"""
Build a fully-enhanced network in one pass.

Loads a case (e.g. a raw PEGASE snapshot, which ships with almost no operational
data), runs a single AC load flow, applies every completion in this repo -
reactive limits, ratio tap changers, a realistic generation mix, apparent-power
ratings, active power control, synthetic measurements, observability flags and
load-flow-based current + apparent-power limit sets - and, by default, rebuilds
the result as a node-breaker network (busbar sections + switches, with element
properties carried across) so it looks like a real one.

The output exercises most of the IIDM object types and extensions, useful as a
realistic reference to compare synthetic networks against (see
network_summary.py).

The completions run on the (converging) bus-breaker network and the node-breaker
conversion is done last: the rebuilt topology loses the source's solved voltage
profile, and a flat-start load flow does not reconverge on very large cases
(e.g. ACTIVSg70k), so re-solving the node-breaker model is avoided. The
converter carries operational limits and element-keyed extensions across; the
measurements and observability extensions it does not copy are re-attached here.
"""

from __future__ import annotations

import argparse
import sys

import pypowsybl.network as pn

import add_current_limits as acl
import add_equipment as eq
import bus_to_node_breaker as b2nb
import complete_network as cn


def _reattach_measurements(src: pn.Network, dst: pn.Network) -> None:
    """Copy the measurements extension across a rebuild (converter skips it)."""
    df = src.get_extensions("measurements")
    if df.empty:
        return
    # pypowsybl transposes value/standard_deviation on create, so pass them
    # swapped; injection rows carry no side, branch rows do (a mixed empty/side
    # column trips the ThreeSides enum), so create the two groups separately.
    base = df.assign(value=df["standard_deviation"], standard_deviation=df["value"])
    inj = base[base["side"] == ""]
    if not inj.empty:
        dst.create_extensions("measurements", inj.drop(columns=["side"]))
    branch = base[base["side"] != ""]
    if not branch.empty:
        dst.create_extensions("measurements", branch)


def _reattach_observability(src: pn.Network, dst: pn.Network) -> None:
    """Copy the observability extensions across a rebuild (converter skips them)."""
    for name in ("injectionObservability", "branchObservability"):
        df = src.get_extensions(name)
        if df.empty:
            continue
        # Drop pypowsybl's internal *_null nullability markers before recreating.
        dst.create_extensions(name, df[[c for c in df.columns if not c.endswith("_null")]])


def _reattach_discrete_measurements(src: pn.Network, dst: pn.Network) -> None:
    """Copy the discreteMeasurements extension across a rebuild (element_id-keyed)."""
    df = src.get_extensions("discreteMeasurements")
    if not df.empty:
        dst.create_extensions("discreteMeasurements", df)


def _reattach_identifiable_short_circuit(src: pn.Network, dst: pn.Network) -> None:
    """Copy identifiableShortCircuit across a rebuild.

    Its dataframe carries a read-only ``equipment_type`` column that the create
    call rejects, so the generic converter skips it; drop that column and
    recreate here (the voltage levels keep their ids in the rebuild).
    """
    df = src.get_extensions("identifiableShortCircuit")
    if not df.empty:
        dst.create_extensions("identifiableShortCircuit",
                              df[[c for c in df.columns if c != "equipment_type"]])


def build_full(input_path: str, output_path: str,
               node_breaker: bool = True) -> pn.Network:
    """Apply every enhancement to the network at ``input_path`` and save it."""
    net = pn.load(input_path)

    # Inject synthetic equipment the source cases lack (batteries, SVCs, HVDC
    # links) before the load flow; they are created electrically neutral, so the
    # base case is unchanged.
    print(f"equipment               : {eq.add_equipment(net)}")

    cn._run_ac_or_raise(net, None)  # one load flow shared by every step

    steps = [
        ("reactive limits", lambda: cn.add_reactive_limits(net, run_loadflow=False)),
        ("ratio tap changers", lambda: cn.add_ratio_tap_changers(net, run_loadflow=False)),
        ("generation mix", lambda: cn.set_generation_mix(net)),
        ("rated_s", lambda: cn.add_rated_s(net, run_loadflow=False)),
        ("active power control", lambda: cn.add_active_power_control(net)),
        ("short circuit", lambda: cn.add_short_circuit(net)),
        ("properties", lambda: cn.add_properties(net)),
        ("load detail", lambda: cn.add_load_detail(net)),
        ("measurements", lambda: cn.add_measurements(net, run_loadflow=False)),
        ("discrete measurements", lambda: cn.add_discrete_measurements(net)),
        ("observability", lambda: cn.add_observability(net)),
        ("current + apparent limits",
         lambda: acl.add_current_limits(net, run_loadflow=False,
                                        limit_types=("CURRENT", "APPARENT_POWER"))),
    ]
    for name, step in steps:
        result = step()
        print(f"{name:24s}: {result}")

    if node_breaker:
        # Rebuild as node-breaker last, then carry over the element_id-keyed
        # extensions the converter does not copy.
        nb = b2nb.convert(net)
        _reattach_measurements(net, nb)
        _reattach_observability(net, nb)
        _reattach_discrete_measurements(net, nb)
        _reattach_identifiable_short_circuit(net, nb)
        net = nb
        print(f"node-breaker            : {len(net.get_busbar_sections())} busbar "
              f"section(s), {len(net.get_switches())} switch(es)")

    net.save(output_path, format="XIIDM")
    print(f"Wrote {output_path}")
    return net


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-i", "--input", required=True,
                        help="input case (e.g. case13659pegase.xiidm or .mat)")
    parser.add_argument("-o", "--output", default="pegase13659_full.xiidm.gz",
                        help="output network; a .gz suffix writes it compressed "
                             "(default: %(default)s)")
    parser.add_argument("--bus-breaker", action="store_true",
                        help="keep the bus-breaker topology (skip node-breaker conversion)")
    args = parser.parse_args(argv)
    build_full(args.input, args.output, node_breaker=not args.bus_breaker)
    return 0


if __name__ == "__main__":
    sys.exit(_main())
