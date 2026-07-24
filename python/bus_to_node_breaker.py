"""
Convert a bus-breaker network into an electrically equivalent node-breaker
network with pypowsybl.

This is the Python/pypowsybl port of the Java ``BusToNodeBreakerConverter``.
Every configured bus becomes one (or more) busbar section(s); every feeder that
used to sit directly on a bus is reconnected through a disconnector + breaker
bay using pypowsybl's ``create_*_bay`` helpers. The transformation is purely
topological, so an AC load flow on the result matches the original bus-for-bus.

Options
-------
``busbar_sections_per_bus``
    Split every bus *without generators* into this many busbar sections, chained
    by closed coupling devices (a sectionalized single busbar). Feeders are
    spread round-robin. Buses that host generators are governed by
    ``one_busbar_per_generator`` instead (the two counts are decoupled).
``one_busbar_per_generator``
    When set (the default), a bus hosting generators gets exactly one busbar
    section per generator - the usual layout for a multi-unit power station -
    regardless of ``busbar_sections_per_bus``. Each generator is placed on its
    own section (non-generator feeders on the same bus still round-robin across
    those sections). When unset, generator buses follow
    ``busbar_sections_per_bus`` like any other.

Supported equipment: generators, loads, batteries, static var compensators,
dangling (boundary) lines, VSC and LCC converter stations (all reconnected as
injection bays); lines, two- and three-winding transformers with their
ratio/phase tap changers; HVDC lines; tie lines; linear/non-linear shunt
compensators; and bus couplers. Only grounds are left out and raise
``NotImplementedError``.
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from typing import Dict, List, Tuple

import pandas as pd
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

BBS_SUFFIX = "_BBS"


class _BusbarAssignment:
    """Tracks the busbar section(s) of each bus and hands them to feeders."""

    def __init__(self, min_sections: int, one_busbar_per_generator: bool,
                 gens_per_bus: Dict[str, int]):
        self._min_sections = min_sections
        self._one_per_gen = one_busbar_per_generator
        self._gens_per_bus = gens_per_bus
        self._sections: Dict[str, List[str]] = {}
        self._node_of: Dict[str, int] = {}
        self._feeder_cursor: Dict[str, int] = defaultdict(int)
        self._gen_cursor: Dict[str, int] = defaultdict(int)

    def section_count_for(self, bus_id: str) -> int:
        # A bus hosting generators gets exactly one busbar section per generator;
        # busbar_sections_per_bus applies only to buses without generators (the
        # two counts are decoupled).
        if self._one_per_gen and self._gens_per_bus.get(bus_id, 0) >= 1:
            return max(1, self._gens_per_bus[bus_id])
        return max(1, self._min_sections)

    def register(self, bus_id: str, bbs_id: str, node: int) -> None:
        self._sections.setdefault(bus_id, []).append(bbs_id)
        self._node_of[bbs_id] = node

    def node_of(self, bbs_id: str) -> int:
        return self._node_of[bbs_id]

    def sections_of(self, bus_id: str) -> List[str]:
        sections = self._sections.get(bus_id)
        if not sections:
            raise KeyError(f"No busbar section created for bus {bus_id}")
        return sections

    def pick(self, bus_id: str, generator_feeder: bool) -> str:
        sections = self.sections_of(bus_id)
        cursor = self._gen_cursor if generator_feeder else self._feeder_cursor
        i = cursor[bus_id]
        cursor[bus_id] = i + 1
        return sections[i % len(sections)]

    def first(self, bus_id: str) -> str:
        return self.sections_of(bus_id)[0]

    def total(self) -> int:
        return sum(len(v) for v in self._sections.values())


def convert(source: pn.Network, busbar_sections_per_bus: int = 1,
            one_busbar_per_generator: bool = True) -> pn.Network:
    """Return the node-breaker equivalent of ``source``."""
    if busbar_sections_per_bus < 1:
        raise ValueError("busbar_sections_per_bus must be >= 1")

    _reject_unsupported(source)

    target = pn.create_empty(source.id or "network")

    _copy_substations(source, target)
    _copy_voltage_levels(source, target)

    gens_per_bus = {}
    if one_busbar_per_generator:
        gens = source.get_generators(all_attributes=True)
        gens_per_bus = gens["bus_breaker_bus_id"].value_counts().to_dict()
    busbars = _BusbarAssignment(busbar_sections_per_bus,
                                one_busbar_per_generator, gens_per_bus)
    _create_busbars(source, target, busbars)
    _copy_bus_couplers(source, target, busbars)

    orders: Dict[str, int] = defaultdict(lambda: 1)
    # Injections.
    _copy_generators(source, target, busbars, orders)
    _copy_loads(source, target, busbars, orders)
    _copy_shunts(source, target, busbars, orders)
    _copy_batteries(source, target, busbars, orders)
    _copy_static_var_compensators(source, target, busbars, orders)
    _copy_boundary_lines(source, target, busbars, orders)
    _copy_vsc_converter_stations(source, target, busbars, orders)
    _copy_lcc_converter_stations(source, target, busbars, orders)
    # Branches.
    _copy_lines(source, target, busbars, orders)
    _copy_two_windings_transformers(source, target, busbars, orders)
    _copy_three_windings_transformers(source, target, busbars)
    # Networks-level links (their endpoints now exist).
    _copy_hvdc_lines(source, target)
    _copy_tie_lines(source, target)
    # Tap changers, once every terminal exists.
    _copy_ratio_tap_changers(source, target)
    _copy_phase_tap_changers(source, target)
    # Operational limits, extensions and properties, once every element exists.
    _copy_operational_limits(source, target)
    _copy_extensions(source, target)
    _copy_properties(source, target)
    _set_busbar_section_positions(target)

    return target


# ---------------------------------------------------------------------------
# Substations / voltage levels / busbar sections
# ---------------------------------------------------------------------------

def _copy_substations(source: pn.Network, target: pn.Network) -> None:
    subs = source.get_substations(all_attributes=True)
    if subs.empty:
        return
    # A substation's country must be a valid ISO code or omitted entirely, so
    # create substations in batches sharing the same country.
    for country, grp in subs.groupby(subs["country"].fillna("")):
        kwargs = {"id": list(grp.index), "name": list(grp["name"])}
        if country:
            kwargs["country"] = [country] * len(grp)
        tsos = [t if isinstance(t, str) else "" for t in grp["TSO"]]
        if any(tsos):
            kwargs["tso"] = tsos
        target.create_substations(**kwargs)


def _copy_voltage_levels(source: pn.Network, target: pn.Network) -> None:
    vls = source.get_voltage_levels(all_attributes=True)
    if (vls["topology_kind"] != "BUS_BREAKER").any():
        bad = vls.index[vls["topology_kind"] != "BUS_BREAKER"].tolist()
        raise NotImplementedError(
            f"source must be fully bus-breaker; node-breaker VLs: {bad[:5]}")
    target.create_voltage_levels(
        id=list(vls.index),
        substation_id=list(vls["substation_id"]),
        topology_kind=["NODE_BREAKER"] * len(vls),
        nominal_v=list(vls["nominal_v"]),
        low_voltage_limit=list(vls["low_voltage_limit"]),
        high_voltage_limit=list(vls["high_voltage_limit"]),
    )


def _create_busbars(source: pn.Network, target: pn.Network,
                    busbars: _BusbarAssignment) -> None:
    ids, vl_ids, nodes = [], [], []
    couplings = []  # (bbs1, bbs2)
    for vl_id in source.get_voltage_levels().index:
        node = 0
        for bus_id in source.get_bus_breaker_topology(vl_id).buses.index:
            count = busbars.section_count_for(bus_id)
            prev = None
            for k in range(count):
                bbs_id = bus_id + BBS_SUFFIX + (f"_{k + 1}" if count > 1 else "")
                ids.append(bbs_id)
                vl_ids.append(vl_id)
                nodes.append(node)
                busbars.register(bus_id, bbs_id, node)
                node += 1
                if prev is not None:
                    couplings.append((prev, bbs_id))
                prev = bbs_id
    target.create_busbar_sections(id=ids, voltage_level_id=vl_ids, node=nodes)
    for bbs1, bbs2 in couplings:
        pn.create_coupling_device(
            target, bus_or_busbar_section_id_1=bbs1, bus_or_busbar_section_id_2=bbs2)


def _copy_bus_couplers(source: pn.Network, target: pn.Network,
                       busbars: _BusbarAssignment) -> None:
    for vl_id in source.get_voltage_levels().index:
        switches = source.get_bus_breaker_topology(vl_id).switches
        for sw_id, row in switches.iterrows():
            b1, b2 = row["bus1_id"], row["bus2_id"]
            pn.create_coupling_device(
                target,
                bus_or_busbar_section_id_1=busbars.first(b1),
                bus_or_busbar_section_id_2=busbars.first(b2),
                switch_prefix_id=sw_id,
            )


# ---------------------------------------------------------------------------
# Injections
# ---------------------------------------------------------------------------

def _next_order(orders: Dict[str, int], vl_id: str) -> int:
    order = orders[vl_id]
    orders[vl_id] = order + 1
    return order


def _copy_generators(source, target, busbars, orders):
    gens = source.get_generators(all_attributes=True)
    if gens.empty:
        return
    rows = []
    for gid, g in gens.iterrows():
        rows.append({
            "id": gid,
            "energy_source": g["energy_source"],
            "max_p": g["max_p"], "min_p": g["min_p"],
            "target_p": g["target_p"], "target_q": g["target_q"],
            "target_v": g["target_v"], "rated_s": g["rated_s"],
            "voltage_regulator_on": g["voltage_regulator_on"],
            "bus_or_busbar_section_id": busbars.pick(g["bus_breaker_bus_id"], True),
            "position_order": _next_order(orders, g["voltage_level_id"]),
        })
    pn.create_generator_bay(target, pd.DataFrame(rows).set_index("id"))
    _copy_generator_reactive_limits(source, target)


def _copy_generator_reactive_limits(source, target):
    gens = source.get_generators(all_attributes=True)
    minmax = gens[gens["reactive_limits_kind"] == "MIN_MAX"]
    if not minmax.empty:
        target.create_minmax_reactive_limits(
            id=list(minmax.index),
            min_q=list(minmax["min_q"]), max_q=list(minmax["max_q"]))
    curve_ids = gens.index[gens["reactive_limits_kind"] == "CURVE"]
    if len(curve_ids):
        pts = source.get_reactive_capability_curve_points()
        pts = pts[pts.index.get_level_values(0).isin(curve_ids)].reset_index()
        target.create_curve_reactive_limits(
            id=list(pts["id"]), p=list(pts["p"]),
            min_q=list(pts["min_q"]), max_q=list(pts["max_q"]))


def _copy_loads(source, target, busbars, orders):
    loads = source.get_loads(all_attributes=True)
    if loads.empty:
        return
    rows = []
    for lid, l in loads.iterrows():
        rows.append({
            "id": lid, "type": l["type"], "p0": l["p0"], "q0": l["q0"],
            "bus_or_busbar_section_id": busbars.pick(l["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, l["voltage_level_id"]),
        })
    pn.create_load_bay(target, pd.DataFrame(rows).set_index("id"))


def _copy_shunts(source, target, busbars, orders):
    shunts = source.get_shunt_compensators(all_attributes=True)
    if shunts.empty:
        return
    shunt_rows, linear_rows, nonlinear_rows = [], [], []
    for sid, sh in shunts.iterrows():
        shunt_rows.append({
            "id": sid, "model_type": sh["model_type"],
            "section_count": sh["section_count"],
            "target_v": sh["target_v"], "target_deadband": sh["target_deadband"],
            "bus_or_busbar_section_id": busbars.pick(sh["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, sh["voltage_level_id"]),
        })
        if sh["model_type"] == "LINEAR":
            lin = source.get_linear_shunt_compensator_sections().loc[sid]
            linear_rows.append({
                "id": sid, "g_per_section": lin["g_per_section"],
                "b_per_section": lin["b_per_section"],
                "max_section_count": int(sh["max_section_count"]),
            })
        else:
            secs = source.get_non_linear_shunt_compensator_sections().loc[[sid]]
            for _, sec in secs.iterrows():
                nonlinear_rows.append(
                    {"id": sid, "g": sec["g"], "b": sec["b"]})
    linear_df = pd.DataFrame(linear_rows).set_index("id") if linear_rows else None
    nonlinear_df = pd.DataFrame(nonlinear_rows).set_index("id") if nonlinear_rows else None
    pn.create_shunt_compensator_bay(
        target, pd.DataFrame(shunt_rows).set_index("id"),
        linear_model_df=linear_df, non_linear_model_df=nonlinear_df)


def _copy_reactive_limits(source, target, ids, getter_name):
    """Copy min/max or curve reactive limits for a set of element ids."""
    df = getattr(source, getter_name)(all_attributes=True)
    df = df[df.index.isin(ids)]
    minmax = df[df["reactive_limits_kind"] == "MIN_MAX"]
    if not minmax.empty:
        target.create_minmax_reactive_limits(
            id=list(minmax.index),
            min_q=list(minmax["min_q"]), max_q=list(minmax["max_q"]))
    curve_ids = df.index[df["reactive_limits_kind"] == "CURVE"]
    if len(curve_ids):
        pts = source.get_reactive_capability_curve_points()
        pts = pts[pts.index.get_level_values(0).isin(curve_ids)].reset_index()
        target.create_curve_reactive_limits(
            id=list(pts["id"]), p=list(pts["p"]),
            min_q=list(pts["min_q"]), max_q=list(pts["max_q"]))


def _copy_batteries(source, target, busbars, orders):
    bats = source.get_batteries(all_attributes=True)
    if bats.empty:
        return
    rows = []
    for bid, b in bats.iterrows():
        rows.append({
            "id": bid, "min_p": b["min_p"], "max_p": b["max_p"],
            "target_p": b["target_p"], "target_q": b["target_q"],
            "bus_or_busbar_section_id": busbars.pick(b["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, b["voltage_level_id"]),
        })
    pn.create_battery_bay(target, pd.DataFrame(rows).set_index("id"))
    _copy_reactive_limits(source, target, list(bats.index), "get_batteries")


def _copy_static_var_compensators(source, target, busbars, orders):
    svcs = source.get_static_var_compensators(all_attributes=True)
    if svcs.empty:
        return
    rows = []
    for sid, svc in svcs.iterrows():
        rows.append({
            "id": sid, "b_min": svc["b_min"], "b_max": svc["b_max"],
            "regulation_mode": svc["regulation_mode"],
            "regulating": svc["regulating"],
            "target_v": svc["target_v"], "target_q": svc["target_q"],
            "bus_or_busbar_section_id": busbars.pick(svc["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, svc["voltage_level_id"]),
        })
    pn.create_static_var_compensator_bay(target, pd.DataFrame(rows).set_index("id"))


def _copy_boundary_lines(source, target, busbars, orders):
    bls = source.get_boundary_lines(all_attributes=True)
    if bls.empty:
        return
    rows = []
    for bid, bl in bls.iterrows():
        key = bl["pairing_key"]
        rows.append({
            "id": bid, "p0": bl["p0"], "q0": bl["q0"],
            "r": bl["r"], "x": bl["x"], "g": bl["g"], "b": bl["b"],
            "pairing_key": key if isinstance(key, str) else "",
            "bus_or_busbar_section_id": busbars.pick(bl["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, bl["voltage_level_id"]),
        })
    pn.create_boundary_line_bay(target, pd.DataFrame(rows).set_index("id"))


def _copy_vsc_converter_stations(source, target, busbars, orders):
    vscs = source.get_vsc_converter_stations(all_attributes=True)
    if vscs.empty:
        return
    rows = []
    for vid, v in vscs.iterrows():
        rows.append({
            "id": vid, "loss_factor": v["loss_factor"],
            "voltage_regulator_on": v["voltage_regulator_on"],
            "target_v": v["target_v"], "target_q": v["target_q"],
            "bus_or_busbar_section_id": busbars.pick(v["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, v["voltage_level_id"]),
        })
    pn.create_vsc_converter_station_bay(target, pd.DataFrame(rows).set_index("id"))
    _copy_reactive_limits(source, target, list(vscs.index), "get_vsc_converter_stations")


def _copy_lcc_converter_stations(source, target, busbars, orders):
    lccs = source.get_lcc_converter_stations(all_attributes=True)
    if lccs.empty:
        return
    rows = []
    for lid, l in lccs.iterrows():
        rows.append({
            "id": lid, "power_factor": l["power_factor"], "loss_factor": l["loss_factor"],
            "bus_or_busbar_section_id": busbars.pick(l["bus_breaker_bus_id"], False),
            "position_order": _next_order(orders, l["voltage_level_id"]),
        })
    pn.create_lcc_converter_station_bay(target, pd.DataFrame(rows).set_index("id"))


# ---------------------------------------------------------------------------
# Branches
# ---------------------------------------------------------------------------

def _copy_lines(source, target, busbars, orders):
    lines = source.get_lines(all_attributes=True)
    if lines.empty:
        return
    rows = []
    for lid, ln in lines.iterrows():
        rows.append({
            "id": lid,
            "r": ln["r"], "x": ln["x"],
            "g1": ln["g1"], "b1": ln["b1"], "g2": ln["g2"], "b2": ln["b2"],
            "bus_or_busbar_section_id_1": busbars.pick(ln["bus_breaker_bus1_id"], False),
            "bus_or_busbar_section_id_2": busbars.pick(ln["bus_breaker_bus2_id"], False),
            "position_order_1": _next_order(orders, ln["voltage_level1_id"]),
            "position_order_2": _next_order(orders, ln["voltage_level2_id"]),
        })
    pn.create_line_bays(target, pd.DataFrame(rows).set_index("id"))


def _copy_two_windings_transformers(source, target, busbars, orders):
    txs = source.get_2_windings_transformers(all_attributes=True)
    if txs.empty:
        return
    rows = []
    for tid, tx in txs.iterrows():
        rows.append({
            "id": tid,
            "r": tx["r"], "x": tx["x"], "g": tx["g"], "b": tx["b"],
            "rated_u1": tx["rated_u1"], "rated_u2": tx["rated_u2"],
            "rated_s": tx["rated_s"],
            "bus_or_busbar_section_id_1": busbars.pick(tx["bus_breaker_bus1_id"], False),
            "bus_or_busbar_section_id_2": busbars.pick(tx["bus_breaker_bus2_id"], False),
            "position_order_1": _next_order(orders, tx["voltage_level1_id"]),
            "position_order_2": _next_order(orders, tx["voltage_level2_id"]),
        })
    pn.create_2_windings_transformer_bays(target, pd.DataFrame(rows).set_index("id"))


def _copy_three_windings_transformers(source, target, busbars):
    """
    Three-winding transformers have no ready-made bay helper, so each of the
    three legs gets a hand-built bay: a disconnector from its busbar section to
    a fresh node, then a breaker to the node the leg terminal sits on.
    """
    t3s = source.get_3_windings_transformers(all_attributes=True)
    if t3s.empty:
        return

    next_node: Dict[str, int] = {}

    def alloc(vl_id: str) -> int:
        if vl_id not in next_node:
            nodes = target.get_node_breaker_topology(vl_id).nodes
            next_node[vl_id] = (int(nodes.index.max()) + 1) if len(nodes) else 0
        node = next_node[vl_id]
        next_node[vl_id] = node + 1
        return node

    sw_id, sw_vl, sw_n1, sw_n2, sw_kind = [], [], [], [], []
    rows = []
    for tid, t in t3s.iterrows():
        row = {"id": tid, "rated_u0": t["rated_u0"]}
        for side in (1, 2, 3):
            vl_id = t[f"voltage_level{side}_id"]
            bbs = busbars.pick(t[f"bus_breaker_bus{side}_id"], False)
            bbs_node = busbars.node_of(bbs)
            mid, equip = alloc(vl_id), alloc(vl_id)
            sw_id += [f"{tid}_DISC_{side}", f"{tid}_BRK_{side}"]
            sw_vl += [vl_id, vl_id]
            sw_n1 += [bbs_node, mid]
            sw_n2 += [mid, equip]
            sw_kind += ["DISCONNECTOR", "BREAKER"]
            row.update({
                f"voltage_level{side}_id": vl_id, f"node{side}": equip,
                f"rated_u{side}": t[f"rated_u{side}"],
                f"r{side}": t[f"r{side}"], f"x{side}": t[f"x{side}"],
                f"g{side}": t[f"g{side}"], f"b{side}": t[f"b{side}"],
            })
        rows.append(row)
    target.create_switches(id=sw_id, voltage_level_id=sw_vl, node1=sw_n1, node2=sw_n2,
                           kind=sw_kind, open=[False] * len(sw_id))
    target.create_3_windings_transformers(pd.DataFrame(rows).set_index("id"))


def _copy_hvdc_lines(source, target):
    hv = source.get_hvdc_lines(all_attributes=True)
    if hv.empty:
        return
    target.create_hvdc_lines(pd.DataFrame({
        "id": list(hv.index),
        "converter_station1_id": list(hv["converter_station1_id"]),
        "converter_station2_id": list(hv["converter_station2_id"]),
        "r": list(hv["r"]), "nominal_v": list(hv["nominal_v"]),
        "max_p": list(hv["max_p"]), "target_p": list(hv["target_p"]),
        "converters_mode": list(hv["converters_mode"]),
    }).set_index("id"))


def _copy_tie_lines(source, target):
    tl = source.get_tie_lines(all_attributes=True)
    if tl.empty:
        return
    target.create_tie_lines(pd.DataFrame({
        "id": list(tl.index),
        "boundary_line1_id": list(tl["boundary_line1_id"]),
        "boundary_line2_id": list(tl["boundary_line2_id"]),
    }).set_index("id"))


def _regulated_side(tid, reg_bus, txs):
    """Map a tap changer's regulating bus to the ONE/TWO side of its 2wt."""
    if isinstance(reg_bus, str) and reg_bus and reg_bus == txs.loc[tid, "bus1_id"]:
        return "ONE"
    return "TWO"


