"""
Fill in network data that a case is missing, from a load flow, with pypowsybl.

Two independent completions, both driven by an AC load flow so the base case is
preserved and the additions are physically grounded:

``add_reactive_limits``
    Give generators a finite MIN_MAX reactive band when they have none or carry a
    placeholder "infinite" one (the ``|Q| >= 1e4`` values MATPOWER/PEGASE use for
    "unlimited"). The band is sized from the machine rating: ``Q = sqrt(ratedS^2
    - P^2)`` when a rated apparent power is known, otherwise from a power factor
    applied to the active power. Generators that already carry a real finite band
    (or a reactive capability curve) are left untouched.

``add_ratio_tap_changers``
    Give two-winding transformers a voltage-regulating ratio tap changer when
    they have none: symmetric steps around ``rho = 1`` with the tap at neutral,
    regulating the side-2 voltage to its base-case value. At the neutral tap the
    transformer is electrically identical to before, so the base case is
    unchanged; the regulator only acts once tap control is switched on, and its
    setpoint already equals the current voltage.

Phase tap changers are deliberately not synthesized: a phase shifter is a
specific physical device, and cases that use them already carry them where they
belong.
"""

from __future__ import annotations

import argparse
import math
import sys
from typing import Optional

import pandas as pd
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

DEFAULT_POWER_FACTOR = 0.95
DEFAULT_PLACEHOLDER_THRESHOLD = 1e4  # |Q| at/above this (MVar) is a placeholder
DEFAULT_RTC_STEPS_PER_SIDE = 8
DEFAULT_RTC_STEP_INCREMENT = 0.0125  # 1.25 % per step -> +/-10 % over 8 steps
DEFAULT_DROOP = 4.0  # percent
DEFAULT_GENERATOR_POWER_FACTOR = 0.85  # rated_s = maxP / power factor
DEFAULT_TRANSFORMER_LOADING = 0.6  # base-case apparent flow as a share of rated_s

# Representative European installed-capacity split (shares need not sum to 1).
DEFAULT_GENERATION_MIX = {
    "NUCLEAR": 0.15, "THERMAL": 0.35, "HYDRO": 0.20, "WIND": 0.20, "SOLAR": 0.10,
}
# Order the mix is laid down in, from the largest units to the smallest.
_MIX_ORDER = ["NUCLEAR", "THERMAL", "HYDRO", "WIND", "SOLAR", "OTHER"]
_VALID_ENERGY_SOURCES = {"HYDRO", "NUCLEAR", "WIND", "THERMAL", "SOLAR", "OTHER"}

DEFAULT_MEASUREMENT_STD_DEV = 0.01  # standard deviation as a fraction of |value|
DEFAULT_STD_DEV_FLOOR = 0.1         # absolute floor on the standard deviation
DEFAULT_OBSERVABILITY_STD_DEV = 1.0


# ---------------------------------------------------------------------------
# Reactive limits
# ---------------------------------------------------------------------------

def add_reactive_limits(
    network: pn.Network,
    power_factor: float = DEFAULT_POWER_FACTOR,
    placeholder_threshold: float = DEFAULT_PLACEHOLDER_THRESHOLD,
    only_missing: bool = True,
    run_loadflow: bool = True,
    lf_parameters: Optional[lf.Parameters] = None,
) -> dict:
    """Give generators a finite reactive band where they lack a real one.

    The network is modified in place. Returns a summary dict.

    Parameters
    ----------
    power_factor
        Fallback sizing when no rated apparent power is known:
        ``Q = |P| * tan(acos(power_factor))``.
    placeholder_threshold
        A MIN_MAX band whose ``|min_q|`` or ``|max_q|`` reaches this (in MVar) is
        treated as a placeholder "infinite" limit and replaced.
    only_missing
        When set (default), generators already carrying a real finite band or a
        reactive capability curve are left untouched.
    run_loadflow
        Run an AC load flow first (used only to reach ``target_p`` fallbacks and
        keep behaviour uniform with the other completions).
    """
    if not 0 < power_factor <= 1:
        raise ValueError("power_factor must be in (0, 1]")
    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    gens = network.get_generators(all_attributes=True)
    stats = {"generators": len(gens), "filled": 0,
             "skipped_existing": 0, "skipped_no_size": 0}
    if gens.empty:
        return stats

    ids, min_qs, max_qs = [], [], []
    for gid, g in gens.iterrows():
        if only_missing and not _needs_reactive_limits(g, placeholder_threshold):
            stats["skipped_existing"] += 1
            continue
        q = _sized_reactive(g, power_factor, placeholder_threshold)
        if q is None:
            stats["skipped_no_size"] += 1
            continue
        ids.append(gid)
        min_qs.append(-q)
        max_qs.append(q)
    if ids:
        network.create_minmax_reactive_limits(id=ids, min_q=min_qs, max_q=max_qs)
        stats["filled"] = len(ids)
    return stats


def _needs_reactive_limits(g, threshold: float) -> bool:
    kind = g["reactive_limits_kind"]
    if kind == "CURVE":
        return False
    if kind != "MIN_MAX":
        return True  # no usable band
    min_q, max_q = g["min_q"], g["max_q"]
    if not (math.isfinite(min_q) and math.isfinite(max_q)):
        return True
    return abs(min_q) >= threshold or abs(max_q) >= threshold


def _sized_reactive(g, power_factor: float, threshold: float) -> Optional[float]:
    """Symmetric reactive half-band for a generator, or None if unsizable."""
    p = g["max_p"]
    if not (math.isfinite(p) and abs(p) < threshold):
        p = g["target_p"] if math.isfinite(g["target_p"]) else 0.0
    s = g["rated_s"]
    if math.isfinite(s) and s > 0:
        q = math.sqrt(max(s * s - p * p, 0.0))
        if q > 0:
            return q
    if math.isfinite(p) and p != 0:
        q = abs(p) * math.tan(math.acos(power_factor))
        if q > 0:  # unity power factor gives no reactive band -> leave unsized
            return q
    return None


# ---------------------------------------------------------------------------
# Reactive capability curves
# ---------------------------------------------------------------------------

