package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fill missing generator reactive limits and ratio tap changers on IEEE-14 and
 * check the additions are sized sensibly and leave the base case unchanged — the
 * Java counterpart of the Python {@code test_complete_network.py} suite.
 */
class NetworkCompleterTest {

    @Test
    void reactiveLimitsFillPlaceholderOnly() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.ReactiveStats stats = NetworkCompleter.addReactiveLimits(
                net, NetworkCompleter.ReactiveConfig.defaults());
        // IEEE-14 ships B1-G with a placeholder +/-Double.MAX band; the rest finite.
        assertEquals(1, stats.filled());
        assertEquals(4, stats.skippedExisting());

        MinMaxReactiveLimits mm = net.getGenerator("B1-G")
                .getReactiveLimits(MinMaxReactiveLimits.class);
        assertTrue(Math.abs(mm.getMinQ()) < 1e4);
        assertEquals(-mm.getMaxQ(), mm.getMinQ(), 1e-9);
    }

    @Test
    void reactiveSizingFromRatedS() {
        Network net = Network.create("s", "test");
        var s = net.newSubstation().setId("S").add();
        var vl = s.newVoltageLevel().setId("VL").setNominalV(100.0)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("B").add();
        vl.newGenerator().setId("G").setBus("B").setConnectableBus("B")
                .setMinP(0.0).setMaxP(80.0).setTargetP(60.0).setTargetV(100.0)
                .setVoltageRegulatorOn(true).setRatedS(100.0).add();
        vl.newLoad().setId("L").setBus("B").setConnectableBus("B").setP0(60.0).setQ0(0.0).add();

        NetworkCompleter.addReactiveLimits(net, NetworkCompleter.ReactiveConfig.defaults()
                .withOnlyMissing(false).withRunLoadFlow(false));
        MinMaxReactiveLimits mm = net.getGenerator("G")
                .getReactiveLimits(MinMaxReactiveLimits.class);
        // Q = sqrt(ratedS^2 - maxP^2) = sqrt(100^2 - 80^2) = 60.
        assertEquals(60.0, mm.getMaxQ(), 1e-6);
    }

    @Test
    void reactiveRejectsBadPowerFactor() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addReactiveLimits(
                net, NetworkCompleter.ReactiveConfig.defaults().withPowerFactor(0.0)));
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addReactiveLimits(
                net, NetworkCompleter.ReactiveConfig.defaults().withPowerFactor(1.5)));
    }

    @Test
    void ratioTapChangersAddedAndTransparent() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        Map<String, Double> before = busVoltages(net);

        NetworkCompleter.RatioTapStats stats = NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults().withRunLoadFlow(false));
        assertEquals(net.getTwoWindingsTransformerCount(), stats.added());
        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            assertTrue(tx.hasRatioTapChanger());
        }

        // Neutral tap, rho == 1 -> the base case is electrically unchanged.
        LoadFlow.run(net);
        Map<String, Double> after = busVoltages(net);
        double maxDv = 0.0;
        for (String id : before.keySet()) {
            maxDv = Math.max(maxDv, Math.abs(before.get(id) - after.get(id)));
        }
        assertTrue(maxDv < 1e-6, "neutral RTC should be transparent, maxDv=" + maxDv);
    }

    @Test
    void ratioTapChangerStructure() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults());
        RatioTapChanger rtc = net.getTwoWindingsTransformers().iterator().next()
                .getRatioTapChanger();
        assertEquals(0, rtc.getLowTapPosition());
        assertEquals(8, rtc.getTapPosition());               // neutral
        assertEquals(2 * 8 + 1, rtc.getStepCount());
        assertTrue(rtc.isRegulating());
        assertEquals(RatioTapChanger.RegulationMode.VOLTAGE, rtc.getRegulationMode());
        assertEquals(1.0, rtc.getStep(8).getRho(), 1e-9);    // neutral step is rho 1
        assertEquals(0.9, rtc.getStep(0).getRho(), 1e-9);
        assertEquals(1.1, rtc.getStep(16).getRho(), 1e-9);
    }

    @Test
    void ratioTapChangersSkipExisting() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.RatioTapStats first = NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults());
        assertEquals(3, first.added());
        NetworkCompleter.RatioTapStats again = NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults());
        assertEquals(0, again.added());
        assertEquals(3, again.skippedExisting());
    }

    @Test
    void ratioTapChangerRegulatesToBaseVoltage() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        Map<String, Double> before = busVoltages(net);
        NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults().withRunLoadFlow(false));

        LoadFlowParameters params = new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setDistributedSlack(true)
                .setTransformerVoltageControlOn(true);
        LoadFlowResult res = LoadFlow.run(net, params);
        assertTrue(res.isFullyConverged());
        Map<String, Double> after = busVoltages(net);
        double maxDv = 0.0;
        for (String id : before.keySet()) {
            maxDv = Math.max(maxDv, Math.abs(before.get(id) - after.get(id)));
        }
        assertTrue(maxDv < 1e-3, "regulator target = base V, maxDv=" + maxDv);
        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            assertEquals(8, tx.getRatioTapChanger().getTapPosition());
        }
    }

    @Test
    void reactiveUnityPowerFactorLeavesUnsized() {
        Network net = IeeeCdfNetworkFactory.create14();  // no rated_s
        NetworkCompleter.ReactiveStats stats = NetworkCompleter.addReactiveLimits(net,
                NetworkCompleter.ReactiveConfig.defaults()
                        .withPowerFactor(1.0).withOnlyMissing(false));
        // Unity power factor -> zero band -> left unsized, not a degenerate [0,0].
        assertEquals(0, stats.filled());
        assertEquals(stats.generators(), stats.skippedNoSize());
    }

    @Test
    void nonRegulatingTapChangerKeepsTargetVoltage() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults().withRegulating(false));
        RatioTapChanger rtc = net.getTwoWindingsTransformers().iterator().next()
                .getRatioTapChanger();
        assertFalse(rtc.isRegulating());
        // The base-case setpoint is still stored, so a later flip to regulating
        // has a valid target rather than NaN.
        assertTrue(Double.isFinite(rtc.getTargetV()) && rtc.getTargetV() > 0);
    }

    @Test
    void ratioTapChangersRejectBadConfig() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults().withStepsPerSide(0)));
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults().withStepIncrement(0.0)));
    }

    @Test
    void generationMixAssignsBySize() {
        Network net = IeeeCdfNetworkFactory.create14();  // all generators OTHER
        // Distinct capabilities so the size ranking is unambiguous.
        double[] caps = {500.0, 400.0, 300.0, 200.0, 100.0};
        String[] ids = {"B1-G", "B2-G", "B3-G", "B6-G", "B8-G"};
        for (int i = 0; i < ids.length; i++) {
            net.getGenerator(ids[i]).setMaxP(caps[i]);
        }
        Map<EnergySource, Double> mix = new java.util.LinkedHashMap<>();
        mix.put(EnergySource.NUCLEAR, 0.4);
        mix.put(EnergySource.HYDRO, 0.3);
        mix.put(EnergySource.SOLAR, 0.3);
        NetworkCompleter.GenerationMixStats stats = NetworkCompleter.setGenerationMix(net, mix, true);
        assertEquals(5, stats.assigned());
        // Largest unit is base-load (nuclear), smallest is intermittent (solar).
        assertEquals(EnergySource.NUCLEAR, net.getGenerator("B1-G").getEnergySource());
        assertEquals(EnergySource.SOLAR, net.getGenerator("B8-G").getEnergySource());
        for (Generator g : net.getGenerators()) {
            assertTrue(mix.containsKey(g.getEnergySource()));
        }
    }

    @Test
    void generationMixOnlyUndefinedAndRejectBad() {
        Network net = IeeeCdfNetworkFactory.create14();
        net.getGenerator("B1-G").setEnergySource(EnergySource.WIND);
        NetworkCompleter.GenerationMixStats stats = NetworkCompleter.setGenerationMix(
                net, NetworkCompleter.DEFAULT_GENERATION_MIX, true);
        assertEquals(1, stats.skippedDefined());
        assertEquals(EnergySource.WIND, net.getGenerator("B1-G").getEnergySource());
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.setGenerationMix(net, Map.of(), true));
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.setGenerationMix(
                net, Map.of(EnergySource.NUCLEAR, -1.0), true));
    }

    @Test
    void ratedSGeneratorFromPowerFactor() {
        Network net = Network.create("s", "test");
        var s = net.newSubstation().setId("S").add();
        var vl = s.newVoltageLevel().setId("VL").setNominalV(100.0)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("B").add();
        vl.newGenerator().setId("G").setBus("B").setConnectableBus("B")
                .setMinP(0.0).setMaxP(80.0).setTargetP(60.0).setTargetV(100.0)
                .setVoltageRegulatorOn(true).add();
        vl.newLoad().setId("L").setBus("B").setConnectableBus("B").setP0(60.0).setQ0(0.0).add();

        NetworkCompleter.addRatedS(net, 0.8, NetworkCompleter.DEFAULT_TRANSFORMER_LOADING,
                false, false);
        // rated_s = maxP / power_factor = 80 / 0.8 = 100.
        assertEquals(100.0, net.getGenerator("G").getRatedS(), 1e-6);
    }

    @Test
    void ratedSTransformerFromFlow() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.addRatedS(net, NetworkCompleter.DEFAULT_GENERATOR_POWER_FACTOR,
                0.6, true, true);
        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            double sBase = Math.max(
                    Math.hypot(tx.getTerminal1().getP(), tx.getTerminal1().getQ()),
                    Math.hypot(tx.getTerminal2().getP(), tx.getTerminal2().getQ()));
            assertEquals(sBase / 0.6, tx.getRatedS(), 1e-6);
        }
    }

    @Test
    void ratedSOnlyMissing() {
        Network net = IeeeCdfNetworkFactory.create14();
        net.getGenerator("B1-G").setRatedS(500.0);
        NetworkCompleter.RatedSStats stats = NetworkCompleter.addRatedS(net,
                NetworkCompleter.DEFAULT_GENERATOR_POWER_FACTOR,
                NetworkCompleter.DEFAULT_TRANSFORMER_LOADING, true, true);
        assertEquals(1, stats.generatorsSkippedExisting());
        assertEquals(500.0, net.getGenerator("B1-G").getRatedS(), 0.0);
    }

    @Test
    void ratedSRejectsBadConfig() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.addRatedS(net, 0.0, 0.6, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.addRatedS(net, 0.85, 0.0, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.addRatedS(net, 0.85, 1.5, true, false));
    }

    @Test
    void measurementsFromLoadFlow() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.MeasurementsStats stats = NetworkCompleter.addMeasurements(
                net, 0.02, 0.5, true, true);
        // 16 injections x 2 + 20 branches x 6 = 152 measurements.
        assertEquals(152, stats.measurements());

        @SuppressWarnings("unchecked")
        com.powsybl.iidm.network.extensions.Measurements<com.powsybl.iidm.network.Line> meas =
                net.getLine("L1-2-1").getExtension(
                        com.powsybl.iidm.network.extensions.Measurements.class);
        assertNotNull(meas);
        double p1 = net.getLine("L1-2-1").getTerminal1().getP();
        com.powsybl.iidm.network.extensions.Measurement m = meas.getMeasurements(
                com.powsybl.iidm.network.extensions.Measurement.Type.ACTIVE_POWER).stream()
                .filter(x -> x.getSide() == ThreeSidesOne())
                .findFirst().orElseThrow();
        assertEquals(p1, m.getValue(), 1e-6);
        assertEquals(Math.max(Math.abs(p1) * 0.02, 0.5), m.getStandardDeviation(), 1e-6);
        // Idempotent under only_missing.
        assertEquals(0, NetworkCompleter.addMeasurements(net, 0.01, 0.1, true, false).measurements());
    }

    private static com.powsybl.iidm.network.ThreeSides ThreeSidesOne() {
        return com.powsybl.iidm.network.ThreeSides.ONE;
    }

    @Test
    void observabilityFlags() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.ObservabilityStats stats = NetworkCompleter.addObservability(net, 2.0, true);
        assertEquals(16, stats.injections());   // 5 generators + 11 loads
        assertEquals(20, stats.branches());     // 17 lines + 3 transformers
        var inj = net.getGenerator("B1-G").getExtension(
                com.powsybl.iidm.network.extensions.InjectionObservability.class);
        assertNotNull(inj);
        assertTrue(inj.isObservable());
        assertEquals(2.0, inj.getQualityP().getStandardDeviation(), 1e-9);
        // Idempotent under only_missing.
        NetworkCompleter.ObservabilityStats again = NetworkCompleter.addObservability(net, 1.0, true);
        assertEquals(0, again.injections());
        assertEquals(0, again.branches());
    }

    @Test
    void measurementsRejectBadStd() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.addMeasurements(net, -0.1, 0.1, true, true));
    }

    @Test
    void activePowerControlAddedWithParticipation() {
        Network net = IeeeCdfNetworkFactory.create14();
        NetworkCompleter.ActivePowerControlStats stats =
                NetworkCompleter.addActivePowerControl(net, 5.0, true);
        assertEquals(5, stats.added());

        for (Generator g : net.getGenerators()) {
            ActivePowerControl<Generator> apc = g.getExtension(ActivePowerControl.class);
            assertNotNull(apc);
            assertTrue(apc.isParticipate());
            assertEquals(5.0, apc.getDroop(), 1e-9);
            double expected = g.getMaxP() > 0 ? g.getMaxP() : 1.0;
            assertEquals(expected, apc.getParticipationFactor(), 1e-6);
        }

        // Idempotent.
        NetworkCompleter.ActivePowerControlStats again =
                NetworkCompleter.addActivePowerControl(net, 4.0, true);
        assertEquals(0, again.added());
        assertEquals(5, again.skippedExisting());
    }

    @Test
    void activePowerControlRejectsBadDroop() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCompleter.addActivePowerControl(net, 0.0, true));
    }

    @Test
    void bothCompletionsRoundtrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Network net = ExtendedIeee14Factory.create();
        NetworkCompleter.addReactiveLimits(net, NetworkCompleter.ReactiveConfig.defaults());
        NetworkCompleter.addRatioTapChangers(net, NetworkCompleter.RatioTapConfig.defaults());
        NetworkCompleter.setGenerationMix(net, NetworkCompleter.DEFAULT_GENERATION_MIX, true);
        NetworkCompleter.addActivePowerControl(net, 4.0, true);
        long rtcCount = countRatioTapChangers(net);
        assertTrue(rtcCount > 0);
        EnergySource firstSource = net.getGenerators().iterator().next().getEnergySource();

        java.nio.file.Path out = dir.resolve("completed.xiidm");
        NetworkSerDeWrite(net, out);
        Network reloaded = Network.read(out);
        assertEquals(rtcCount, countRatioTapChangers(reloaded));
        // Energy source and active power control survive the round trip.
        assertEquals(firstSource, reloaded.getGenerators().iterator().next().getEnergySource());
        assertNotNull(reloaded.getGenerators().iterator().next()
                .getExtension(ActivePowerControl.class));
        assertTrue(LoadFlow.run(reloaded).isFullyConverged());
    }

    private static void NetworkSerDeWrite(Network net, java.nio.file.Path out) {
        com.powsybl.iidm.serde.NetworkSerDe.write(net,
                new com.powsybl.iidm.serde.ExportOptions(), out);
    }

    private static long countRatioTapChangers(Network net) {
        long n = 0;
        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            if (tx.hasRatioTapChanger()) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Double> busVoltages(Network net) {
        Map<String, Double> out = new HashMap<>();
        for (Bus bus : net.getBusView().getBuses()) {
            out.put(bus.getId(), bus.getV());
        }
        return out;
    }
}