def _copy_ratio_tap_changers(source, target):
    rtc = source.get_ratio_tap_changers()
    if rtc.empty:
        return
    txs = source.get_2_windings_transformers(all_attributes=True)
    # 2-winding transformers only (three-winding legs carry a non-empty side).
    rtc = rtc[(rtc["side"] == "") & rtc.index.isin(txs.index)]
    if rtc.empty:
        return
    rtc_df = pd.DataFrame({
        "id": list(rtc.index),
        "tap": list(rtc["tap"]),
        "low_tap": list(rtc["low_tap"]),
        "oltc": list(rtc["oltc"]),
        "target_v": list(rtc["target_v"]),
        "target_deadband": [d if pd.notna(d) else 0.0 for d in rtc["target_deadband"]],
        "regulating": list(rtc["regulating"]),
        "regulated_side": [_regulated_side(t, b, txs)
                           for t, b in zip(rtc.index, rtc["regulating_bus_id"])],
    }).set_index("id")
    steps = source.get_ratio_tap_changer_steps().reset_index()
    steps = steps[steps["id"].isin(rtc.index)]
    steps_df = steps[["id", "rho", "r", "x", "g", "b"]].set_index("id")
    target.create_ratio_tap_changers(rtc_df, steps_df)


def _copy_phase_tap_changers(source, target):
    ptc = source.get_phase_tap_changers()
    if ptc.empty:
        return
    txs = source.get_2_windings_transformers(all_attributes=True)
    ptc = ptc[(ptc["side"] == "") & ptc.index.isin(txs.index)]
    if ptc.empty:
        return
    ptc_df = pd.DataFrame({
        "id": list(ptc.index),
        "tap": list(ptc["tap"]),
        "low_tap": list(ptc["low_tap"]),
        "regulation_mode": list(ptc["regulation_mode"]),
        "target_deadband": [d if pd.notna(d) else 0.0 for d in ptc["target_deadband"]],
        "regulating": list(ptc["regulating"]),
        "regulated_side": [_regulated_side(t, b, txs)
                           for t, b in zip(ptc.index, ptc["regulating_bus_id"])],
    }).set_index("id")
    steps = source.get_phase_tap_changer_steps().reset_index()
    steps = steps[steps["id"].isin(ptc.index)]
    steps_df = steps[["id", "rho", "alpha", "r", "x", "g", "b"]].set_index("id")
    target.create_phase_tap_changers(ptc_df, steps_df)


