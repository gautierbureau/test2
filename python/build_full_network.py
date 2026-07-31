"""
Build a fully-enhanced network in one pass, from any solvable input.

The pipeline is not tied to a particular case: it loads any network pypowsybl
can import (XIIDM, MATPOWER .mat/.m - with real base voltages kept automatically
- PSS/E .raw, CGMES, ...), runs a single AC load flow, and applies every
completion in this repo - reactive limits, ratio tap changers, a realistic
generation mix, apparent-power ratings, active power control, synthetic
measurements, observability flags and load-flow-based current + apparent-power
limit sets. By default it then rebuilds the result as a node-breaker network
(busbar sections + switches, with element properties carried across) and adds
the OpenLoadFlow outer-loop modeling that only node-breaker feeder bays can
carry (remote / shared / secondary / transformer / shunt voltage control, SVC
standby automaton, HVDC angle-droop control) so it looks like a real one.

The output exercises most of the IIDM object types and extensions, useful as a
realistic reference to compare synthetic networks against (see
network_summary.py).

Requirements and graceful degradation:
- The input must solve an AC load flow (a flat start is tried first, then a
  DC-based start); if neither converges the build aborts.
- Every completion operates on whatever equipment exists and reports counts, so
  features a given network lacks simply report zero (no EHV generators -> no
  remote voltage control, no multi-unit voltage levels -> no shared control, ...).
- The completions run on the (converging) bus-breaker network and the
  node-breaker conversion is done last, because the rebuilt topology loses the
  source's solved voltage profile and a flat start does not reconverge on very
  large cases (e.g. ACTIVSg70k). The node-breaker modeling steps each re-check
  convergence (DC init) and roll back if the rebuilt model does not reconverge,
  so on such a case they report zero while the fixture is still written. The
  converter carries operational limits and element-keyed extensions across; the
  measurements and observability extensions it does not copy are re-attached here.
"""

from __future__ import annotations

import argparse
import sys

import pypowsybl.loadflow as lf
import pypowsybl.network as pn

import add_current_limits as acl
import add_equipment as eq
import add_remote_voltage_control as rvc
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


def _load_case(input_path: str) -> pn.Network:
    """Load a case, keeping real base voltages for MATPOWER inputs.

    The MATPOWER importer flattens bus base voltages to 1.0 kV by default;
    ``matpower.import.ignore-base-voltage=false`` keeps the real nominal kV, which
    everything voltage-dependent (voltage class, short-circuit levels, remote-VC
    eligibility) relies on. Applied automatically for ``.mat``/``.m`` inputs.
    """
    if input_path.lower().endswith((".mat", ".m")):
        return pn.load(input_path,
                       parameters={"matpower.import.ignore-base-voltage": "false"})
    return pn.load(input_path)


def build_full(input_path: str, output_path: str,
               node_breaker: bool = True) -> pn.Network:
    """Apply every enhancement to the network at ``input_path`` and save it."""
    net = _load_case(input_path)

    # Inject synthetic equipment the source cases lack (batteries, SVCs, HVDC
    # links) before the load flow; they are created electrically neutral, so the
    # base case is unchanged.
    print(f"equipment               : {eq.add_equipment(net)}")

    cn._run_ac_or_raise(net, None)  # one load flow shared by every step

    steps = [
        ("reactive limits", lambda: cn.add_reactive_limits(net, run_loadflow=False)),
        ("ratio tap changers", lambda: cn.add_ratio_tap_changers(net, run_loadflow=False)),
        ("phase control", lambda: cn.add_phase_control(net)),
        ("svc voltage control", lambda: cn.add_svc_voltage_control(net)),
        ("generation mix", lambda: cn.set_generation_mix(net)),
        ("rated_s", lambda: cn.add_rated_s(net, run_loadflow=False)),
        ("reactive capability curves", lambda: cn.add_reactive_capability_curves(net)),
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

        # Remote voltage control: deport some EHV generators behind a new GSU
        # transformer (node-breaker, feeder bays) regulating the HV bus remotely.
        net, rvc_stats = rvc.deport_generators(net)
        print(f"remote voltage control  : {rvc_stats}")

        # Transformer voltage control: keep a converging subset of the ratio tap
        # changers regulating (thousands at once do not converge).
        print(f"transformer voltage ctl : {cn.add_transformer_voltage_control(net)}")

        # Secondary voltage control: a couple of control zones (pilot bus + a few
        # generators) - run before shared control so the zone generators are still
        # locally regulating.
        print(f"secondary voltage ctl   : {cn.add_secondary_voltage_control(net)}")

        # Shared voltage control: several generators in a voltage level co-regulate
        # one common bus.
        print(f"shared voltage control  : {cn.add_shared_voltage_control(net)}")

        # Shunt voltage control: a few switchable shunts regulating voltage.
        print(f"shunt voltage control   : {cn.add_shunt_voltage_control(net)}")

        # SVC standby automaton: put a converging subset of SVCs into standby
        # (fixed b0 until voltage leaves the band around its solved value).
        print(f"svc standby automaton   : {cn.add_svc_standby_automaton(net)}")

        # HVDC AC emulation: angle-droop active power control on the HVDC links.
        print(f"hvdc ac emulation       : {cn.add_hvdc_ac_emulation(net)}")

    # Solve the finished network with a DC-based voltage init so the fixture is
    # saved in (and reported as) a converged state. A flat start does not
    # converge on the rebuilt node-breaker model at this size (many retained
    # breakers); DC init converges the ~13k case. The very large node-breaker
    # case (ACTIVSg70k) does not reconverge from any non-solved start, so this is
    # reported, not enforced - the fixture is still written.
    params = lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                           voltage_init_mode=lf.VoltageInitMode.DC_VALUES)
    status = lf.run_ac(net, params)[0].status.name
    print(f"final load flow (DC init): {status}")
    if status != "CONVERGED":
        print("  WARNING: fixture saved without a converged final state")

    net.save(output_path, format="XIIDM")
    print(f"Wrote {output_path}")
    return net


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-i", "--input", required=True,
                        help="input case; a .mat/.m MATPOWER case is imported with "
                             "real base voltages automatically (e.g. case13659pegase.mat)")
    parser.add_argument("-o", "--output", default="full_network.xiidm.gz",
                        help="output network; a .gz suffix writes it compressed "
                             "(default: %(default)s)")
    parser.add_argument("--bus-breaker", action="store_true",
                        help="keep the bus-breaker topology (skip node-breaker conversion)")
    args = parser.parse_args(argv)
    build_full(args.input, args.output, node_breaker=not args.bus_breaker)
    return 0


if __name__ == "__main__":
    sys.exit(_main())
