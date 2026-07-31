"""
Add synthetic FACTS / storage / HVDC equipment to a network.

Real transmission networks carry a handful of batteries, static var
compensators (SVCs) and HVDC links; the PEGASE and ACTIVSg source cases have
none. This module injects a representative, sparse set of each so a network
exercises those IIDM object types. The devices are created **electrically
neutral** (zero power/reactive target, voltage regulation off), so the base-case
load flow is unchanged - the point is structural coverage, not new flows.

Hosts are chosen deterministically and evenly spread across the buses that carry
load (which are energised and have valid bus-breaker buses). Counts default to a
sparse rate taken from a real network (~27 batteries, 7 SVCs, 6 HVDC links per
~7800 buses); pass an explicit ``count`` to override.
"""

from __future__ import annotations

from typing import List, Optional, Tuple

import pypowsybl.network as pn

# Devices per bus, from a real reference network (27/7/6 over 7803 buses).
DEFAULT_BATTERY_RATE = 27 / 7803
DEFAULT_SVC_RATE = 7 / 7803
DEFAULT_HVDC_RATE = 6 / 7803
DEFAULT_HVDC_NOMINAL_V = 320.0  # kV, DC side


def _host_buses(network: pn.Network) -> List[Tuple[str, str]]:
    """Distinct (voltage_level_id, bus_breaker_bus_id) hosts, load buses first.

    Sorted by bus id for determinism; load buses are energised and expose a
    valid bus-breaker bus to hang an injection on.
    """
    loads = network.get_loads(all_attributes=True)
    loads = loads[loads["bus_breaker_bus_id"] != ""]
    hosts = (loads[["voltage_level_id", "bus_breaker_bus_id"]]
             .drop_duplicates("bus_breaker_bus_id").sort_index())
    return list(zip(hosts["voltage_level_id"], hosts["bus_breaker_bus_id"]))