# ---------------------------------------------------------------------------
# Operational limits and extensions
# ---------------------------------------------------------------------------

# element getter -> (updater, [selected-group column per side], [iidm side label per side])
_SELECTED_GROUP_SPEC = {
    "get_lines": ("update_lines",
                  ["selected_limits_group_1", "selected_limits_group_2"], ["ONE", "TWO"]),
    "get_2_windings_transformers": ("update_2_windings_transformers",
                                    ["selected_limits_group_1", "selected_limits_group_2"],
                                    ["ONE", "TWO"]),
    "get_3_windings_transformers": ("update_3_windings_transformers",
                                    ["selected_limits_group_1", "selected_limits_group_2",
                                     "selected_limits_group_3"], ["ONE", "TWO", "THREE"]),
    "get_boundary_lines": ("update_boundary_lines", ["selected_limits_group"], ["NONE"]),
}

# Extensions the conversion itself sets (feeder ordering) - never copy these over.
_CONVERSION_OWNED_EXTENSIONS = {"position", "busbarSectionPosition"}


def _copy_operational_limits(source: pn.Network, target: pn.Network) -> None:
    """Copy every operational-limit group and restore each element's active group.

    The converter rebuilds the network from scratch, so without this every
    current / apparent-power / active-power limit (and the selected group) would
    be lost.
    """
    limits = source.get_operational_limits(show_inactive_sets=True)
    if not limits.empty:
        r = limits.reset_index()
        create_df = pd.DataFrame({
            "element_id": r["element_id"], "side": r["side"], "name": r["name"],
            "type": r["type"], "value": r["value"],
            "acceptable_duration": r["acceptable_duration"], "group_name": r["group_name"],
        }).set_index("element_id")
        target.create_operational_limits(create_df)

    _restore_selected_groups(source, target)


