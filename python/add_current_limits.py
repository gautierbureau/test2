"""
Create operational current-limit sets on every branch of a network from a load
flow, with pypowsybl.

Many cases carry no operational limits at all - the PEGASE snapshots are the
usual example. Without limits, security / overload analysis has nothing to check
against. This tool fills that gap: it runs an AC load flow, reads the current
that actually flows through each branch side, and sizes a *complete* current
limit set (an IIDM operational-limit group) around it - one permanent limit
(PATL) plus a configurable ladder of temporary limits (TATL), each with its own
acceptable duration ("temporisation").

Sizing is per side, because the amperage on the two ends of a transformer
differs by the voltage ratio: a current limit is only meaningful next to the
current it bounds. For a side carrying ``I`` amps the set is

    permanent          value = I * permanent_margin           duration = -1
    temporary tier k   value = permanent * tier_margin_k      duration = tier_seconds_k

so with the defaults a branch loaded to ``I`` in the base case sits at
``1 / permanent_margin`` (80 %) of its permanent limit, with temporary steps
above it. The set is stored as a named operational-limit group and, by default,
selected as the branch's active group.

Supported branches: lines, two- and three-winding transformers, and boundary
(dangling) lines. HVDC lines carry no AC current limits and are ignored.
"""

from __future__ import annotations

import argparse
import math
import sys
from collections import defaultdict
from typing import Dict, List, Optional, Sequence, Tuple

import pandas as pd
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

# (acceptable_duration_seconds, margin_over_permanent_limit)
DEFAULT_TEMPORARY_TIERS: Tuple[Tuple[int, float], ...] = (
    (1200, 1.10),  # 20 min at 110 % of the permanent limit
    (600, 1.20),   # 10 min at 120 %
    (60, 1.40),    #  1 min at 140 %
)
DEFAULT_PERMANENT_MARGIN = 1.25
DEFAULT_GROUP_NAME = "LOADFLOW_BASED"
PERMANENT_DURATION = -1
DEFAULT_LIMIT_TYPES = ("CURRENT",)
# limit type -> unit label, for messages.
_LIMIT_TYPE_UNITS = {"CURRENT": "A", "APPARENT_POWER": "MVA", "ACTIVE_POWER": "MW"}

# Per-side wiring for each branch type:
# (current_column, p_column, q_column, iidm_side, selected_group_column).
_LINE_SIDES = [("i1", "p1", "q1", "ONE", "selected_limits_group_1"),
               ("i2", "p2", "q2", "TWO", "selected_limits_group_2")]
_T3_SIDES = [("i1", "p1", "q1", "ONE", "selected_limits_group_1"),
             ("i2", "p2", "q2", "TWO", "selected_limits_group_2"),
             ("i3", "p3", "q3", "THREE", "selected_limits_group_3")]
_BOUNDARY_SIDES = [("i", "p", "q", "NONE", "selected_limits_group")]

# The single source of truth for which branch types get limits and how to reach
# them: (type_name, getter, sides, updater, include_flag). ``include_flag`` names
# the ``include_*`` toggle that gates the type (None = always included).
_BRANCH_TYPES = (
    ("line", "get_lines", _LINE_SIDES, "update_lines", None),
    ("2_windings_transformer", "get_2_windings_transformers", _LINE_SIDES,
     "update_2_windings_transformers", "transformers"),
    ("3_windings_transformer", "get_3_windings_transformers", _T3_SIDES,
     "update_3_windings_transformers", "transformers"),
    ("boundary_line", "get_boundary_lines", _BOUNDARY_SIDES,
     "update_boundary_lines", "boundary_lines"),
)


