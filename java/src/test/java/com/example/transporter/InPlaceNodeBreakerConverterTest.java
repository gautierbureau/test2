package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The in-place converter must (a) turn every voltage level node-breaker,
 * (b) preserve everything attached to the connectables - limits, extensions,
 * properties - for free, and (c) stay electrically equivalent.
 */
class InPlaceNodeBreakerConverterTest {

    private static LoadFlowParameters lfParams() {
        return new LoadFlowParameters().setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false).setDistributedSlack(true);
    }

    @Test
    void convertsInPlacePreservingLimitsExtensionsAndData() {
        Network net = ExtendedIeee14Factory.create();

        // Attach data the rebuild converter would have to copy by hand.
        Line line = net.getLine("L1-2-1");
        for (int side = 1; side <= 2; side++) {
            OperationalLimitsGroup g = side == 1
                    ? line.newOperationalLimitsGroup1("SET_A")
                    : line.newOperationalLimitsGroup2("SET_A");
            g.newCurrentLimits().setPermanentLimit(1000.0)
                    .beginTemporaryLimit().setName("IT20min").setAcceptableDuration(1200)
                    .setValue(1100.0).endTemporaryLimit().add();
        }
        line.setSelectedOperationalLimitsGroup1("SET_A");
        line.setSelectedOperationalLimitsGroup2("SET_A");
        line.setProperty("owner", "TSO-X");
        line.addAlias("feeder-42");
        net.getGenerator("B1-G").newExtension(ActivePowerControlAdder.class)
                .withParticipate(true).withDroop(4.0).withParticipationFactor(100.0).add();

        // Reference solution (per generator bus voltage) before conversion.
        LoadFlow.run(net, lfParams());
        Map<String, Double> vBefore = generatorVoltages(net);

        int branchesBefore = net.getLineCount() + net.getTwoWindingsTransformerCount()
                + net.getThreeWindingsTransformerCount();

        Network same = InPlaceNodeBreakerConverter.convert(net);
        assertSame(net, same, "conversion is in place");

        // (a) fully node-breaker.
        for (VoltageLevel vl : net.getVoltageLevels()) {
            assertEquals(TopologyKind.NODE_BREAKER, vl.getTopologyKind(), vl.getId());
        }
        // Connectable inventory intact (nothing recreated or lost).
        assertEquals(branchesBefore, net.getLineCount() + net.getTwoWindingsTransformerCount()
                + net.getThreeWindingsTransformerCount());
        assertEquals(1, net.getThreeWindingsTransformerCount());
        assertEquals(2, net.getHvdcLineCount());
        assertEquals(1, net.getTieLineCount());

        // (b) everything on the connectables survived - for free.
        Line converted = net.getLine("L1-2-1");
        assertEquals(java.util.Optional.of("SET_A"), converted.getSelectedOperationalLimitsGroupId1());
        CurrentLimits cl = converted.getOperationalLimitsGroup1("SET_A").orElseThrow()
                .getCurrentLimits().orElseThrow();
        assertEquals(1000.0, cl.getPermanentLimit(), 1e-9);
        assertEquals(1100.0, cl.getTemporaryLimit(1200).getValue(), 1e-9);
        assertEquals("TSO-X", converted.getProperty("owner"));
        assertTrue(converted.getAliases().contains("feeder-42"));
        ActivePowerControl<Generator> apc = net.getGenerator("B1-G")
                .getExtension(ActivePowerControl.class);
        assertNotNull(apc, "extension survives an in-place move");
        assertEquals(100.0, apc.getParticipationFactor(), 1e-9);

        // (c) electrically equivalent.
        LoadFlow.run(net, lfParams());
        Map<String, Double> vAfter = generatorVoltages(net);
        double maxDv = 0.0;
        for (String id : vBefore.keySet()) {
            maxDv = Math.max(maxDv, Math.abs(vBefore.get(id) - vAfter.get(id)));
        }
        assertTrue(maxDv < 1e-3, "voltages must match the original, maxDv=" + maxDv);
    }

    @Test
    void recreatesBusCouplers() {
        // The extended network has a bus coupler (B_COUP_A -- B_COUP_B in VL_COUP).
        Network net = ExtendedIeee14Factory.create();
        InPlaceNodeBreakerConverter.convert(net);
        VoltageLevel coup = net.getVoltageLevel("VL_COUP" + InPlaceNodeBreakerConverter.NB_SUFFIX);
        assertNotNull(coup);
        assertEquals(TopologyKind.NODE_BREAKER, coup.getTopologyKind());
        // Two busbar sections (one per original bus), joined by a coupling device.
        assertEquals(2, coup.getNodeBreakerView().getBusbarSectionCount());
        assertTrue(coup.getNodeBreakerView().getSwitchCount() > 0, "coupler switches expected");
        assertTrue(LoadFlow.run(net, lfParams()).isFullyConverged());
    }

    @Test
    void scalesToIeee300() {
        Network net = IeeeCdfNetworkFactory.create300();
        LoadFlow.run(net, lfParams());
        Map<String, Double> before = generatorVoltages(net);
        int connectables = (int) net.getConnectableStream().count();

        InPlaceNodeBreakerConverter.convert(net);

        for (VoltageLevel vl : net.getVoltageLevels()) {
            assertEquals(TopologyKind.NODE_BREAKER, vl.getTopologyKind(), vl.getId());
        }
        // Same connectables, plus the new busbar sections.
        assertTrue(net.getConnectableStream().count() >= connectables);
        LoadFlow.run(net, lfParams());
        Map<String, Double> after = generatorVoltages(net);
        double maxDv = 0.0;
        for (String id : before.keySet()) {
            maxDv = Math.max(maxDv, Math.abs(before.get(id) - after.get(id)));
        }
        assertTrue(maxDv < 1e-2, "IEEE-300 voltages must match, maxDv=" + maxDv);
    }

    @Test
    void sectionalizesEachBusIntoNBusbars() {
        Network net = IeeeCdfNetworkFactory.create14();
        long buses = net.getVoltageLevelStream()
                .flatMap(vl -> vl.getBusBreakerView().getBusStream()).count();
        LoadFlow.run(net, lfParams());
        Map<String, Double> before = generatorVoltages(net);

        // Policy off: busbarSectionsPerBus applies to every bus.
        InPlaceNodeBreakerConverter.convert(net, 3, false);

        assertEquals(buses * 3, net.getBusbarSectionCount());
        for (VoltageLevel vl : net.getVoltageLevels()) {
            // Closed intra-bus couplers keep each voltage level a single bus.
            assertEquals(1, vl.getBusView().getBusStream().count(), vl.getId());
        }
        LoadFlow.run(net, lfParams());
        Map<String, Double> after = generatorVoltages(net);
        double maxDv = 0.0;
        for (String id : before.keySet()) {
            maxDv = Math.max(maxDv, Math.abs(before.get(id) - after.get(id)));
        }
        assertTrue(maxDv < 1e-2, "sectionalized network must match, maxDv=" + maxDv);
    }

    @Test
    void decouplesGeneratorAndFeederSectionCounts() {
        Network net = multiUnitNetwork();   // B1: 3 generators, B2: load only

        InPlaceNodeBreakerConverter.convert(net, 5, true);

        // Generator bus -> one section per generator (3), not padded to 5.
        assertEquals(3, net.getVoltageLevel("VL1" + InPlaceNodeBreakerConverter.NB_SUFFIX)
                .getNodeBreakerView().getBusbarSectionCount());
        // Non-generator bus -> busbarSectionsPerBus.
        assertEquals(5, net.getVoltageLevel("VL2" + InPlaceNodeBreakerConverter.NB_SUFFIX)
                .getNodeBreakerView().getBusbarSectionCount());
        assertTrue(LoadFlow.run(net, lfParams()).isFullyConverged());
    }

    @Test
    void putsEachGeneratorOnItsOwnBusbar() {
        Network net = multiUnitNetwork();
        InPlaceNodeBreakerConverter.convert(net);   // default: one busbar per generator

        java.util.Set<Integer> genNodes = new java.util.HashSet<>();
        for (String g : new String[]{"GA", "GB", "GC"}) {
            genNodes.add(net.getGenerator(g).getTerminal().getNodeBreakerView().getNode());
        }
        assertEquals(3, genNodes.size(), "the three generators must land on three distinct feeders");
        assertEquals(3, net.getVoltageLevel("VL1" + InPlaceNodeBreakerConverter.NB_SUFFIX)
                .getNodeBreakerView().getBusbarSectionCount());
    }

    @Test
    void rejectsNetworkWithoutBusBreakerVoltageLevel() {
        Network net = ExtendedIeee14Factory.create();
        InPlaceNodeBreakerConverter.convert(net);       // now all node-breaker
        assertThrows(IllegalArgumentException.class,
                () -> InPlaceNodeBreakerConverter.convert(net));
    }

    /** One bus (B1) hosting three generators + a load, feeding a load bus over a line. */
    private static Network multiUnitNetwork() {
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
        return net;
    }

    private static Map<String, Double> generatorVoltages(Network net) {
        Map<String, Double> out = new HashMap<>();
        for (Generator g : net.getGenerators()) {
            Bus b = g.getTerminal().getBusView().getBus();
            if (b != null) {
                out.put(g.getId(), b.getV());
            }
        }
        return out;
    }
}