def _restore_selected_groups(source: pn.Network, target: pn.Network) -> None:
    # A group can only be selected on a side that actually carries a limit there,
    # so restrict to (element, side, group) triples that now exist on the target.
    present = set()
    tgt_limits = target.get_operational_limits(show_inactive_sets=True)
    if not tgt_limits.empty:
        idx = tgt_limits.index
        present = set(zip(idx.get_level_values("element_id"),
                          idx.get_level_values("side"),
                          idx.get_level_values("group_name")))

    for getter_name, (updater, group_cols, side_labels) in _SELECTED_GROUP_SPEC.items():
        df = getattr(source, getter_name)(all_attributes=True)
        if df.empty:
            continue
        for col, side in zip(group_cols, side_labels):
            if col not in df.columns:
                continue
            ids, groups = [], []
            for eid, grp in df[col].items():
                if isinstance(grp, str) and grp and (eid, side, grp) in present:
                    ids.append(eid)
                    groups.append(grp)
            if ids:
                getattr(target, updater)(id=ids, **{col: groups})


def _copy_extensions(source: pn.Network, target: pn.Network) -> None:
    """Copy element-keyed extensions onto the rebuilt network.

    Covers extensions attached to a single connectable by its id
    (activePowerControl, generatorShortCircuit, entsoeCategory, ...). Extensions
    the conversion manages itself (feeder position) are skipped, as are
    topology/terminal-bound extensions (e.g. slackTerminal) whose reference does
    not survive a rebuild - those are indexed by something other than ``id`` and
    are left out.
    """
    for name in pn.get_extensions_information().index:
        if name in _CONVERSION_OWNED_EXTENSIONS:
            continue
        try:
            df = source.get_extensions(name)
        except Exception:  # noqa: BLE001 - extension may be unsupported here
            continue
        # Only copy extensions keyed by a single element id; terminal/position
        # bound extensions are indexed differently and cannot be blindly rebuilt.
        if df is None or df.empty or df.index.name != "id":
            continue
        try:
            target.create_extensions(name, df)
        except Exception:  # noqa: BLE001 - element absent or schema mismatch
            continue


