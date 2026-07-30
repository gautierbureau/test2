package com.example.transporter;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;

import java.nio.file.Path;

/**
 * Build a fully-enhanced network in one pass — the Java port of
 * {@code build_full_network.py}.
 *
 * <p>Injects synthetic equipment, runs a load flow, applies every completion in
 * this repo (reactive limits, ratio tap changers, generation mix, rated_s,
 * reactive capability curves, active power control, short circuit, properties,
 * load detail, measurements, discrete measurements, observability, current +
 * apparent-power limits) and — by default — rebuilds the result as a
 * node-breaker network and adds the OpenLoadFlow outer-loop modeling (remote /
 * shared / secondary / transformer / shunt voltage control, SVC standby, HVDC
 * angle droop).
 *
 * <p>Unlike the Python pipeline (which completes on the bus-breaker network and
 * converts last), the typed IIDM API lets us convert first and complete on the
 * node-breaker network, so the extensions never have to be copied across the
 * rebuild. The base case must solve a load flow (a flat start is tried first,
 * then a DC start); the final load flow is reported, not enforced, so a case
 * whose node-breaker form does not reconverge is still written.
 */
public final class BuildFullNetwork {

    private BuildFullNetwork() {
    }

    private static void log(String name, Object stats) {
        System.out.printf("%-26s: %s%n", name, stats);
    }

    /** Apply every enhancement to {@code net} in place and return it. */
    public static Network buildFull(Network net, boolean nodeBreaker) {
        // Synthetic equipment (electrically neutral) before the load flow.
        log("equipment", EquipmentInjector.addEquipment(net));
        NetworkCompleter.runAcOrThrow(net);

        if (nodeBreaker) {
            net = BusToNodeBreakerConverter.convert(net);
            NetworkCompleter.runAcOrThrow(net);
            log("node-breaker", net.getBusbarSectionCount() + " busbar section(s), "
                    + net.getSwitchCount() + " switch(es)");
        }

        // Completions (each grounded on the solved base case).
        log("reactive limits", NetworkCompleter.addReactiveLimits(net,
                NetworkCompleter.ReactiveConfig.defaults().withRunLoadFlow(false)));
        log("ratio tap changers", NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults().withRunLoadFlow(false)));
        log("generation mix", NetworkCompleter.setGenerationMix(net,
                NetworkCompleter.DEFAULT_GENERATION_MIX, true));
        log("rated_s", NetworkCompleter.addRatedS(net,
                NetworkCompleter.DEFAULT_GENERATOR_POWER_FACTOR,
                NetworkCompleter.DEFAULT_TRANSFORMER_LOADING, true, false));
        log("reactive capability curves", NetworkCompleter.addReactiveCapabilityCurves(net,
                NetworkCompleter.DEFAULT_CURVE_POINTS, true));
        log("active power control", NetworkCompleter.addActivePowerControl(net,
                NetworkCompleter.DEFAULT_DROOP, true));
        log("short circuit", NetworkCompleter.addShortCircuit(net, true));
        log("properties", NetworkCompleter.addProperties(net,
                NetworkCompleter.DEFAULT_REGION_COUNT, NetworkCompleter.DEFAULT_COUNTRY, true));
        log("load detail", NetworkCompleter.addLoadDetail(net,
                NetworkCompleter.DEFAULT_LOAD_FIXED_FRACTION, true));
        log("measurements", NetworkCompleter.addMeasurements(net,
                NetworkCompleter.DEFAULT_MEASUREMENT_STD_DEV, NetworkCompleter.DEFAULT_STD_DEV_FLOOR,
                true, false));
        log("discrete measurements", NetworkCompleter.addDiscreteMeasurements(net, true));
        log("observability", NetworkCompleter.addObservability(net,
                NetworkCompleter.DEFAULT_OBSERVABILITY_STD_DEV, true));
        log("current + apparent limits", CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withRunLoadFlow(false).withLimitTypes(
                        java.util.List.of(com.powsybl.iidm.network.LimitType.CURRENT,
                                com.powsybl.iidm.network.LimitType.APPARENT_POWER))));

        if (nodeBreaker) {
            // OLF outer-loop modeling (each keeps the base case converged).
            RemoteVoltageControl.DeportResult rvc = RemoteVoltageControl.deportGenerators(
                    net, null, RemoteVoltageControl.DEFAULT_HV_THRESHOLD,
                    RemoteVoltageControl.DEFAULT_LV_NOMINAL_V, RemoteVoltageControl.DEFAULT_GSU_X_PU);
            net = rvc.network();
            log("remote voltage control", "deported " + rvc.deported() + "/" + rvc.eligible());
            log("transformer voltage ctl", VoltageControlCompleter.addTransformerVoltageControl(
                    net, VoltageControlCompleter.DEFAULT_TVC_COUNT,
                    VoltageControlCompleter.DEFAULT_TVC_DEADBAND));
            log("secondary voltage ctl", VoltageControlCompleter.addSecondaryVoltageControl(
                    net, VoltageControlCompleter.DEFAULT_SECONDARY_VC_ZONES,
                    VoltageControlCompleter.DEFAULT_SECONDARY_VC_UNITS));
            log("shared voltage control", VoltageControlCompleter.addSharedVoltageControl(
                    net, VoltageControlCompleter.DEFAULT_SHARED_VC_GROUPS));
            log("shunt voltage control", VoltageControlCompleter.addShuntVoltageControl(
                    net, VoltageControlCompleter.DEFAULT_SHUNT_VC_COUNT,
                    VoltageControlCompleter.DEFAULT_SHUNT_SECTIONS,
                    VoltageControlCompleter.DEFAULT_SHUNT_B_PER_SECTION, 1.0));
            log("phase control", VoltageControlCompleter.addPhaseControl(
                    net, null, VoltageControlCompleter.DEFAULT_PHASE_CURRENT_MARGIN));
            log("svc voltage control", VoltageControlCompleter.addSvcVoltageControl(
                    net, VoltageControlCompleter.DEFAULT_SVC_SLOPE));
            log("svc standby automaton", VoltageControlCompleter.addSvcStandbyAutomaton(net, null));
            log("hvdc ac emulation", VoltageControlCompleter.addHvdcAcEmulation(
                    net, VoltageControlCompleter.DEFAULT_HVDC_DROOP, null));
        }

        // Solve with a DC-based init so the fixture is saved converged; reported,
        // not enforced (a very large node-breaker case may not reconverge).
        LoadFlowParameters params = new LoadFlowParameters()
                .setDistributedSlack(true).setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        LoadFlowResult result = LoadFlow.run(net, params);
        log("final load flow (DC init)", result.getStatus());
        if (!result.isFullyConverged()) {
            System.out.println("  WARNING: fixture saved without a converged final state");
        }
        return net;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: BuildFullNetwork <input> <output> [--bus-breaker]");
            System.exit(2);
        }
        boolean nodeBreaker = args.length < 3 || !"--bus-breaker".equals(args[2]);
        Network net = Network.read(Path.of(args[0]));
        net = buildFull(net, nodeBreaker);
        NetworkSerDe.write(net, Path.of(args[1]));
        System.out.println("Wrote " + args[1]);
    }
}