def add_current_limits(
    network: pn.Network,
    permanent_margin: float = DEFAULT_PERMANENT_MARGIN,
    temporary_tiers: Sequence[Tuple[int, float]] = DEFAULT_TEMPORARY_TIERS,
    group_name: str = DEFAULT_GROUP_NAME,
    include_transformers: bool = True,
    include_boundary_lines: bool = True,
    min_current: float = 1.0,
    run_loadflow: bool = True,
    lf_parameters: Optional[lf.Parameters] = None,
    only_missing: bool = False,
    select: bool = True,
    limit_types: Sequence[str] = DEFAULT_LIMIT_TYPES,
) -> dict:
    """Size and attach a current-limit set to every branch side from a load flow.

    The network is modified in place. Returns a summary dict.

    Parameters
    ----------
    permanent_margin
        Permanent limit = ``side current * permanent_margin`` (> 1).
    temporary_tiers
        Ladder of ``(duration_seconds, margin_over_permanent)`` temporary
        limits. Durations must be positive and distinct; margins must be > 1.
    group_name
        Name of the operational-limit group (the "set") to create.
    include_transformers
        Also size limits on two- and three-winding transformers.
    include_boundary_lines
        Also size limits on boundary (dangling) lines.
    min_current
        Sides carrying less than this (in A), or a non-finite current
        (disconnected / unsolved), are skipped - a load flow cannot suggest a
        meaningful limit there.
    run_loadflow
        Run an AC load flow first. Set ``False`` when the network is already
        solved. A non-converged flow raises ``RuntimeError``.
    lf_parameters
        Load-flow parameters; a sensible distributed-slack default is used when
        omitted (with a DC-start fallback for hard cases).
    only_missing
        Skip branches that already carry any operational limit, leaving their
        existing sets untouched.
    select
        Mark the created group as the active (selected) group on each branch.
    limit_types
        Which operational-limit types to size into the group: ``"CURRENT"``
        (side amps), ``"APPARENT_POWER"`` (``sqrt(P^2 + Q^2)`` in MVA) and/or
        ``"ACTIVE_POWER"`` (``|P|`` in MW). All share the same permanent margin
        and temporary tiers.
    """
    _validate_config(permanent_margin, temporary_tiers, limit_types)

    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    skip = _elements_with_limits(network) if only_missing else set()

    stats = {
        "group_name": group_name,
        "branches": 0,
        "sides": 0,
        "permanent_limits": 0,
        "temporary_limits": 0,
        "skipped_no_flow": 0,
        "skipped_existing": 0,
        "by_type": {},
    }
    rows: List[dict] = []
    # updater -> {selected_group_column -> [ids that got a limit on that side]}.
    selections: Dict[str, Dict[str, List[str]]] = {}

    includes = {"transformers": include_transformers,
                "boundary_lines": include_boundary_lines}

    for type_name, getter_name, sides, updater, flag in _BRANCH_TYPES:
        if flag is not None and not includes[flag]:
            continue
        df = getattr(network, getter_name)()
        if df.empty:
            continue
        type_stats = {"branches": 0, "sides": 0, "skipped_no_flow": 0,
                      "skipped_existing": 0}
        sel_by_col: Dict[str, List[str]] = defaultdict(list)
        for eid, elem in df.iterrows():
            if eid in skip:
                type_stats["skipped_existing"] += 1
                stats["skipped_existing"] += 1
                continue
            element_had_side = False
            for current_col, p_col, q_col, side, group_col in sides:
                side_used = False
                for limit_type in limit_types:
                    value = _side_value(elem, current_col, p_col, q_col, limit_type)
                    if not _is_usable(value, min_current):
                        continue
                    rows.extend(_limit_rows(eid, side, float(value),
                                            permanent_margin, temporary_tiers,
                                            group_name, limit_type))
                    stats["permanent_limits"] += 1
                    stats["temporary_limits"] += len(temporary_tiers)
                    side_used = True
                if not side_used:
                    type_stats["skipped_no_flow"] += 1
                    stats["skipped_no_flow"] += 1
                    continue
                type_stats["sides"] += 1
                stats["sides"] += 1
                element_had_side = True
                # Select the group only on the side that actually got a limit -
                # pointing a side at a group with no limit there is rejected.
                sel_by_col[group_col].append(eid)
            if element_had_side:
                type_stats["branches"] += 1
                stats["branches"] += 1
        if type_stats["branches"] or type_stats["skipped_no_flow"] \
                or type_stats["skipped_existing"]:
            stats["by_type"][type_name] = type_stats
        if sel_by_col:
            selections[updater] = dict(sel_by_col)

    if rows:
        network.create_operational_limits(pd.DataFrame(rows).set_index("element_id"))
    if select:
        for updater, sel_by_col in selections.items():
            for group_col, ids in sel_by_col.items():
                getattr(network, updater)(
                    id=ids, **{group_col: [group_name] * len(ids)})

    return stats


# ---------------------------------------------------------------------------
# Limit construction
# ---------------------------------------------------------------------------

def _limit_rows(element_id: str, side: str, base_value: float,
                permanent_margin: float,
                temporary_tiers: Sequence[Tuple[int, float]],
                group_name: str, limit_type: str) -> List[dict]:
    """One permanent + N temporary limits of ``limit_type`` for a branch side."""
    permanent = base_value * permanent_margin
    rows = [{
        "element_id": element_id, "side": side, "name": "permanent_limit",
        "type": limit_type, "value": permanent,
        "acceptable_duration": PERMANENT_DURATION, "group_name": group_name,
    }]
    for duration, margin in temporary_tiers:
        rows.append({
            "element_id": element_id, "side": side,
            "name": _tier_name(duration), "type": limit_type,
            "value": permanent * margin, "acceptable_duration": int(duration),
            "group_name": group_name,
        })
    return rows