def _copy_properties(source: pn.Network, target: pn.Network) -> None:
    """Copy element key/value properties onto the rebuilt network.

    Properties are stored per identifiable; the rebuild keeps every id except
    the (recomputed) buses, so properties are re-applied to whatever elements
    still exist. ``get_elements_properties`` returns a long (id, key, value)
    frame; ``add_elements_properties`` wants one call per key.
    """
    props = source.get_elements_properties()
    if props.empty:
        return
    existing = set(target.get_elements(pn.ElementType.IDENTIFIABLE).index)
    for key, group in props.groupby("key"):
        sub = group[group.index.isin(existing)]
        if sub.empty:
            continue
        target.add_elements_properties(
            id=list(sub.index), **{key: [str(v) for v in sub["value"]]})


def _set_busbar_section_positions(target: pn.Network) -> None:
    """Give every rebuilt busbar section a ``busbarSectionPosition`` extension.

    Node-breaker models tag each busbar section with a (busbar, section) index
    for single-line-diagram layout. The rebuild creates the sections without it,
    so number them per voltage level: one busbar, sections 1..k.
    """
    bbs = target.get_busbar_sections(all_attributes=True)
    if bbs.empty:
        return
    section_of = {}
    ids, busbar_index, section_index = [], [], []
    for bid, b in bbs.sort_index().iterrows():
        vl = b["voltage_level_id"]
        nxt = section_of.get(vl, 0) + 1
        section_of[vl] = nxt
        ids.append(bid)
        busbar_index.append(1)
        section_index.append(nxt)
    target.create_extensions("busbarSectionPosition", id=ids,
                             busbar_index=busbar_index, section_index=section_index)


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

