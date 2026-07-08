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
    void rejectsNetworkWithoutBusBreakerVoltageLevel() {
        Network net = ExtendedIeee14Factory.create();
        InPlaceNodeBreakerConverter.convert(net);       // now all node-breaker
        assertThrows(IllegalArgumentException.class,
                () -> InPlaceNodeBreakerConverter.convert(net));
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
