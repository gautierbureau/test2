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
                    "completion flag, all run.")
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

    # With no explicit selection, run every completion (using the realistic
    # generation mix rather than the uniform energy source).
    selected = (args.reactive_limits or args.ratio_tap_changers
                or args.generation_mix or args.active_power_control or args.rated_s)
    do_reactive = args.reactive_limits or not selected
    do_taps = args.ratio_tap_changers or not selected
    do_mix = args.generation_mix or not selected
    do_apc = args.active_power_control or not selected
    do_rated_s = args.rated_s or not selected

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

    if args.output:
        network.save(args.output, format="XIIDM")
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
