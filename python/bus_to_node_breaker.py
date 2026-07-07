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
    Split every bus into this many busbar sections, chained by closed coupling
    devices (a sectionalized single busbar). Feeders are spread round-robin.
``one_busbar_per_generator``
    When set (the default), a bus hosting several generators gets at least one
    busbar section per generator, and each generator is placed on its own
    section - the usual layout for a multi-unit power station.

Supported equipment: generators (min/max or curve reactive limits), loads,
linear/non-linear shunt compensators, lines and two-winding transformers, plus
bus couplers. This covers the IEEE cases and the PEGASE networks. Types not yet
ported (batteries, SVCs, boundary lines, HVDC, tie lines, 3-winding
transformers) raise ``NotImplementedError``.
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
        self._feeder_cursor: Dict[str, int] = defaultdict(int)
        self._gen_cursor: Dict[str, int] = defaultdict(int)

    def section_count_for(self, bus_id: str) -> int:
        wanted = self._min_sections
        if self._one_per_gen:
            gens = self._gens_per_bus.get(bus_id, 0)
            if gens > 1:
                wanted = max(wanted, gens)
        return max(1, wanted)

    def register(self, bus_id: str, bbs_id: str) -> None:
        self._sections.setdefault(bus_id, []).append(bbs_id)

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
    _copy_generators(source, target, busbars, orders)
    _copy_loads(source, target, busbars, orders)
    _copy_shunts(source, target, busbars, orders)
    _copy_lines(source, target, busbars, orders)
    _copy_two_windings_transformers(source, target, busbars, orders)
    _copy_ratio_tap_changers(source, target)
    _copy_phase_tap_changers(source, target)

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
                node += 1
                busbars.register(bus_id, bbs_id)
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
        target.create_reactive_capability_curve_points(
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
    checks = [
        ("batteries", source.get_batteries),
        ("static var compensators", source.get_static_var_compensators),
        ("VSC converter stations", source.get_vsc_converter_stations),
        ("LCC converter stations", source.get_lcc_converter_stations),
        ("HVDC lines", source.get_hvdc_lines),
        ("3-winding transformers", source.get_3_windings_transformers),
    ]
    for name, getter in checks:
        try:
            df = getter()
        except Exception:  # noqa: BLE001 - element type may be absent
            continue
        if not df.empty:
            raise NotImplementedError(
                f"{name} are not supported yet by the pypowsybl converter "
                f"({len(df)} found)")


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