DEFAULT_CURVE_POINTS = 3
DEFAULT_CURVE_MIN_Q_FRACTION = 0.2  # floor on the half-band as a share of rated_s


def add_reactive_capability_curves(
    network: pn.Network,
    points: int = DEFAULT_CURVE_POINTS,
    only_missing: bool = True,
) -> dict:
    """Give generators a reactive **capability curve** instead of a flat band.

    Real machines have a P-dependent reactive range (the classic "D-curve"): the
    reactive band is widest at low active power and narrows as ``P`` approaches
    the machine rating. This replaces a generator's MIN_MAX band with a
    piecewise curve sampled at ``points`` active-power values from 0 to ``maxP``,
    with a half-band of ``sqrt(ratedS^2 - P^2)`` (the armature-current circle,
    floored at ``DEFAULT_CURVE_MIN_Q_FRACTION * ratedS``).

    The band never drops below the generator's existing MIN_MAX limits, so the
    base case stays feasible. Needs ``rated_s`` (run :func:`add_rated_s` first);
    generators without a usable rating or ``maxP`` are left as they are. With
    ``only_missing`` generators that already use a CURVE are skipped.
    """
    if points < 2:
        raise ValueError("points must be >= 2")
    gens = network.get_generators(all_attributes=True)
    if only_missing:
        gens = gens[gens["reactive_limits_kind"] != "CURVE"]

    ids, ps, min_qs, max_qs = [], [], [], []
    set_count = 0
    for gid, g in gens.iterrows():
        s = g["rated_s"]
        pmax = g["max_p"]
        if not (math.isfinite(s) and s > 0 and math.isfinite(pmax) and pmax > 0):
            continue
        emax = g["max_q"] if math.isfinite(g["max_q"]) else 0.0
        emin = g["min_q"] if math.isfinite(g["min_q"]) else 0.0
        floor = DEFAULT_CURVE_MIN_Q_FRACTION * s
        for k in range(points):
            p = pmax * k / (points - 1)
            arm = math.sqrt(max(s * s - p * p, floor * floor))
            ids.append(gid)
            ps.append(p)
            max_qs.append(max(arm, emax))   # never narrower than the existing band
            min_qs.append(min(-arm, emin))
        set_count += 1
    if ids:
        network.create_curve_reactive_limits(id=ids, p=ps, min_q=min_qs, max_q=max_qs)
    return {"generators": len(gens), "set": set_count}


# ---------------------------------------------------------------------------
# Ratio tap changers
# ---------------------------------------------------------------------------

def add_ratio_tap_changers(
    network: pn.Network,
    steps_per_side: int = DEFAULT_RTC_STEPS_PER_SIDE,
    step_increment: float = DEFAULT_RTC_STEP_INCREMENT,
    target_deadband: float = 0.0,
    regulating: bool = True,
    only_missing: bool = True,
    run_loadflow: bool = True,
    lf_parameters: Optional[lf.Parameters] = None,
) -> dict:
    """Give two-winding transformers a voltage-regulating ratio tap changer.

    The network is modified in place. Returns a summary dict.

    Each new tap changer has ``2 * steps_per_side + 1`` steps spaced
    ``step_increment`` apart around ``rho = 1`` (impedance unchanged), with the
    tap at neutral. When ``regulating`` it holds the side-2 bus voltage at its
    base-case value, so switching tap control on barely moves the tap.

    Parameters
    ----------
    only_missing
        Only transformers that carry no tap changer at all (neither ratio nor
        phase) get one; existing tap changers are never touched.
    """
    if steps_per_side < 1:
        raise ValueError("steps_per_side must be >= 1")
    if not step_increment > 0:
        raise ValueError("step_increment must be positive")
    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    txs = network.get_2_windings_transformers(all_attributes=True)
    stats = {"transformers": len(txs), "added": 0,
             "skipped_existing": 0, "skipped_no_voltage": 0}
    if txs.empty:
        return stats

    have_taps = set(network.get_ratio_tap_changers().index) \
        | set(network.get_phase_tap_changers().index)
    buses = network.get_buses()

    neutral = steps_per_side
    rtc_rows, steps_rows = [], []
    for tid, tx in txs.iterrows():
        if only_missing and tid in have_taps:
            stats["skipped_existing"] += 1
            continue
        target_v = _side2_voltage(tx, buses)
        if target_v is None:
            stats["skipped_no_voltage"] += 1
            continue
        rtc_rows.append({
            "id": tid, "tap": neutral, "low_tap": 0, "oltc": True,
            "target_v": target_v, "target_deadband": target_deadband,
            "regulating": regulating, "regulated_side": "TWO",
        })
        for k in range(2 * steps_per_side + 1):
            rho = 1.0 + (k - neutral) * step_increment
            steps_rows.append({"id": tid, "rho": rho,
                               "r": 0.0, "x": 0.0, "g": 0.0, "b": 0.0})
    if rtc_rows:
        network.create_ratio_tap_changers(
            pd.DataFrame(rtc_rows).set_index("id"),
            pd.DataFrame(steps_rows).set_index("id"))
        stats["added"] = len(rtc_rows)
    return stats


def _side2_voltage(tx, buses) -> Optional[float]:
    """Base-case voltage (kV) at the transformer's side-2 bus, or None."""
    bus_id = tx["bus2_id"]
    if not isinstance(bus_id, str) or bus_id not in buses.index:
        return None
    v = buses.at[bus_id, "v_mag"]
    if not (math.isfinite(v) and v > 0):
        return None
    return float(v)


# ---------------------------------------------------------------------------
# Phase control (phase-shifting transformers)
# ---------------------------------------------------------------------------

DEFAULT_PHASE_CURRENT_MARGIN = 1.5  # limiter threshold as a multiple of base flow


