package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
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
    void ratioTapChangersRejectBadConfig() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults().withStepsPerSide(0)));
        assertThrows(IllegalArgumentException.class, () -> NetworkCompleter.addRatioTapChangers(
                net, NetworkCompleter.RatioTapConfig.defaults().withStepIncrement(0.0)));
    }

    @Test
    void bothCompletionsRoundtrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Network net = ExtendedIeee14Factory.create();
        NetworkCompleter.addReactiveLimits(net, NetworkCompleter.ReactiveConfig.defaults());
        NetworkCompleter.addRatioTapChangers(net, NetworkCompleter.RatioTapConfig.defaults());
        long rtcCount = countRatioTapChangers(net);
        assertTrue(rtcCount > 0);

        java.nio.file.Path out = dir.resolve("completed.xiidm");
        NetworkSerDeWrite(net, out);
        Network reloaded = Network.read(out);
        assertEquals(rtcCount, countRatioTapChangers(reloaded));
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
