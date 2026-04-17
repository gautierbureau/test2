package com.example.transporter;

import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EquivalentBuilder} with node-breaker topology on
 * the HV side.
 *
 * The test network is built programmatically by {@link NodeBreakerNetworkFactory}
 * and is electrically identical to {@code test_network.xiidm}, so transported
 * curve values and load-flow results must match the bus-breaker reference.
 */
class EquivalentBuilderNodeBreakerTest {

    private static final double TOLERANCE_MW = 0.01;

    private Network network;

    @BeforeEach
    void buildNetwork() {
        network = NodeBreakerNetworkFactory.create();
    }

    /** HV voltage level must use NODE_BREAKER and contain a busbar section. */
    @Test
    void testNetworkTopologyKind() {
        VoltageLevel hvVl = network.getVoltageLevel("VL_HV");
        assertNotNull(hvVl);
        assertEquals(TopologyKind.NODE_BREAKER, hvVl.getTopologyKind());
        assertTrue(hvVl.getNodeBreakerView().getBusbarSectionStream().findAny().isPresent(),
                "VL_HV must contain a busbar section");
    }

    /** All original LV equipment must be present before the build. */
    @Test
    void testOriginalEquipmentPresent() {
        assertNotNull(network.getGenerator("GEN_LV"));
        assertNotNull(network.getLoad("AUX_LOAD"));
        assertNotNull(network.getTwoWindingsTransformer("TX"));
    }

    /**
     * After building the equivalent:
     *  - LV equipment is removed
     *  - New HV generator exists inside VL_HV
     *  - Curve has the right number of points and strictly increasing P
     */
    @Test
    void testEquivalentStructure() {
        EquivalentBuilder.BuildResult result =
                EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        assertNull(network.getGenerator("GEN_LV"),          "Original generator must be removed");
        assertNull(network.getLoad("AUX_LOAD"),             "Auxiliary load must be removed");
        assertNull(network.getTwoWindingsTransformer("TX"), "Transformer must be removed");

        Generator eqGen = network.getGenerator("GEN_HV_EQ");
        assertNotNull(eqGen, "Equivalent generator must exist");
        assertEquals("VL_HV", eqGen.getTerminal().getVoltageLevel().getId(),
                "Generator must be in VL_HV");

        List<CurveTransporter.HvCurvePoint> curve = result.curve();
        assertEquals(11, curve.size(), "Curve must have 11 points");
        for (int i = 1; i < curve.size(); i++) {
            assertTrue(curve.get(i).pHv() > curve.get(i - 1).pHv(),
                    "Curve P must be strictly increasing at index " + i);
        }
    }

    /**
     * RemoveFeederBay must clean up the TX feeder bay switches from VL_HV, and
     * CreateFeederBay must add a new disconnector + breaker for the equivalent
     * generator. Net switch count stays the same (-2 from TX bay, +2 for EQ_GEN bay).
     */
    @Test
    void testNodeBreakerSwitchesCreated() {
        VoltageLevel hvVl = network.getVoltageLevel("VL_HV");
        long beforeCount = hvVl.getNodeBreakerView().getSwitchStream().count();

        EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        // TX HV feeder bay removed by RemoveFeederBay
        assertNull(network.getSwitch("DISC_TX"), "TX HV disconnector must be removed");
        assertNull(network.getSwitch("BRK_TX"),  "TX HV breaker must be removed");

        // Net switch count: -2 (TX bay removed) +2 (EQ_GEN bay added) = unchanged
        long afterCount = hvVl.getNodeBreakerView().getSwitchStream().count();
        assertEquals(beforeCount, afterCount, "Net switch count in VL_HV must be unchanged");

        // Equivalent generator is reachable via the bus view (connected through bay)
        assertNotNull(network.getGenerator("GEN_HV_EQ").getTerminal().getBusBreakerView().getBus(),
                "Equivalent generator must be connected to a bus");
    }

    /**
     * When the LV voltage level is also in node-breaker topology, RemoveFeederBay
     * must clean up all feeder bay switches (GEN_LV bay + AUX_LOAD bay + TX LV bay),
     * leaving only the busbar section in VL_LV.
     */
    @Test
    void testNodeBreakerLvFeederBaysRemoved() {
        Network nb = NodeBreakerNetworkFactory.createWithNodeBreakerLv();
        VoltageLevel lvVl = nb.getVoltageLevel("VL_LV");

        assertEquals(6, lvVl.getNodeBreakerView().getSwitchCount(),
                "VL_LV must start with 6 switches (2 per feeder bay × 3 feeders)");

        EquivalentBuilder.build(nb, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        assertEquals(0, lvVl.getNodeBreakerView().getSwitchCount(),
                "All LV feeder bay switches must be removed by RemoveFeederBay");
        assertNull(nb.getSwitch("DISC_GEN_LV"), "GEN_LV disconnector must be removed");
        assertNull(nb.getSwitch("BRK_GEN_LV"),  "GEN_LV breaker must be removed");
        assertNull(nb.getSwitch("DISC_AUX_LV"), "AUX_LOAD disconnector must be removed");
        assertNull(nb.getSwitch("BRK_AUX_LV"),  "AUX_LOAD breaker must be removed");
        assertNull(nb.getSwitch("DISC_TX_LV"),  "TX LV disconnector must be removed");
        assertNull(nb.getSwitch("BRK_TX_LV"),   "TX LV breaker must be removed");
    }

    /**
     * Transported curve values must match the bus-breaker reference (same
     * electrical data, n_samples=11, V_lv=20.5 kV).
     */
    @Test
    void testCurveValues() {
        EquivalentBuilder.BuildResult result =
                EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        List<CurveTransporter.HvCurvePoint> curve = result.curve();

        CurveTransporter.HvCurvePoint first = curve.get(0);
        assertEquals(-15.524,  first.pHv(),    TOLERANCE_MW, "First P_hv");
        assertEquals(-171.645, first.minQHv(), TOLERANCE_MW, "First Qmin_hv");
        assertEquals( 130.224, first.maxQHv(), TOLERANCE_MW, "First Qmax_hv");

        CurveTransporter.HvCurvePoint last = curve.get(curve.size() - 1);
        assertEquals( 483.052, last.pHv(),    TOLERANCE_MW, "Last P_hv");
        assertEquals(-163.709, last.minQHv(), TOLERANCE_MW, "Last Qmin_hv");
        assertEquals(  56.822, last.maxQHv(), TOLERANCE_MW, "Last Qmax_hv");
    }

    /**
     * The equivalent node-breaker network must converge under an AC load flow,
     * and the equivalent generator P injection must match the transported
     * operating point within 0.5 MW.
     */
    @Test
    void testLoadFlowConverges() {
        EquivalentBuilder.BuildResult result =
                EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        LoadFlowParameters params = new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false)
                .setDistributedSlack(false);

        LoadFlowResult lfResult = LoadFlow.run(network, params);

        assertEquals(LoadFlowResult.Status.FULLY_CONVERGED, lfResult.getStatus(),
                "Load flow must converge on the equivalent node-breaker network");

        Generator eqGen = result.equivalentGenerator();
        double injP = -eqGen.getTerminal().getP(); // generator convention: -p = injected
        assertEquals(383.680, injP, 0.5, "HV generator P injection after LF");
    }
}