def add_phase_control(network: pn.Network, count: Optional[int] = None,
                      current_margin: float = DEFAULT_PHASE_CURRENT_MARGIN) -> dict:
    """Turn idle phase-shifting transformers into current limiters.

    Exercises OpenLoadFlow's phase-control outer loop (run with
    ``phaseShifterRegulationOn``). Existing phase tap changers are enabled in
    ``CURRENT_LIMITER`` mode with a threshold set above the current they already
    carry (``current_margin`` x the base-case i1), which is how real phase
    shifters behave - passive until an overload - so the outer loop is present
    but does not trip and the flow still converges. Active-power-control mode was
    tried but destabilised the flow when many shifters act at once. Run after a
    load flow so the base-case currents are known.
    """
    ptc = network.get_phase_tap_changers(all_attributes=True)
    ptc = ptc[~ptc["regulating"]]
    if ptc.empty:
        return {"phase_shifters": 0}
    tx = network.get_2_windings_transformers(all_attributes=True)
    # A transformer allows only one regulating control, so skip any whose ratio
    # tap changer already regulates.
    rtc = network.get_ratio_tap_changers(all_attributes=True)
    rtc_regulating = set(rtc.index[rtc["regulating"]]) if not rtc.empty else set()

    ids, values = [], []
    for tid in ptc.index:
        eid = tid[0] if isinstance(tid, tuple) else tid
        if eid not in tx.index or eid in rtc_regulating:
            continue
        i1 = tx.at[eid, "i1"]
        if not math.isfinite(i1):
            continue
        ids.append(eid)
        values.append(max(float(i1) * current_margin, 100.0))
        if count is not None and len(ids) >= count:
            break
    if not ids:
        return {"phase_shifters": 0}
    # Set the threshold before enabling regulation: the model rejects turning
    # regulation on while the threshold is still unset.
    network.update_phase_tap_changers(
        id=ids, regulation_mode=["CURRENT_LIMITER"] * len(ids),
        regulation_value=values, target_deadband=[10.0] * len(ids),
        regulated_side=["ONE"] * len(ids))
    network.update_phase_tap_changers(id=ids, regulating=[True] * len(ids))
    return {"phase_shifters": len(ids)}


# ---------------------------------------------------------------------------
# Transformer voltage control
# ---------------------------------------------------------------------------

DEFAULT_TVC_COUNT = 300      # regulating ratio tap changers to keep active
DEFAULT_TVC_DEADBAND = 2.0   # kV deadband, wide enough to avoid tap hunting


def _tvc_converges(network: pn.Network) -> bool:
    params = lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                           voltage_init_mode=lf.VoltageInitMode.DC_VALUES,
                           transformer_voltage_control_on=True)
    return lf.run_ac(network, params)[0].status.name == "CONVERGED"