def _evenly_spaced(items: list, n: int) -> list:
    """Pick ``n`` distinct, evenly spaced entries from ``items`` (n <= len)."""
    if n <= 0 or not items:
        return []
    n = min(n, len(items))
    k = len(items)
    return [items[(i * k) // n] for i in range(n)]


def _count(network: pn.Network, count: Optional[int], rate: float) -> int:
    if count is not None:
        return max(0, count)
    return max(1, round(len(network.get_buses()) * rate))


def add_batteries(network: pn.Network, count: Optional[int] = None,
                  power_mw: float = 25.0) -> dict:
    """Add sparse grid-storage batteries (idle: target P/Q = 0).

    Each battery also gets the ``voltageRegulation`` extension (regulator off,
    so neutral) to mirror how real storage records its voltage-control mode.
    """
    n = _count(network, count, DEFAULT_BATTERY_RATE)
    hosts = _evenly_spaced(_host_buses(network), n)
    if not hosts:
        return {"added": 0}
    ids = [f"SYN_BAT_{i}" for i in range(len(hosts))]
    network.create_batteries(
        id=ids,
        voltage_level_id=[h[0] for h in hosts], bus_id=[h[1] for h in hosts],
        min_p=[-power_mw] * len(hosts), max_p=[power_mw] * len(hosts),
        target_p=[0.0] * len(hosts), target_q=[0.0] * len(hosts))
    network.create_minmax_reactive_limits(
        id=ids, min_q=[-power_mw] * len(hosts), max_q=[power_mw] * len(hosts))
    network.create_extensions(
        "voltageRegulation", id=ids, voltage_regulator_on=[False] * len(ids),
        target_v=[float("nan")] * len(ids), regulated_element_id=[""] * len(ids))
    return {"added": len(ids)}


def add_static_var_compensators(network: pn.Network, count: Optional[int] = None,
                                susceptance: float = 0.01) -> dict:
    """Add sparse SVCs (regulation off, so electrically neutral in the base case)."""
    n = _count(network, count, DEFAULT_SVC_RATE)
    hosts = _evenly_spaced(_host_buses(network), n)
    if not hosts:
        return {"added": 0}
    nominal = network.get_voltage_levels()["nominal_v"]
    ids = [f"SYN_SVC_{i}" for i in range(len(hosts))]
    target_v = [float(nominal[h[0]]) for h in hosts]
    network.create_static_var_compensators(
        id=ids,
        voltage_level_id=[h[0] for h in hosts], bus_id=[h[1] for h in hosts],
        b_min=[-susceptance] * len(hosts), b_max=[susceptance] * len(hosts),
        regulation_mode=["VOLTAGE"] * len(hosts), regulating=[False] * len(hosts),
        target_v=target_v, target_q=[0.0] * len(hosts))
    # standbyAutomaton: standby off (neutral); voltage thresholds around target.
    network.create_extensions(
        "standbyAutomaton", id=ids, standby=[False] * len(ids), b0=[0.0] * len(ids),
        low_voltage_threshold=[0.90 * v for v in target_v],
        low_voltage_setpoint=[0.95 * v for v in target_v],
        high_voltage_threshold=[1.10 * v for v in target_v],
        high_voltage_setpoint=[1.05 * v for v in target_v])
    return {"added": len(ids)}


def add_hvdc_links(network: pn.Network, count: Optional[int] = None,
                   max_p: float = 200.0) -> dict:
    """Add sparse VSC-based HVDC links (idle: target P = 0).

    Each link is two VSC converter stations plus the DC line joining them. The
    two ends are placed far apart in the host ordering so the link spans the
    network rather than sitting on adjacent buses.
    """
    n = _count(network, count, DEFAULT_HVDC_RATE)
    hosts = _evenly_spaced(_host_buses(network), 2 * n)
    links = len(hosts) // 2
    if links == 0:
        return {"hvdc_lines": 0, "vsc_stations": 0}
    side1, side2 = hosts[:links], hosts[links:2 * links]

    vsc_ids, vl_ids, bus_ids = [], [], []
    for i in range(links):
        for end, host in (("1", side1[i]), ("2", side2[i])):
            vsc_ids.append(f"SYN_VSC_{i}_{end}")
            vl_ids.append(host[0])
            bus_ids.append(host[1])
    network.create_vsc_converter_stations(
        id=vsc_ids, voltage_level_id=vl_ids, bus_id=bus_ids,
        loss_factor=[1.0] * len(vsc_ids),
        voltage_regulator_on=[False] * len(vsc_ids),
        target_q=[0.0] * len(vsc_ids), target_v=[float("nan")] * len(vsc_ids))
    network.create_minmax_reactive_limits(
        id=vsc_ids, min_q=[-max_p / 2] * len(vsc_ids), max_q=[max_p / 2] * len(vsc_ids))

    hvdc_ids = [f"SYN_HVDC_{i}" for i in range(links)]
    network.create_hvdc_lines(
        id=hvdc_ids,
        converter_station1_id=[f"SYN_VSC_{i}_1" for i in range(links)],
        converter_station2_id=[f"SYN_VSC_{i}_2" for i in range(links)],
        r=[1.0] * links, nominal_v=[DEFAULT_HVDC_NOMINAL_V] * links,
        max_p=[max_p] * links, target_p=[0.0] * links,
        converters_mode=["SIDE_1_RECTIFIER_SIDE_2_INVERTER"] * links)
    # Angle-droop active-power control (disabled -> neutral) and the operator
    # active-power range, both mirroring how real HVDC links are described.
    network.create_extensions(
        "hvdcAngleDroopActivePowerControl", id=hvdc_ids,
        droop=[180.0] * links, p0=[0.0] * links, enabled=[False] * links)
    network.create_extensions(
        "hvdcOperatorActivePowerRange", id=hvdc_ids,
        opr_from_cs1_to_cs2=[max_p] * links, opr_from_cs2_to_cs1=[max_p] * links)
    return {"hvdc_lines": links, "vsc_stations": len(vsc_ids)}


def add_equipment(network: pn.Network) -> dict:
    """Add batteries, SVCs and HVDC links with default sparse counts."""
    return {
        "batteries": add_batteries(network),
        "static_var_compensators": add_static_var_compensators(network),
        "hvdc": add_hvdc_links(network),
    }
