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
     * CreateFeederBay must have added exactly 2 new switches (disconnector + breaker)
     * for the equivalent generator's feeder bay.
     */
    @Test
    void testNodeBreakerSwitchesCreated() {
        VoltageLevel hvVl = network.getVoltageLevel("VL_HV");
        long beforeCount = hvVl.getNodeBreakerView().getSwitchStream().count();

        EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        long afterCount = hvVl.getNodeBreakerView().getSwitchStream().count();
        assertEquals(beforeCount + 2, afterCount,
                "CreateFeederBay must add exactly 2 switches (disconnector + breaker)");
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
