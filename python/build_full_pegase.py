"""
Build a fully-enhanced network in one pass.

Loads a case (e.g. a raw PEGASE snapshot, which ships with almost no operational
data), optionally converts it to a node-breaker topology, runs a single AC load
flow, then applies every completion in this repo - reactive limits, ratio tap
changers, a realistic generation mix, apparent-power ratings, active power
control, synthetic measurements, observability flags and load-flow-based
current + apparent-power limit sets - and writes the result.

The output is a network that exercises most of the IIDM object types and
extensions, useful as a realistic reference to compare synthetic networks
against (see network_summary.py). Real networks are node-breaker (busbar
sections + switches), so that conversion is on by default.
"""

from __future__ import annotations

import argparse
import sys

import pypowsybl.network as pn

import add_current_limits as acl
import bus_to_node_breaker as b2nb
import complete_network as cn


def build_full(input_path: str, output_path: str,
               node_breaker: bool = True) -> pn.Network:
    """Apply every enhancement to the network at ``input_path`` and save it."""
    net = pn.load(input_path)

    if node_breaker:
        # Real networks are node-breaker; rebuild the (bus-breaker) case as an
        # electrically identical node-breaker model first, so busbar sections,
        # switches and their position extensions are present and every later
        # completion lands on the node-breaker topology.
        net = b2nb.convert(net)
        print(f"node-breaker            : {len(net.get_busbar_sections())} busbar "
              f"section(s), {len(net.get_switches())} switch(es)")

    cn._run_ac_or_raise(net, None)  # one load flow shared by every step

    steps = [
        ("reactive limits", lambda: cn.add_reactive_limits(net, run_loadflow=False)),
        ("ratio tap changers", lambda: cn.add_ratio_tap_changers(net, run_loadflow=False)),
        ("generation mix", lambda: cn.set_generation_mix(net)),
        ("rated_s", lambda: cn.add_rated_s(net, run_loadflow=False)),
        ("active power control", lambda: cn.add_active_power_control(net)),
        ("measurements", lambda: cn.add_measurements(net, run_loadflow=False)),
        ("observability", lambda: cn.add_observability(net)),
        ("current + apparent limits",
         lambda: acl.add_current_limits(net, run_loadflow=False,
                                        limit_types=("CURRENT", "APPARENT_POWER"))),
    ]
    for name, step in steps:
        result = step()
        print(f"{name:24s}: {result}")

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