def _bus_voltage_by_element(net: pn.Network) -> Dict[Tuple[str, str], Tuple[float, float]]:
    """Voltage (mag, angle) at each generator's and load's bus, keyed by element."""
    buses = net.get_buses()
    out = {}
    for kind, df in (("G", net.get_generators()), ("L", net.get_loads())):
        for eid, row in df.iterrows():
            b = row["bus_id"]
            if b in buses.index:
                out[(kind, eid)] = (buses.at[b, "v_mag"], buses.at[b, "v_angle"])
    return out


def validate(source: pn.Network, target: pn.Network) -> dict:
    """
    Run an AC load flow on both networks and compare bus voltages at every
    shared element. Angles are compared modulo a common reference bus. A flat
    start is tried first, then a DC-based start (which converges large cases
    like case9241pegase where a flat start does not).
    """
    for init in (lf.VoltageInitMode.UNIFORM_VALUES, lf.VoltageInitMode.DC_VALUES):
        params = lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                               voltage_init_mode=init)
        src = lf.run_ac(source, params)
        tgt = lf.run_ac(target, params)
        src_ok = src[0].status.name == "CONVERGED"
        tgt_ok = tgt[0].status.name == "CONVERGED"
        if src_ok and tgt_ok:
            break
    result = {"init_mode": init.name, "source_converged": src_ok,
              "target_converged": tgt_ok, "max_dv_kv": None, "max_dangle_deg": None}
    if not (src_ok and tgt_ok):
        return result
    sv = _bus_voltage_by_element(source)
    tv = _bus_voltage_by_element(target)
    keys = [k for k in sv if k in tv]
    ref = keys[0]
    s_ref, t_ref = sv[ref][1], tv[ref][1]
    result["max_dv_kv"] = max(abs(sv[k][0] - tv[k][0]) for k in keys)
    result["max_dangle_deg"] = max(
        abs((sv[k][1] - s_ref) - (tv[k][1] - t_ref)) for k in keys)
    return result