def add_transformer_voltage_control(network: pn.Network,
                                    count: int = DEFAULT_TVC_COUNT,
                                    deadband: float = DEFAULT_TVC_DEADBAND) -> dict:
    """Keep a converging subset of ratio tap changers regulating voltage.

    ``add_ratio_tap_changers`` makes every added tap changer voltage-regulating;
    with the transformer-voltage-control outer loop on, thousands of them acting
    at once do not converge. This trims the regulating set to an evenly spread
    subset (widening its deadband) and verifies the outer loop converges,
    halving the count until it does. The disabled tap changers keep their
    structure (they are transparent with the outer loop off). Run on the final
    (node-breaker) network, after a load flow.
    """
    rtc = network.get_ratio_tap_changers(all_attributes=True)
    regulating = list(rtc.index[rtc["regulating"]])
    if not regulating:
        return {"regulating": 0, "disabled": 0}
    network.update_ratio_tap_changers(
        id=regulating, regulating=[False] * len(regulating))

    n = min(count, len(regulating))
    while n >= 1:
        subset = [regulating[(i * len(regulating)) // n] for i in range(n)]
        network.update_ratio_tap_changers(
            id=subset, regulating=[True] * n, target_deadband=[deadband] * n)
        if _tvc_converges(network):
            return {"regulating": n, "disabled": len(regulating) - n}
        network.update_ratio_tap_changers(id=subset, regulating=[False] * n)
        n //= 2
    return {"regulating": 0, "disabled": len(regulating)}


# ---------------------------------------------------------------------------
# Shared (coordinated) generator voltage control
# ---------------------------------------------------------------------------

DEFAULT_SHARED_VC_GROUPS = 50


def add_shared_voltage_control(network: pn.Network,
                               count: int = DEFAULT_SHARED_VC_GROUPS) -> dict:
    """Make several generators in a voltage level co-regulate one common bus.

    Exercises OpenLoadFlow's shared (coordinated) voltage control, where more
    than one generator controls the same bus and the reactive output is split
    between them. In a multi-unit voltage level every generator is pointed at the
    first generator's bus (a common busbar), all with that bus's already-solved
    voltage as target, so the control is satisfied and the flow still converges.
    Only ``count`` groups are set up (one activated control is enough; forcing
    all of them is unnecessary). Run on the node-breaker network after a load flow.
    """
    buses = network.get_buses()
    bbs = network.get_busbar_sections(all_attributes=True)
    gens = network.get_generators(all_attributes=True)
    gens = gens[gens["voltage_regulator_on"]]
    by_vl = {}
    for gid in gens.index:
        by_vl.setdefault(gens.at[gid, "voltage_level_id"], []).append(gid)
    groups = sorted((grp for grp in by_vl.values() if len(grp) >= 2), key=lambda x: x[0])

    applied, secondaries = 0, 0
    for grp in groups:
        if applied >= count:
            break
        pilot = grp[0]
        pbus = gens.at[pilot, "bus_id"]
        cand = bbs[bbs["bus_id"] == pbus]
        if pbus not in buses.index or cand.empty:
            continue
        v = buses.at[pbus, "v_mag"]
        if not (math.isfinite(v) and v > 0):
            continue
        pbbs = cand.index[0]
        secondary = grp[1:]
        network.update_generators(
            id=secondary, regulated_element_id=[pbbs] * len(secondary),
            target_v=[float(v)] * len(secondary))
        network.update_generators(id=[pilot], target_v=[float(v)])
        applied += 1
        secondaries += len(secondary)
    return {"groups": applied, "generators": secondaries}


# ---------------------------------------------------------------------------
# Static var compensator voltage control
# ---------------------------------------------------------------------------

DEFAULT_SVC_SLOPE = 0.01  # kV per MVar (voltage droop of the SVC characteristic)


def add_svc_voltage_control(network: pn.Network, slope: float = DEFAULT_SVC_SLOPE) -> dict:
    """Put idle static var compensators into voltage regulation with a slope.

    Exercises OpenLoadFlow's SVC voltage control (``svcVoltageMonitoring``, on by
    default) and the voltage-droop characteristic (``voltagePerReactivePowerControl``
    extension). Non-regulating SVCs are switched to ``VOLTAGE`` mode targeting the
    voltage their bus already has in the solved base case, so the control is
    satisfied from the start and the flow still converges. Run after a load flow.
    """
    svc = network.get_static_var_compensators(all_attributes=True)
    svc = svc[~svc["regulating"]]
    if svc.empty:
        return {"svcs": 0}
    buses = network.get_buses()
    ids, target_v = [], []
    for sid in svc.index:
        b = svc.at[sid, "bus_id"]
        if b in buses.index and math.isfinite(buses.at[b, "v_mag"]) and buses.at[b, "v_mag"] > 0:
            ids.append(sid)
            target_v.append(float(buses.at[b, "v_mag"]))
    if not ids:
        return {"svcs": 0}
    network.update_static_var_compensators(
        id=ids, regulation_mode=["VOLTAGE"] * len(ids),
        target_v=target_v, regulating=[True] * len(ids))
    network.create_extensions("voltagePerReactivePowerControl", id=ids,
                              slope=[slope] * len(ids))
    return {"svcs": len(ids)}


# ---------------------------------------------------------------------------
# Generation mix (energy sources)
# ---------------------------------------------------------------------------

def set_generation_mix(
    network: pn.Network,
    mix: Optional[dict] = None,
    only_undefined: bool = True,
) -> dict:
    """Assign a realistic energy-source mix across the generation fleet.

    Fuel type cannot be inferred from a load flow, so this lays down a
    representative installed-capacity distribution instead of a single default:
    generators are ranked by active-power capability and the largest units are
    given the base-load sources (nuclear, thermal), the smallest the
    intermittent ones (wind, solar), so the shares in ``mix`` are met by
    capacity. Deterministic (no randomness).

    ``mix`` maps energy source -> share (need not sum to 1; default is a
    representative European split). With ``only_undefined`` (the default) only
    generators whose source is ``OTHER`` are assigned.
    """
    mix = dict(mix if mix is not None else DEFAULT_GENERATION_MIX)
    if not mix:
        raise ValueError("mix must not be empty")
    for source, weight in mix.items():
        if source not in _VALID_ENERGY_SOURCES:
            raise ValueError(f"unknown energy source in mix: {source}")
        if not weight > 0:
            raise ValueError(f"mix weight for {source} must be positive")

    gens = network.get_generators(all_attributes=True)
    stats = {"generators": len(gens), "assigned": 0,
             "skipped_defined": 0, "by_source": {}}
    if gens.empty:
        return stats
    if only_undefined:
        candidates = [g for g in gens.index if gens.at[g, "energy_source"] == "OTHER"]
        stats["skipped_defined"] = len(gens) - len(candidates)
    else:
        candidates = list(gens.index)
    if not candidates:
        return stats

    sources = [s for s in _MIX_ORDER if s in mix]
    sources += [s for s in mix if s not in sources]
    total_weight = sum(mix[s] for s in sources)

    caps = {g: _gen_capacity(gens.loc[g]) for g in candidates}
    ordered = sorted(candidates, key=lambda g: (-caps[g], g))
    total_cap = sum(caps.values())

    # Cumulative capacity boundary each source must reach, largest units first.
    boundaries, running = [], 0.0
    for source in sources:
        running += mix[source] / total_weight
        boundaries.append((source, running * (total_cap if total_cap > 0 else len(ordered))))

    assigned = {}
    cum, cursor = 0.0, 0
    for g in ordered:
        cum += caps[g] if total_cap > 0 else 1.0
        while cursor < len(boundaries) - 1 and cum > boundaries[cursor][1]:
            cursor += 1
        assigned[g] = boundaries[cursor][0]

    for source in sources:
        ids = [g for g in ordered if assigned[g] == source]
        if ids:
            network.update_generators(id=ids, energy_source=[source] * len(ids))
            stats["by_source"][source] = len(ids)
            stats["assigned"] += len(ids)
    return stats


def _gen_capacity(g) -> float:
    """Generator active-power capability (MW), falling back off a placeholder maxP."""
    p = g["max_p"]
    if not (math.isfinite(p) and 0 < abs(p) < DEFAULT_PLACEHOLDER_THRESHOLD):
        p = g["target_p"]
    return abs(p) if (math.isfinite(p) and p > 0) else 0.0


# ---------------------------------------------------------------------------
# Synthetic measurements and observability (state-estimation input)
# ---------------------------------------------------------------------------

# Injection measurement types: (measurement_type, value_column).
_INJECTION_MEASUREMENTS = [("ACTIVE_POWER", "p"), ("REACTIVE_POWER", "q")]
# Branch per-side measurement types: side -> [(measurement_type, value_column)].
_BRANCH_MEASUREMENTS = {
    "ONE": [("ACTIVE_POWER", "p1"), ("REACTIVE_POWER", "q1"), ("CURRENT", "i1")],
    "TWO": [("ACTIVE_POWER", "p2"), ("REACTIVE_POWER", "q2"), ("CURRENT", "i2")],
}


def add_measurements(
    network: pn.Network,
    relative_std_dev: float = DEFAULT_MEASUREMENT_STD_DEV,
    std_dev_floor: float = DEFAULT_STD_DEV_FLOOR,
    include_injections: bool = True,
    include_branches: bool = True,
    only_missing: bool = True,
    run_loadflow: bool = True,
    lf_parameters: Optional[lf.Parameters] = None,
) -> dict:
    """Attach synthetic analog measurements taken from a load flow.

    Turns the case into a state-estimation test bed: every generator and load
    gets active/reactive-power measurements, every line and transformer side
    active/reactive-power and current measurements, all valued from the base-case
    load flow with a standard deviation of ``max(|value| * relative_std_dev,
    std_dev_floor)``. Measured values are the exact load-flow results (no random
    perturbation, so the result is reproducible); the standard deviation carries
    the intended noise level.
    """
    if not relative_std_dev >= 0:
        raise ValueError("relative_std_dev must be >= 0")
    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    existing = set()
    if only_missing:
        try:
            existing = set(network.get_extensions("measurements")
                           .index.get_level_values("element_id"))
        except Exception:  # noqa: BLE001 - extension table may be absent
            existing = set()

    # Injection measurements carry no side, branch measurements do; IIDM rejects
    # an empty side, so the two go in separate create calls with distinct columns.
    injection_rows, branch_rows = [], []
    stats = {"measurements": 0, "elements": 0, "skipped_existing": 0}

    def make(element_id, mtype, value, side=None):
        if not math.isfinite(value):
            return None
        std = max(abs(value) * relative_std_dev, std_dev_floor)
        suffix = f"{mtype}_{side}" if side else mtype
        # pypowsybl (>=1.5, seen through 1.15) transposes the measurements
        # extension's ``value`` and ``standard_deviation`` on create, so pass
        # them swapped to land correctly in IIDM. test_measurements_from_load_flow
        # asserts the round-trip and will flag it if the library ever fixes this.
        row = {"element_id": element_id, "id": f"{element_id}_{suffix}",
               "type": mtype, "value": std,
               "standard_deviation": float(value), "valid": True}
        if side is not None:
            row["side"] = side
        return row

    if include_injections:
        for getter in ("get_generators", "get_loads"):
            for eid, elem in getattr(network, getter)().iterrows():
                if eid in existing:
                    stats["skipped_existing"] += 1
                    continue
                made = [make(eid, m, elem[col]) for m, col in _INJECTION_MEASUREMENTS]
                made = [r for r in made if r]
                if made:
                    injection_rows.extend(made)
                    stats["elements"] += 1
    if include_branches:
        for getter in ("get_lines", "get_2_windings_transformers"):
            for eid, elem in getattr(network, getter)().iterrows():
                if eid in existing:
                    stats["skipped_existing"] += 1
                    continue
                made = [make(eid, m, elem[col], side)
                        for side, specs in _BRANCH_MEASUREMENTS.items()
                        for m, col in specs]
                made = [r for r in made if r]
                if made:
                    branch_rows.extend(made)
                    stats["elements"] += 1

    for rows in (injection_rows, branch_rows):
        if rows:
            network.create_extensions("measurements",
                                      pd.DataFrame(rows).set_index("element_id"))
            stats["measurements"] += len(rows)
    return stats


def add_observability(
    network: pn.Network,
    std_dev: float = DEFAULT_OBSERVABILITY_STD_DEV,
    include_injections: bool = True,
    include_branches: bool = True,
    only_missing: bool = True,
) -> dict:
    """Mark injections and branches observable, with a per-quantity std deviation.

    Sets the ``injectionObservability`` extension on generators and loads and
    the ``branchObservability`` extension on lines and transformers, flagging
    them observable (as they would be with the measurements above) and recording
    a standard deviation per measured quantity.
    """
    stats = {"injections": 0, "branches": 0}

    if include_injections:
        skip = _elements_with_extension(network, "injectionObservability") if only_missing else set()
        ids = [e for getter in ("get_generators", "get_loads")
               for e in getattr(network, getter)().index if e not in skip]
        if ids:
            network.create_extensions(
                "injectionObservability", id=ids, observable=[True] * len(ids),
                p_standard_deviation=[std_dev] * len(ids), p_redundant=[False] * len(ids),
                q_standard_deviation=[std_dev] * len(ids), q_redundant=[False] * len(ids),
                v_standard_deviation=[std_dev] * len(ids), v_redundant=[False] * len(ids))
            stats["injections"] = len(ids)

    if include_branches:
        skip = _elements_with_extension(network, "branchObservability") if only_missing else set()
        ids = [e for getter in ("get_lines", "get_2_windings_transformers")
               for e in getattr(network, getter)().index if e not in skip]
        if ids:
            n = len(ids)
            network.create_extensions(
                "branchObservability", id=ids, observable=[True] * n,
                p1_standard_deviation=[std_dev] * n, p1_redundant=[False] * n,
                p2_standard_deviation=[std_dev] * n, p2_redundant=[False] * n,
                q1_standard_deviation=[std_dev] * n, q1_redundant=[False] * n,
                q2_standard_deviation=[std_dev] * n, q2_redundant=[False] * n)
            stats["branches"] = n
    return stats


def _elements_with_extension(network: pn.Network, name: str) -> set:
    try:
        return set(network.get_extensions(name).index)
    except Exception:  # noqa: BLE001 - extension table may be absent
        return set()


# ---------------------------------------------------------------------------
# Load detail (fixed vs variable P/Q split)
# ---------------------------------------------------------------------------

DEFAULT_LOAD_FIXED_FRACTION = 0.4  # share of a load that is constant (rest varies)


def add_load_detail(
    network: pn.Network,
    fixed_fraction: float = DEFAULT_LOAD_FIXED_FRACTION,
    only_missing: bool = True,
) -> dict:
    """Split each load's P/Q into a fixed and a variable part (``detail`` extension).

    Real load records distinguish a constant component from a
    voltage/time-varying one; a load flow cannot infer the split, so this applies
    a representative constant ``fixed_fraction`` (default 40 % fixed, 60 %
    variable) to every load's ``p0``/``q0``.
    """
    if not 0.0 <= fixed_fraction <= 1.0:
        raise ValueError("fixed_fraction must be in [0, 1]")
    loads = network.get_loads(all_attributes=True)
    skip = _elements_with_extension(network, "detail") if only_missing else set()
    loads = loads[~loads.index.isin(skip)]
    if loads.empty:
        return {"loads": 0, "set": 0}
    var = 1.0 - fixed_fraction
    df = pd.DataFrame({
        "id": list(loads.index),
        "fixed_p0": [fixed_fraction * p for p in loads["p0"]],
        "variable_p0": [var * p for p in loads["p0"]],
        "fixed_q0": [fixed_fraction * q for q in loads["q0"]],
        "variable_q0": [var * q for q in loads["q0"]],
    }).set_index("id")
    network.create_extensions("detail", df)
    return {"loads": len(loads), "set": len(df)}


# ---------------------------------------------------------------------------
# Discrete measurements (tap-changer positions)
# ---------------------------------------------------------------------------

def add_discrete_measurements(network: pn.Network, only_missing: bool = True) -> dict:
    """Attach a discrete tap-position measurement to every tap changer.

    Sets the ``discreteMeasurements`` extension with the current tap position of
    each ratio and phase tap changer (the discrete counterpart of the analog
    ``measurements`` extension). Keyed by the transformer id.
    """
    skip = _elements_with_extension(network, "discreteMeasurements") if only_missing else set()
    rows = []
    for getter, kind in (("get_ratio_tap_changers", "RATIO_TAP_CHANGER"),
                         ("get_phase_tap_changers", "PHASE_TAP_CHANGER")):
        tcs = getattr(network, getter)()
        for idx, row in tcs.iterrows():
            eid = idx[0] if isinstance(idx, tuple) else idx
            side = idx[1] if isinstance(idx, tuple) else None
            if eid in skip:
                continue
            suffix = f"{kind}_{side}" if side else kind
            rows.append({
                "element_id": eid, "id": f"{eid}_{suffix}_POS",
                "type": "TAP_POSITION", "tap_changer": kind,
                "value_type": "INT", "value": str(int(row["tap"])), "valid": True,
            })
    if not rows:
        return {"measurements": 0}
    network.create_extensions("discreteMeasurements",
                              pd.DataFrame(rows).set_index("element_id"))
    return {"measurements": len(rows)}


# ---------------------------------------------------------------------------
# Element properties (free-form string key/value tags)
# ---------------------------------------------------------------------------

DEFAULT_REGION_COUNT = 10
DEFAULT_COUNTRY = "XX"


def _voltage_class(nominal_v: float) -> str:
    if nominal_v >= 300.0:
        return "EHV"
    if nominal_v >= 100.0:
        return "HV"
    if nominal_v >= 1.0:
        return "MV"
    return "LV"


def add_properties(
    network: pn.Network,
    region_count: int = DEFAULT_REGION_COUNT,
    country: str = DEFAULT_COUNTRY,
    only_missing: bool = True,
) -> dict:
    """Tag substations and voltage levels with representative string properties.

    IIDM properties are free-form string key/value pairs; real (often
    CGMES-sourced) networks carry geographic/operational tags. A load flow cannot
    infer them, so this lays down a deterministic set:

    - each **substation** gets ``region`` (partitioned into ``region_count`` zones
      by sorted id) and ``country_code``;
    - each **voltage level** gets ``voltage_class`` (EHV/HV/MV/LV from nominal kV).

    Keys deliberately avoid native IIDM attribute names (``country``, ``name``,
    ``TSO``) so they don't collide when the network is rebuilt. With
    ``only_missing`` elements that already carry any property are left alone.
    """
    if region_count < 1:
        raise ValueError("region_count must be >= 1")
    skip = set()
    if only_missing:
        props = network.get_elements_properties()
        if not props.empty:
            skip = set(props.index)

    stats = {"substations": 0, "voltage_levels": 0}

    subs = [s for s in sorted(network.get_substations().index) if s not in skip]
    if subs:
        network.add_elements_properties(
            id=subs,
            region=[f"REGION_{i % region_count:02d}" for i in range(len(subs))],
            country_code=[country] * len(subs))
        stats["substations"] = len(subs)

    vls = network.get_voltage_levels()
    vids = [v for v in sorted(vls.index) if v not in skip]
    if vids:
        network.add_elements_properties(
            id=vids,
            voltage_class=[_voltage_class(float(vls.at[v, "nominal_v"])) for v in vids])
        stats["voltage_levels"] = len(vids)
    return stats


# ---------------------------------------------------------------------------
# Short-circuit data
# ---------------------------------------------------------------------------

DEFAULT_TRANS_X_PU = 0.25       # transient reactance X'd (pu on machine base)
DEFAULT_SUBTRANS_X_PU = 0.18    # subtransient reactance X''d (pu)
# Representative max short-circuit current (A) by voltage class; min is a share.
_IP_MAX_BY_CLASS = {"EHV": 40000.0, "HV": 31500.0, "MV": 25000.0, "LV": 10000.0}
_IP_MIN_FRACTION = 0.3


def add_short_circuit(network: pn.Network, only_missing: bool = True) -> dict:
    """Add short-circuit data used by fault calculations.

    - ``generatorShortCircuit`` on every generator: transient / subtransient
      reactances in ohms, sized ``x_pu * Vn^2 / ratedS`` from the machine's
      nominal voltage and rated apparent power (a step-up transformer reactance
      of 0 is recorded).
    - ``identifiableShortCircuit`` on every voltage level: representative min/max
      short-circuit current (A) by voltage class.

    A load flow cannot infer these, so representative per-unit reactances and
    fault levels are used. Run :func:`add_rated_s` first for the best sizing.
    """
    nominal = network.get_voltage_levels()["nominal_v"]
    stats = {"generators": 0, "voltage_levels": 0}

    gens = network.get_generators(all_attributes=True)
    skip = _elements_with_extension(network, "generatorShortCircuit") if only_missing else set()
    gens = gens[~gens.index.isin(skip)]
    if not gens.empty:
        trans, subtrans = [], []
        for _gid, g in gens.iterrows():
            vn = float(nominal.get(g["voltage_level_id"], 0.0))
            s = g["rated_s"]
            if not (math.isfinite(s) and s > 0):
                cap = _gen_capacity(g)
                s = cap / DEFAULT_GENERATOR_POWER_FACTOR if cap > 0 else 0.0
            base = (vn * vn / s) if (vn > 0 and s > 0) else 1.0
            trans.append(DEFAULT_TRANS_X_PU * base)
            subtrans.append(DEFAULT_SUBTRANS_X_PU * base)
        network.create_extensions(
            "generatorShortCircuit", id=list(gens.index),
            direct_trans_x=trans, direct_sub_trans_x=subtrans,
            step_up_transformer_x=[0.0] * len(gens))
        stats["generators"] = len(gens)

    skip_vl = _elements_with_extension(network, "identifiableShortCircuit") if only_missing else set()
    vids = [v for v in network.get_voltage_levels().index if v not in skip_vl]
    if vids:
        ip_max = [_IP_MAX_BY_CLASS[_voltage_class(float(nominal[v]))] for v in vids]
        network.create_extensions(
            "identifiableShortCircuit", id=vids,
            ip_min=[m * _IP_MIN_FRACTION for m in ip_max], ip_max=ip_max)
        stats["voltage_levels"] = len(vids)
    return stats


# ---------------------------------------------------------------------------
# Active power control (participation factors)
# ---------------------------------------------------------------------------

def add_active_power_control(
    network: pn.Network,
    droop: float = DEFAULT_DROOP,
    only_missing: bool = True,
) -> dict:
    """Give generators an active-power-control participation factor.

    Sets the ``activePowerControl`` extension with ``participate = True`` and a
    participation factor proportional to the generator's active-power capability
    (``max_p``, falling back to ``target_p`` then 1), so distributed slack /
    redispatch has something to act on. Generators that already carry the
    extension are left untouched when ``only_missing``.
    """
    if not droop > 0:
        raise ValueError("droop must be positive")

    gens = network.get_generators(all_attributes=True)
    stats = {"generators": len(gens), "added": 0, "skipped_existing": 0}
    if gens.empty:
        return stats

    existing = set()
    if only_missing:
        try:
            existing = set(network.get_extensions("activePowerControl").index)
        except Exception:  # noqa: BLE001 - extension table may be absent
            existing = set()

    ids, factors = [], []
    for gid, g in gens.iterrows():
        if gid in existing:
            stats["skipped_existing"] += 1
            continue
        ids.append(gid)
        factors.append(_participation_factor(g))
    if ids:
        network.create_extensions(
            "activePowerControl", id=ids, participate=[True] * len(ids),
            droop=[droop] * len(ids), participation_factor=factors)
        stats["added"] = len(ids)
    return stats


def _participation_factor(g) -> float:
    for value in (g["max_p"], g["target_p"]):
        if math.isfinite(value) and value > 0:
            return float(value)
    return 1.0


# ---------------------------------------------------------------------------
# Apparent power ratings (rated_s)
# ---------------------------------------------------------------------------

def add_rated_s(
    network: pn.Network,
    generator_power_factor: float = DEFAULT_GENERATOR_POWER_FACTOR,
    transformer_loading: float = DEFAULT_TRANSFORMER_LOADING,
    only_missing: bool = True,
    run_loadflow: bool = True,
    lf_parameters: Optional[lf.Parameters] = None,
) -> dict:
    """Give generators and two-winding transformers an apparent-power rating.

    Real models always carry ``rated_s`` (nameplate MVA); many cases - PEGASE
    included - omit it, which blocks apparent-power limits and MVA loading.

    - Generators: ``rated_s = |P| / generator_power_factor`` where ``P`` is the
      active-power capability (``max_p``, falling back to ``target_p``).
    - Transformers: ``rated_s = S_base / transformer_loading`` where ``S_base``
      is the larger of the two sides' base-case apparent flow, so the base case
      loads the transformer at ``transformer_loading`` of its rating.

    The network is modified in place. Returns a summary dict.
    """
    if not 0 < generator_power_factor <= 1:
        raise ValueError("generator_power_factor must be in (0, 1]")
    if not 0 < transformer_loading <= 1:
        raise ValueError("transformer_loading must be in (0, 1]")
    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    stats = {"generators": 0, "generators_set": 0, "generators_skipped_existing": 0,
             "generators_unsizable": 0, "transformers": 0, "transformers_set": 0,
             "transformers_skipped_existing": 0, "transformers_no_flow": 0}

    gens = network.get_generators(all_attributes=True)
    stats["generators"] = len(gens)
    gids, gvals = [], []
    for gid, g in gens.iterrows():
        if only_missing and math.isfinite(g["rated_s"]) and g["rated_s"] > 0:
            stats["generators_skipped_existing"] += 1
            continue
        capacity = _gen_capacity(g)
        if capacity <= 0:
            stats["generators_unsizable"] += 1
            continue
        gids.append(gid)
        gvals.append(capacity / generator_power_factor)
    if gids:
        network.update_generators(id=gids, rated_s=gvals)
        stats["generators_set"] = len(gids)

    txs = network.get_2_windings_transformers(all_attributes=True)
    stats["transformers"] = len(txs)
    tids, tvals = [], []
    for tid, t in txs.iterrows():
        if only_missing and math.isfinite(t["rated_s"]) and t["rated_s"] > 0:
            stats["transformers_skipped_existing"] += 1
            continue
        s_base = _apparent_flow(t)
        if s_base is None:
            stats["transformers_no_flow"] += 1
            continue
        tids.append(tid)
        tvals.append(s_base / transformer_loading)
    if tids:
        network.update_2_windings_transformers(id=tids, rated_s=tvals)
        stats["transformers_set"] = len(tids)
    return stats


def _apparent_flow(tx) -> Optional[float]:
    """Larger of the two sides' base-case apparent power (MVA), or None."""
    best = None
    for p_col, q_col in (("p1", "q1"), ("p2", "q2")):
        p, q = tx[p_col], tx[q_col]
        if math.isfinite(p) and math.isfinite(q):
            best = max(best or 0.0, math.hypot(p, q))
    return best if best and best > 0 else None


# ---------------------------------------------------------------------------
# Load flow
# ---------------------------------------------------------------------------

def _run_ac_or_raise(network: pn.Network,
                     lf_parameters: Optional[lf.Parameters]) -> None:
    if lf_parameters is not None:
        candidates = [lf_parameters]
    else:
        # Flat start first, then a DC-based start, which converges large cases
        # where a flat start does not.
        candidates = [
            lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                          voltage_init_mode=init)
            for init in (lf.VoltageInitMode.UNIFORM_VALUES, lf.VoltageInitMode.DC_VALUES)
        ]
    for params in candidates:
        result = lf.run_ac(network, params)
        if result[0].status.name == "CONVERGED":
            return
    raise RuntimeError(f"load flow did not converge: {result[0].status.name}")


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
        description="Fill in missing network data (reactive limits, ratio tap "
                    "changers, generation mix, active power control, "
                    "apparent-power ratings), sized from an AC load flow. With no "
                    "completion flag, all of those run. Synthetic measurements and "
                    "observability are opt-in (--measurements / --observability).")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("-i", "--input", help="input network file")
    src.add_argument("--builtin", choices=sorted(_BUILTINS),
                     help="use a bundled network instead of --input")
    parser.add_argument("-o", "--output", help="write the completed network here")
    parser.add_argument("--reactive-limits", action="store_true",
                        help="fill missing/placeholder generator reactive limits")
    parser.add_argument("--ratio-tap-changers", action="store_true",
                        help="add regulating ratio tap changers where missing")
    parser.add_argument("--generation-mix", action="store_true",
                        help="assign a realistic energy-source mix (default when no flag)")
    parser.add_argument("--active-power-control", action="store_true",
                        help="add participation factors for distributed slack")
    parser.add_argument("--rated-s", action="store_true",
                        help="estimate apparent-power ratings where missing")
    parser.add_argument("--measurements", action="store_true",
                        help="add synthetic measurements from the load flow "
                             "(opt-in; not part of the run-all default)")
    parser.add_argument("--observability", action="store_true",
                        help="mark injections/branches observable (opt-in)")
    parser.add_argument("--power-factor", type=float, default=DEFAULT_POWER_FACTOR,
                        help="reactive sizing fallback power factor "
                             f"(default: {DEFAULT_POWER_FACTOR})")
    parser.add_argument("--rtc-steps", type=int, default=DEFAULT_RTC_STEPS_PER_SIDE,
                        help="ratio tap changer steps per side "
                             f"(default: {DEFAULT_RTC_STEPS_PER_SIDE})")
    parser.add_argument("--rtc-step", type=float, default=DEFAULT_RTC_STEP_INCREMENT,
                        help="ratio tap changer step increment "
                             f"(default: {DEFAULT_RTC_STEP_INCREMENT})")
    parser.add_argument("--droop", type=float, default=DEFAULT_DROOP,
                        help=f"active power control droop percent (default: {DEFAULT_DROOP})")
    parser.add_argument("--generator-power-factor", type=float,
                        default=DEFAULT_GENERATOR_POWER_FACTOR,
                        help="rated_s generator power factor "
                             f"(default: {DEFAULT_GENERATOR_POWER_FACTOR})")
    parser.add_argument("--transformer-loading", type=float,
                        default=DEFAULT_TRANSFORMER_LOADING,
                        help="base-case transformer loading as a share of rated_s "
                             f"(default: {DEFAULT_TRANSFORMER_LOADING})")
    args = parser.parse_args(argv)

    # Any explicit flag disables the run-all default. Measurements and
    # observability are opt-in: they are never part of the run-all default (they
    # add a lot of data for a specialized, state-estimation purpose), only run
    # when their flag is set.
    selected = (args.reactive_limits or args.ratio_tap_changers
                or args.generation_mix or args.active_power_control or args.rated_s
                or args.measurements or args.observability)
    do_reactive = args.reactive_limits or not selected
    do_taps = args.ratio_tap_changers or not selected
    do_mix = args.generation_mix or not selected
    do_apc = args.active_power_control or not selected
    do_rated_s = args.rated_s or not selected
    do_measurements = args.measurements
    do_observability = args.observability

    network = _BUILTINS[args.builtin]() if args.builtin else pn.load(args.input)
    # One load flow shared by all completions.
    _run_ac_or_raise(network, None)

    if do_reactive:
        r = add_reactive_limits(network, power_factor=args.power_factor,
                                run_loadflow=False)
        print(f"Reactive limits: filled {r['filled']} of {r['generators']} generator(s) "
              f"({r['skipped_existing']} already had a band, "
              f"{r['skipped_no_size']} unsizable).")
    if do_taps:
        t = add_ratio_tap_changers(network, steps_per_side=args.rtc_steps,
                                   step_increment=args.rtc_step, run_loadflow=False)
        print(f"Ratio tap changers: added {t['added']} of {t['transformers']} "
              f"transformer(s) ({t['skipped_existing']} already had a tap changer, "
              f"{t['skipped_no_voltage']} without a base-case voltage).")
    if do_mix:
        m = set_generation_mix(network)
        print(f"Generation mix: assigned {m['assigned']} of {m['generators']} generator(s) "
              f"({m['skipped_defined']} already defined) -> {m['by_source']}.")
    if do_apc:
        a = add_active_power_control(network, droop=args.droop)
        print(f"Active power control: added {a['added']} of {a['generators']} "
              f"generator(s) ({a['skipped_existing']} already had it).")
    if do_rated_s:
        s = add_rated_s(network, generator_power_factor=args.generator_power_factor,
                        transformer_loading=args.transformer_loading, run_loadflow=False)
        print(f"Rated S: set {s['generators_set']} of {s['generators']} generator(s) and "
              f"{s['transformers_set']} of {s['transformers']} transformer(s) "
              f"({s['transformers_no_flow']} transformer(s) had no base-case flow).")
    if do_measurements:
        me = add_measurements(network, run_loadflow=False)
        print(f"Measurements: added {me['measurements']} measurement(s) on "
              f"{me['elements']} element(s).")
    if do_observability:
        ob = add_observability(network)
        print(f"Observability: marked {ob['injections']} injection(s) and "
              f"{ob['branches']} branch(es) observable.")

    if args.output:
        network.save(args.output, format="XIIDM")
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
