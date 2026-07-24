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
run on a network that is already node-breaker.

Deporting an EHV generator can occasionally upset convergence (the machine now
supports the HV bus through a transformer). To stay robust, generators are
deported **one at a time with a convergence check**: the network is solved after
each move and, if it no longer converges, that one deportation is rolled back
(via a checkpoint) and the generator is skipped. The function therefore always
returns a network that still converges, having deported as many generators as it
safely can. Because a rollback reloads the network, the (possibly new) network
object is returned alongside the stats.
"""

from __future__ import annotations

import math
import os
import shutil
import tempfile
from typing import Optional, Tuple

import pandas as pd
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

DEFAULT_HV_THRESHOLD = 200.0   # only deport generators on buses >= this (kV)
DEFAULT_LV_NOMINAL_V = 20.0    # generator step-up LV voltage (kV)
DEFAULT_GSU_X_PU = 0.06        # GSU transformer reactance (pu on its rating)
DEFAULT_DEPORT_RATE = 6 / 7803  # generators to deport per bus (sparse)

_DC_PARAMS = lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                           voltage_init_mode=lf.VoltageInitMode.DC_VALUES)


def _converges(network: pn.Network) -> bool:
    return lf.run_ac(network, _DC_PARAMS)[0].status.name == "CONVERGED"


def _eligible_generators(network: pn.Network, hv_threshold: float):
    """Generator ids on buses >= hv_threshold, highest voltage first."""
    vls = network.get_voltage_levels()
    nominal = vls["nominal_v"]
    bbs = network.get_busbar_sections(all_attributes=True)
    gens = network.get_generators(all_attributes=True)
    gens = gens.assign(_vn=[float(nominal.get(v, 0.0)) for v in gens["voltage_level_id"]])
    gens = gens[gens["_vn"] >= hv_threshold].sort_values("_vn", ascending=False)
    vls_with_bbs = set(bbs["voltage_level_id"])
    return [gid for gid in gens.index if gens.at[gid, "voltage_level_id"] in vls_with_bbs]


def _deport_one(network: pn.Network, gid: str, lv_nominal_v: float, x_pu: float,
                apc, gsc) -> None:
    """Move one generator onto a new LV bus behind a GSU transformer (remote VC)."""
    vls = network.get_voltage_levels(all_attributes=True)
    nominal = vls["nominal_v"]
    substation = vls["substation_id"]
    bbs = network.get_busbar_sections(all_attributes=True)
    buses = network.get_buses()
    row = network.get_generators(all_attributes=True).loc[gid]

    vl_hv = row["voltage_level_id"]
    vhv = float(nominal[vl_hv])
    sub = substation[vl_hv]
    hv_bbs = bbs[bbs["voltage_level_id"] == vl_hv].index[0]
    hv_bus = row["bus_id"]
    v_target = buses.at[hv_bus, "v_mag"] if (
        hv_bus in buses.index and math.isfinite(buses.at[hv_bus, "v_mag"])
        and buses.at[hv_bus, "v_mag"] > 0) else row["target_v"]
    s = row["rated_s"] if (math.isfinite(row["rated_s"]) and row["rated_s"] > 0) \
        else max(abs(row["max_p"]), 1.0) / 0.85

    apc_row = apc.loc[gid] if (apc is not None and gid in apc.index) else None
    gsc_row = gsc.loc[gid] if (gsc is not None and gid in gsc.index) else None

    vl_lv = f"{gid}_GSU_VL"
    lv_bbs = f"{gid}_GSU_BBS"
    network.create_voltage_levels(id=[vl_lv], substation_id=[sub],
                                  topology_kind=["NODE_BREAKER"], nominal_v=[lv_nominal_v])
    network.create_busbar_sections(id=[lv_bbs], voltage_level_id=[vl_lv], node=[0])
    network.create_extensions("busbarSectionPosition", id=[lv_bbs],
                              busbar_index=[1], section_index=[1])

    x = x_pu * lv_nominal_v * lv_nominal_v / s
    tx = f"{gid}_GSU_TX"
    pn.create_2_windings_transformer_bays(network, pd.DataFrame([{
        "id": tx, "r": x / 20.0, "x": x, "g": 0.0, "b": 0.0,
        "rated_u1": vhv, "rated_u2": lv_nominal_v, "rated_s": s,
        "bus_or_busbar_section_id_1": hv_bbs, "bus_or_busbar_section_id_2": lv_bbs,
        "position_order_1": 10000, "position_order_2": 1,
    }]).set_index("id"))

    network.remove_elements(gid)
    pn.create_generator_bay(network, pd.DataFrame([{
        "id": gid, "energy_source": row["energy_source"],
        "max_p": row["max_p"], "min_p": row["min_p"],
        "target_p": row["target_p"], "target_q": row["target_q"],
        "target_v": v_target, "rated_s": s, "voltage_regulator_on": True,
        "bus_or_busbar_section_id": lv_bbs, "position_order": 2,
    }]).set_index("id"))
    # Wide reactive band so remote control keeps headroom through the transformer.
    network.create_minmax_reactive_limits(id=[gid], min_q=[-s], max_q=[s])
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


def deport_generators(
    network: pn.Network,
    count: Optional[int] = None,
    hv_threshold: float = DEFAULT_HV_THRESHOLD,
    lv_nominal_v: float = DEFAULT_LV_NOMINAL_V,
    x_pu: float = DEFAULT_GSU_X_PU,
) -> Tuple[pn.Network, dict]:
    """Deport generators behind a GSU transformer with remote HV voltage control.

    Operates on a node-breaker network. Returns ``(network, stats)`` - the
    network is returned because a rolled-back deportation reloads it.
    """
    if not _converges(network):
        return network, {"deported": 0, "eligible": 0, "skipped": 0,
                         "note": "base did not converge"}

    eligible = _eligible_generators(network, hv_threshold)
    if not eligible:
        return network, {"deported": 0, "eligible": 0, "skipped": 0}
    target = count if count is not None else max(
        1, round(len(network.get_buses()) * DEFAULT_DEPORT_RATE))
    target = min(target, len(eligible))

    apc = _safe_ext(network, "activePowerControl")
    gsc = _safe_ext(network, "generatorShortCircuit")

    tmp = tempfile.mkdtemp(prefix="rvc_ckpt_")
    ckpt = os.path.join(tmp, "checkpoint.xiidm")
    kept = 0
    skipped = 0
    try:
        network.save(ckpt, format="XIIDM")
        for gid in eligible:
            if kept >= target:
                break
            _deport_one(network, gid, lv_nominal_v, x_pu, apc, gsc)
            if _converges(network):
                network.save(ckpt, format="XIIDM")  # new last-good state
                kept += 1
            else:
                network = pn.load(ckpt)  # roll back this one deportation
                skipped += 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    return network, {"deported": kept, "eligible": len(eligible), "skipped": skipped}


def _safe_ext(network, name):
    try:
        df = network.get_extensions(name)
        return df if not df.empty else None
    except Exception:  # noqa: BLE001
        return None