# ---------------------------------------------------------------------------
# Guard
# ---------------------------------------------------------------------------

def _reject_unsupported(source: pn.Network) -> None:
    # Every standard connectable is handled; only grounds (a rare, injection-less
    # connection to earth) are left out, matching the Java converter.
    getter = getattr(source, "get_grounds", None)
    if getter is not None:
        try:
            grounds = getter()
        except Exception:  # noqa: BLE001 - element type may be absent
            grounds = None
        if grounds is not None and not grounds.empty:
            raise NotImplementedError(
                f"grounds are not supported yet ({len(grounds)} found)")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

_BUILTINS = {
    "ieee14": pn.create_ieee14,
    "ieee118": pn.create_ieee118,
    "ieee300": pn.create_ieee300,
}


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a bus-breaker network into an equivalent "
                    "node-breaker network (one busbar section per bus, feeders "
                    "reconnected through disconnector + breaker bays).")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("-i", "--input", help="input bus-breaker network file")
    src.add_argument("--builtin", choices=sorted(_BUILTINS),
                     help="use a bundled network instead of --input")
    parser.add_argument("-o", "--output", help="write the node-breaker network here")
    parser.add_argument("--busbars-per-bus", type=int, default=1,
                        help="busbar sections per bus, chained by closed couplers "
                             "(default: 1)")
    parser.add_argument("--no-generator-busbars", action="store_true",
                        help="disable one busbar section per generator on "
                             "multi-generator buses")
    parser.add_argument("--validate", action="store_true",
                        help="run an AC load flow on both networks and compare")
    args = parser.parse_args(argv)

    source = _BUILTINS[args.builtin]() if args.builtin else pn.load(args.input)
    target = convert(source, args.busbars_per_bus, not args.no_generator_busbars)
    print(f"Converted '{source.id}' to node-breaker: "
          f"{len(target.get_busbar_sections())} busbar section(s) over "
          f"{len(target.get_voltage_levels())} voltage level(s).")

    if args.validate:
        r = validate(source, target)
        print(f"Init mode : {r['init_mode']}")
        print(f"Source    : {'CONVERGED' if r['source_converged'] else 'NOT CONVERGED'}")
        print(f"Converted : {'CONVERGED' if r['target_converged'] else 'NOT CONVERGED'}")
        if r["max_dv_kv"] is None:
            print("Validation FAILED: a load flow did not converge.")
            return 1
        print(f"Max |dV|     : {r['max_dv_kv']:.3e} kV")
        print(f"Max |dAngle| : {r['max_dangle_deg']:.3e} deg")
        if r["max_dv_kv"] >= 1e-2 or r["max_dangle_deg"] >= 1e-1:
            print("Validation FAILED: networks are not equivalent.")
            return 1
        print("Validation OK: bus voltages match the original.")

    if args.output:
        target.save(args.output, format="XIIDM")
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