def _side_value(elem, current_col: str, p_col: str, q_col: str,
                limit_type: str) -> float:
    """Observed flow on a side for the given limit type (A / MVA / MW)."""
    if limit_type == "CURRENT":
        return elem[current_col]
    p, q = elem[p_col], elem[q_col]
    if not (math.isfinite(p) and math.isfinite(q)):
        return float("nan")
    if limit_type == "APPARENT_POWER":
        return math.hypot(p, q)
    return abs(p)  # ACTIVE_POWER


def _tier_name(duration_seconds: int) -> str:
    """Readable temporary-limit name, e.g. 1200 -> 'IT20min', 90 -> 'IT90s'."""
    if duration_seconds % 60 == 0:
        return f"IT{duration_seconds // 60}min"
    return f"IT{duration_seconds}s"


def _is_usable(current: float, min_current: float) -> bool:
    return current is not None and math.isfinite(current) and current >= min_current


# ---------------------------------------------------------------------------
# Load flow
# ---------------------------------------------------------------------------

def _run_ac_or_raise(network: pn.Network,
                     lf_parameters: Optional[lf.Parameters]) -> None:
    if lf_parameters is not None:
        candidates = [lf_parameters]
    else:
        # No parameters given: try a flat start, then a DC-based start, which
        # converges large cases where a flat start does not.
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


def _elements_with_limits(network: pn.Network) -> set:
    limits = network.get_operational_limits(show_inactive_sets=True)
    if limits.empty:
        return set()
    return set(limits.index.get_level_values("element_id"))


# ---------------------------------------------------------------------------
# Validation / reporting
# ---------------------------------------------------------------------------

def loading_report(network: pn.Network, group_name: str = DEFAULT_GROUP_NAME,
                   lf_parameters: Optional[lf.Parameters] = None,
                   run_loadflow: bool = True,
                   limit_type: str = "CURRENT") -> dict:
    """Load-flow each branch side against its selected permanent limit.

    Returns the number of sides checked, the worst base-case loading
    (observed flow / permanent limit) and how many sides exceed their permanent
    limit - which, by construction, should be none in the base case. ``limit_type``
    picks which limit (CURRENT / APPARENT_POWER / ACTIVE_POWER) to check.
    """
    if run_loadflow:
        _run_ac_or_raise(network, lf_parameters)

    limits = network.get_operational_limits(show_inactive_sets=True)
    if limits.empty:
        return {"sides": 0, "max_loading": None, "overloaded": 0}
    is_type = limits.index.get_level_values("type") == limit_type
    is_permanent = limits.index.get_level_values("acceptable_duration") == PERMANENT_DURATION
    perm = limits[is_type & is_permanent]

    values = _side_values(network, limit_type)
    checked = 0
    max_loading = 0.0
    overloaded = 0
    for (element_id, side, _type, _dur, grp), row in perm.iterrows():
        if group_name is not None and grp != group_name:
            continue
        observed = values.get((element_id, side))
        value = row["value"]
        if observed is None or not math.isfinite(observed) or not value:
            continue
        loading = observed / value
        checked += 1
        max_loading = max(max_loading, loading)
        if loading > 1.0:
            overloaded += 1
    return {"sides": checked,
            "max_loading": max_loading if checked else None,
            "overloaded": overloaded}


def _side_values(network: pn.Network, limit_type: str) -> Dict[Tuple[str, str], float]:
    out: Dict[Tuple[str, str], float] = {}
    for _type_name, getter_name, sides, _updater, _flag in _BRANCH_TYPES:
        df = getattr(network, getter_name)()
        if df.empty:
            continue
        for eid, elem in df.iterrows():
            for current_col, p_col, q_col, side, _ in sides:
                out[(eid, side)] = _side_value(elem, current_col, p_col, q_col, limit_type)
    return out


# ---------------------------------------------------------------------------
# Config guard
# ---------------------------------------------------------------------------

def _validate_config(permanent_margin: float,
                     temporary_tiers: Sequence[Tuple[int, float]],
                     limit_types: Sequence[str] = DEFAULT_LIMIT_TYPES) -> None:
    if not permanent_margin > 1.0:
        raise ValueError("permanent_margin must be > 1 (the permanent limit "
                         "must sit above the base-case current)")
    if not limit_types:
        raise ValueError("limit_types must not be empty")
    for limit_type in limit_types:
        if limit_type not in _LIMIT_TYPE_UNITS:
            raise ValueError(f"unknown limit type {limit_type}; "
                             f"choose from {sorted(_LIMIT_TYPE_UNITS)}")
    seen = set()
    for duration, margin in temporary_tiers:
        if duration <= 0:
            raise ValueError(f"temporary tier duration must be > 0, got {duration}")
        if margin <= 1.0:
            raise ValueError(
                f"temporary tier margin must be > 1 (above the permanent "
                f"limit), got {margin}")
        if duration in seen:
            raise ValueError(f"duplicate temporary tier duration {duration}")
        seen.add(duration)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

