package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;
import com.powsybl.math.graph.TraverseResult;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Convert the IEEE-14 bus-breaker network to node-breaker and check that
 * (a) the topology is rebuilt as expected — every voltage level node-breaker,
 * one busbar section per bus, every feeder reached through a disconnector +
 * breaker bay — and (b) the conversion is electrically transparent: an AC load
 * flow gives the same bus voltages on both networks.
 */
class BusToNodeBreakerConverterTest {

    /** Voltage level used as the common angle reference when comparing solutions. */
    private static final String REF_VL = "VL1";

    private static LoadFlowParameters lfParams() {
        return new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false)
                .setDistributedSlack(true);
    }

    @Test
    void convertsIeee14ToNodeBreaker() {
        Network source = IeeeCdfNetworkFactory.create14();

        // Sanity: the source really is all bus-breaker.
        for (VoltageLevel vl : source.getVoltageLevels()) {
            assertEquals(TopologyKind.BUS_BREAKER, vl.getTopologyKind(),
                    "IEEE14 voltage level should start bus-breaker: " + vl.getId());
        }

        Network target = BusToNodeBreakerConverter.convert(source);

        // Every voltage level is now node-breaker.
        for (VoltageLevel vl : target.getVoltageLevels()) {
            assertEquals(TopologyKind.NODE_BREAKER, vl.getTopologyKind(),
                    "converted voltage level should be node-breaker: " + vl.getId());
        }

        // One busbar section per original configured bus.
        long sourceBuses = source.getVoltageLevelStream()
                .flatMap(vl -> vl.getBusBreakerView().getBusStream())
                .count();
        assertEquals(sourceBuses, target.getBusbarSectionCount(),
                "one busbar section expected per original bus");

        // Equipment inventory preserved.
        assertEquals(source.getGeneratorCount(), target.getGeneratorCount());
        assertEquals(source.getLoadCount(), target.getLoadCount());
        assertEquals(source.getShuntCompensatorCount(), target.getShuntCompensatorCount());
        assertEquals(source.getLineCount(), target.getLineCount());
        assertEquals(source.getTwoWindingsTransformerCount(),
                target.getTwoWindingsTransformerCount());

        // The source is untouched.
        assertEquals(0, source.getBusbarSectionCount(), "source must not be mutated");

        // Every feeder reaches its busbar through a disconnector + a breaker.
        assertEveryFeederHasDisconnectorAndBreaker(target);
    }

    @Test
    void loadFlowMatchesOriginal() {
        Network source = IeeeCdfNetworkFactory.create14();
        Network target = BusToNodeBreakerConverter.convert(source);

        LoadFlowResult srcLf = LoadFlow.run(source, lfParams());
        LoadFlowResult tgtLf = LoadFlow.run(target, lfParams());
        assertTrue(srcLf.isFullyConverged(), "source load flow should converge");
        assertTrue(tgtLf.isFullyConverged(), "converted load flow should converge");

        // Per voltage level, compare the single merged bus voltage.
        Map<String, double[]> srcV = busVoltagesByVl(source);
        Map<String, double[]> tgtV = busVoltagesByVl(target);
        assertEquals(srcV.keySet(), tgtV.keySet(), "same set of voltage levels");

        // Voltage angles are only defined up to a global additive constant (the
        // choice of reference bus), and the solver may pick a different one for
        // each network. Normalize both against a common bus before comparing.
        double srcRef = srcV.get(REF_VL)[1];
        double tgtRef = tgtV.get(REF_VL)[1];

        // The two networks are electrically identical; any residual difference
        // is solver round-off from a different internal element ordering, well
        // below what a real topological error would produce.
        for (String vlId : srcV.keySet()) {
            double[] a = srcV.get(vlId);
            double[] b = tgtV.get(vlId);
            assertEquals(a[0], b[0], 1e-3, "V magnitude mismatch at " + vlId);
            assertEquals(a[1] - srcRef, b[1] - tgtRef, 5e-3,
                    "V angle mismatch at " + vlId);
        }
    }

    @Test
    void sectionalizesEachBusIntoNBusbars() {
        Network source = IeeeCdfNetworkFactory.create14();
        long buses = source.getVoltageLevelStream()
                .flatMap(vl -> vl.getBusBreakerView().getBusStream()).count();

        int n = 3;
        // With the one-busbar-per-generator policy off, busbarSectionsPerBus
        // applies to every bus, so each becomes exactly n sections.
        Network target = BusToNodeBreakerConverter.convert(source, n, false);

        // n busbar sections per bus, joined by n-1 closed coupler breakers each.
        assertEquals(buses * n, target.getBusbarSectionCount());
        long couplers = target.getSwitchStream()
                .filter(sw -> sw.getId().contains("_COUPLER_")).count();
        assertEquals(buses * (n - 1), couplers);
        target.getSwitchStream()
                .filter(sw -> sw.getId().contains("_COUPLER_"))
                .forEach(sw -> assertFalse(sw.isOpen(), "couplers must be closed: " + sw.getId()));

        // Closed couplers keep each voltage level a single electrical bus.
        for (VoltageLevel vl : target.getVoltageLevels()) {
            assertEquals(1, vl.getBusView().getBusStream().count(), vl.getId());
        }

        // Still electrically transparent.
        LoadFlowResult src = LoadFlow.run(source, lfParams());
        LoadFlowResult tgt = LoadFlow.run(target, lfParams());
        assertTrue(src.isFullyConverged());
        assertTrue(tgt.isFullyConverged());
        Map<String, double[]> sv = busVoltagesByVl(source);
        Map<String, double[]> tv = busVoltagesByVl(target);
        for (String vlId : sv.keySet()) {
            assertEquals(sv.get(vlId)[0], tv.get(vlId)[0], 1e-3, "V mismatch at " + vlId);
        }
    }

    @Test
    void putsEachGeneratorOfAMultiUnitBusOnItsOwnBusbar() {
        // One bus (B1) hosting three generators, fed to a load bus by a line.
        Network net = Network.create("multi-unit", "test");
        Substation s1 = net.newSubstation().setId("S1").add();
        VoltageLevel vl1 = s1.newVoltageLevel().setId("VL1").setNominalV(100.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("B1").add();
        for (String g : new String[]{"GA", "GB", "GC"}) {
            vl1.newGenerator().setId(g).setBus("B1").setConnectableBus("B1")
                    .setMinP(0).setMaxP(200).setTargetP(50).setTargetV(100)
                    .setVoltageRegulatorOn(true).add()
                    .newMinMaxReactiveLimits().setMinQ(-100).setMaxQ(100).add();
        }
        vl1.newLoad().setId("LD").setBus("B1").setConnectableBus("B1").setP0(30).setQ0(10).add();
        Substation s2 = net.newSubstation().setId("S2").add();
        VoltageLevel vl2 = s2.newVoltageLevel().setId("VL2").setNominalV(100.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl2.getBusBreakerView().newBus().setId("B2").add();
        vl2.newLoad().setId("LD2").setBus("B2").setConnectableBus("B2").setP0(100).setQ0(20).add();
        net.newLine().setId("L12")
                .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
                .setVoltageLevel2("VL2").setBus2("B2").setConnectableBus2("B2")
                .setR(1).setX(5).setG1(0).setB1(0).setG2(0).setB2(0).add();

        // Default policy: each generator gets its own busbar on the 3-unit bus.
        Network target = BusToNodeBreakerConverter.convert(net);

        // B1 -> 3 sections (one per generator), B2 -> 1 section.
        assertEquals(4, target.getBusbarSectionCount());
        String ga = busbarOf(target, "GA");
        String gb = busbarOf(target, "GB");
        String gc = busbarOf(target, "GC");
        assertNotNull(ga);
        assertEquals(3, java.util.Set.of(ga, gb, gc).size(),
                "the three generators must land on three distinct busbar sections");

        // Turning the policy off collapses B1 back to a single busbar section.
        Network flat = BusToNodeBreakerConverter.convert(net, 1, false);
        assertEquals(2, flat.getBusbarSectionCount());

        // busbarSectionsPerBus controls only non-generator buses: the 3-unit bus
        // still gets 3 sections (one per generator), the load bus gets 5.
        Network decoupled = BusToNodeBreakerConverter.convert(net, 5, true);
        assertEquals(3, decoupled.getVoltageLevel("VL1").getNodeBreakerView()
                .getBusbarSectionCount());
        assertEquals(5, decoupled.getVoltageLevel("VL2").getNodeBreakerView()
                .getBusbarSectionCount());
    }

    /** Busbar section id the given generator's feeder reaches. */
    private static String busbarOf(Network net, String generatorId) {
        Terminal t = net.getGenerator(generatorId).getTerminal();
        VoltageLevel.NodeBreakerView nbv = t.getVoltageLevel().getNodeBreakerView();
        String[] found = {null};
        nbv.traverse(t.getNodeBreakerView().getNode(), (n1, sw, n2) -> {
            Terminal term = nbv.getOptionalTerminal(n2).orElse(null);
            if (term != null && term.getConnectable() instanceof BusbarSection bbs) {
                found[0] = bbs.getId();
                return TraverseResult.TERMINATE_TRAVERSER;
            }
            return TraverseResult.CONTINUE;
        });
        return found[0];
    }

    /** Map voltage level id -> {v magnitude (kV), v angle (deg)} of its bus. */
    private static Map<String, double[]> busVoltagesByVl(Network net) {
        Map<String, double[]> out = new HashMap<>();
        for (VoltageLevel vl : net.getVoltageLevels()) {
            for (Bus bus : vl.getBusView().getBuses()) {
                out.put(vl.getId(), new double[]{bus.getV(), bus.getAngle()});
            }
        }
        return out;
    }

    /**
     * For each injection and each branch terminal, walk the node-breaker graph
     * from the equipment node and assert it reaches a busbar section through at
     * least one disconnector and one breaker — i.e. a real feeder bay.
     */
    private static void assertEveryFeederHasDisconnectorAndBreaker(Network net) {
        for (Connectable<?> c : net.getConnectables()) {
            if (c instanceof BusbarSection) {
                continue;
            }
            for (Terminal t : c.getTerminals()) {
                VoltageLevel.NodeBreakerView nbv = t.getVoltageLevel().getNodeBreakerView();
                int node = t.getNodeBreakerView().getNode();

                boolean[] seen = new boolean[]{false, false, false}; // disc, brk, bbs
                nbv.traverse(node, (n1, sw, n2) -> {
                    if (sw != null) {
                        if (sw.getKind() == SwitchKind.DISCONNECTOR) {
                            seen[0] = true;
                        } else if (sw.getKind() == SwitchKind.BREAKER) {
                            seen[1] = true;
                        }
                    }
                    Terminal term = nbv.getOptionalTerminal(n2).orElse(null);
                    if (term != null && term.getConnectable() instanceof BusbarSection) {
                        seen[2] = true;
                        return TraverseResult.TERMINATE_TRAVERSER;
                    }
                    return TraverseResult.CONTINUE;
                });

                String id = c.getId();
                assertTrue(seen[2], "feeder of " + id + " should reach a busbar section");
                assertTrue(seen[0], "feeder of " + id + " should include a disconnector");
                assertTrue(seen[1], "feeder of " + id + " should include a breaker");
            }
        }
    }
}
