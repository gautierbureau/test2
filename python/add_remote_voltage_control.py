"""
Introduce remote voltage control by deporting generators behind a step-up
transformer (node-breaker, with feeder bays).

OpenLoadFlow's remote-voltage-control outer loop is only exercised when a
generator regulates a bus other than its own. A realistic way to create that:
take a generator sitting directly on a high-voltage bus, move it onto a new
low-voltage bus behind a dedicated generator step-up (GSU) transformer, and have
it keep regulating the **high-voltage** bus - i.e. remotely, through the
transformer.

Everything is built in node-breaker form with feeder bays
(``create_2_windings_transformer_bays`` / ``create_generator_bay``), so this must
run on a network that is already node-breaker. The generator keeps its original
active/reactive setpoints, reactive limits and key extensions; its voltage
target (an HV setpoint) is unchanged, so the base case stays close and still
converges (the only new element is a low-reactance GSU transformer).
"""

from __future__ import annotations

import math
from typing import Optional

import pandas as pd
import pypowsybl.network as pn

DEFAULT_HV_THRESHOLD = 200.0   # only deport generators on buses >= this (kV)
DEFAULT_LV_NOMINAL_V = 20.0    # generator step-up LV voltage (kV)
DEFAULT_GSU_X_PU = 0.14        # GSU transformer reactance (pu on its rating)
DEFAULT_DEPORT_RATE = 6 / 7803  # generators to deport per bus (sparse)


def _capture_reactive_limits(network, gid, kind, row, curve_pts):
    if kind == "CURVE" and gid in curve_pts.index.get_level_values(0):
        pts = curve_pts.loc[gid]
        return ("CURVE", list(pts["p"]), list(pts["min_q"]), list(pts["max_q"]))
    min_q = row["min_q"] if math.isfinite(row["min_q"]) else -abs(row["max_q"])
    max_q = row["max_q"] if math.isfinite(row["max_q"]) else abs(row["min_q"])
    return ("MIN_MAX", min_q, max_q)


def _reapply_reactive_limits(network, gid, captured):
    if captured[0] == "CURVE":
        _, ps, min_qs, max_qs = captured
        network.create_curve_reactive_limits(
            id=[gid] * len(ps), p=ps, min_q=min_qs, max_q=max_qs)
    else:
        _, min_q, max_q = captured
        network.create_minmax_reactive_limits(id=[gid], min_q=[min_q], max_q=[max_q])


def deport_generators(
    network: pn.Network,
    count: Optional[int] = None,
    hv_threshold: float = DEFAULT_HV_THRESHOLD,
    lv_nominal_v: float = DEFAULT_LV_NOMINAL_V,
    x_pu: float = DEFAULT_GSU_X_PU,
) -> dict:
    """Deport generators behind a GSU transformer with remote HV voltage control.

    Operates on a node-breaker network. Returns a summary dict.
    """
    vls = network.get_voltage_levels(all_attributes=True)
    nominal = vls["nominal_v"]
    substation = vls["substation_id"]
    bbs = network.get_busbar_sections(all_attributes=True)

    gens = network.get_generators(all_attributes=True)
    gens = gens.assign(_vn=[float(nominal.get(v, 0.0)) for v in gens["voltage_level_id"]])
    eligible = gens[gens["_vn"] >= hv_threshold].sort_values(
        ["_vn"], ascending=False)
    eligible = eligible[eligible.index.map(  # a busbar section must exist on the VL
        lambda gid: (bbs["voltage_level_id"] == gens.at[gid, "voltage_level_id"]).any())]
    if eligible.empty:
        return {"deported": 0, "eligible": 0}

    n = count if count is not None else max(1, round(len(network.get_buses()) * DEFAULT_DEPORT_RATE))
    n = min(n, len(eligible))
    # Evenly spaced across the eligible (highest-voltage) generators.
    selected = [eligible.index[(i * len(eligible)) // n] for i in range(n)]

    # Extension tables captured once (recreating a generator drops its extensions).
    apc = _safe_ext(network, "activePowerControl")
    gsc = _safe_ext(network, "generatorShortCircuit")
    curve_pts = network.get_reactive_capability_curve_points()

    deported = 0
    for i, gid in enumerate(selected):
        row = gens.loc[gid]
        vl_hv = row["voltage_level_id"]
        vhv = float(nominal[vl_hv])
        sub = substation[vl_hv]
        hv_bbs = bbs[bbs["voltage_level_id"] == vl_hv].index[0]
        s = row["rated_s"] if (math.isfinite(row["rated_s"]) and row["rated_s"] > 0) \
            else max(abs(row["max_p"]), 1.0) / 0.85

        captured_q = _capture_reactive_limits(network, gid, row["reactive_limits_kind"],
                                              row, curve_pts)
        apc_row = apc.loc[gid] if (apc is not None and gid in apc.index) else None
        gsc_row = gsc.loc[gid] if (gsc is not None and gid in gsc.index) else None

        # New LV voltage level + busbar section in the same substation.
        vl_lv = f"{gid}_GSU_VL"
        lv_bbs = f"{gid}_GSU_BBS"
        network.create_voltage_levels(id=[vl_lv], substation_id=[sub],
                                      topology_kind=["NODE_BREAKER"], nominal_v=[lv_nominal_v])
        network.create_busbar_sections(id=[lv_bbs], voltage_level_id=[vl_lv], node=[0])
        network.create_extensions("busbarSectionPosition", id=[lv_bbs],
                                  busbar_index=[1], section_index=[1])

        # GSU transformer: HV bay on the existing HV busbar, LV bay on the new one.
        x = x_pu * lv_nominal_v * lv_nominal_v / s
        tx = f"{gid}_GSU_TX"
        pn.create_2_windings_transformer_bays(network, pd.DataFrame([{
            "id": tx, "r": x / 20.0, "x": x, "g": 0.0, "b": 0.0,
            "rated_u1": vhv, "rated_u2": lv_nominal_v, "rated_s": s,
            "bus_or_busbar_section_id_1": hv_bbs, "bus_or_busbar_section_id_2": lv_bbs,
            "position_order_1": 10000 + i, "position_order_2": 1,
        }]).set_index("id"))

        # Move the generator to the LV bus and regulate the HV bus remotely.
        network.remove_elements(gid)
        pn.create_generator_bay(network, pd.DataFrame([{
            "id": gid, "energy_source": row["energy_source"],
            "max_p": row["max_p"], "min_p": row["min_p"],
            "target_p": row["target_p"], "target_q": row["target_q"],
            "target_v": row["target_v"], "rated_s": s, "voltage_regulator_on": True,
            "bus_or_busbar_section_id": lv_bbs, "position_order": 2,
        }]).set_index("id"))
        _reapply_reactive_limits(network, gid, captured_q)
        network.update_generators(id=[gid], regulated_element_id=[hv_bbs])

        if apc_row is not None:
            network.create_extensions(
                "activePowerControl", id=[gid], participate=[bool(apc_row["participate"])],
                droop=[float(apc_row["droop"])],
                participation_factor=[float(apc_row["participation_factor"])])
        if gsc_row is not None:
            network.create_extensions(
                "generatorShortCircuit", id=[gid],
                direct_trans_x=[float(gsc_row["direct_trans_x"])],
                direct_sub_trans_x=[float(gsc_row["direct_sub_trans_x"])],
                step_up_transformer_x=[float(gsc_row["step_up_transformer_x"])])
        deported += 1

    return {"deported": deported, "eligible": len(eligible)}


def _safe_ext(network, name):
    try:
        df = network.get_extensions(name)
        return df if not df.empty else None
    except Exception:  # noqa: BLE001
        return None