_BUILTINS = {
    "ieee14": pn.create_ieee14,
    "ieee118": pn.create_ieee118,
    "ieee300": pn.create_ieee300,
}


def _parse_tiers(text: str) -> Tuple[Tuple[int, float], ...]:
    """Parse '1200:1.1,600:1.2,60:1.4' into tier tuples."""
    tiers = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        duration, margin = part.split(":")
        tiers.append((int(duration), float(margin)))
    return tuple(tiers)


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Create operational current-limit sets on every branch of a "
                    "network from an AC load flow (permanent + temporary tiers).")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("-i", "--input", help="input network file")
    src.add_argument("--builtin", choices=sorted(_BUILTINS),
                     help="use a bundled network instead of --input")
    parser.add_argument("-o", "--output", help="write the network with limits here")
    parser.add_argument("--permanent-margin", type=float,
                        default=DEFAULT_PERMANENT_MARGIN,
                        help="permanent limit / base-case current "
                             f"(default: {DEFAULT_PERMANENT_MARGIN})")
    parser.add_argument("--tiers", type=_parse_tiers, default=DEFAULT_TEMPORARY_TIERS,
                        help="temporary tiers 'seconds:margin,...' "
                             "(default: 1200:1.10,600:1.20,60:1.40)")
    parser.add_argument("--group-name", default=DEFAULT_GROUP_NAME,
                        help=f"operational-limit group name (default: {DEFAULT_GROUP_NAME})")
    parser.add_argument("--limit-types", default="CURRENT",
                        help="comma-separated limit types to size into the group: "
                             "CURRENT,APPARENT_POWER,ACTIVE_POWER (default: CURRENT)")
    parser.add_argument("--no-transformers", action="store_true",
                        help="do not size limits on transformers")
    parser.add_argument("--no-boundary-lines", action="store_true",
                        help="do not size limits on boundary (dangling) lines")
    parser.add_argument("--min-current", type=float, default=1.0,
                        help="skip sides below this current in A (default: 1.0)")
    parser.add_argument("--only-missing", action="store_true",
                        help="skip branches that already carry operational limits")
    parser.add_argument("--no-select", action="store_true",
                        help="create the group but do not make it the active set")
    parser.add_argument("--validate", action="store_true",
                        help="report base-case loading against the new permanent limits")
    args = parser.parse_args(argv)

    network = _BUILTINS[args.builtin]() if args.builtin else pn.load(args.input)

    stats = add_current_limits(
        network,
        permanent_margin=args.permanent_margin,
        temporary_tiers=args.tiers,
        group_name=args.group_name,
        include_transformers=not args.no_transformers,
        include_boundary_lines=not args.no_boundary_lines,
        min_current=args.min_current,
        only_missing=args.only_missing,
        select=not args.no_select,
        limit_types=tuple(t.strip() for t in args.limit_types.split(",") if t.strip()),
    )
    print(f"Group '{stats['group_name']}': limits on {stats['branches']} branch(es), "
          f"{stats['sides']} side(s) "
          f"({stats['permanent_limits']} permanent + "
          f"{stats['temporary_limits']} temporary).")
    if stats["skipped_no_flow"]:
        print(f"Skipped {stats['skipped_no_flow']} side(s) with no usable flow.")
    if stats["skipped_existing"]:
        print(f"Skipped {stats['skipped_existing']} branch(es) that already had limits.")
    for type_name, ts in stats["by_type"].items():
        print(f"  {type_name}: {ts['branches']} branch(es), {ts['sides']} side(s)")

    if args.validate:
        # The network is already solved by add_current_limits' load flow.
        report = loading_report(network, group_name=args.group_name,
                                run_loadflow=False)
        if report["sides"]:
            print(f"Base-case loading: {report['sides']} side(s) checked, "
                  f"max {report['max_loading'] * 100:.1f} % of permanent limit, "
                  f"{report['overloaded']} overloaded.")
            if report["overloaded"]:
                print("Validation FAILED: a branch exceeds its permanent limit "
                      "in the base case.")
                return 1
            print("Validation OK: every branch sits within its permanent limit.")
        else:
            print("Validation: no branch sides to check.")

    if args.output:
        network.save(args.output, format="XIIDM")
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
