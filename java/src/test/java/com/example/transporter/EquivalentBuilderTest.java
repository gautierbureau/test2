package com.example.transporter;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EquivalentBuilder}.
 *
 * The test network is loaded from the bundled resource file
 * (java/src/main/resources/test_network.xiidm, exported by the Python script).
 * The equivalent is built and validated by running an AC load flow, mirroring
 * the validation done in transport_curve.py.
 */
class EquivalentBuilderTest {

    private static final double TOLERANCE_MW = 0.01;

    private Network network;

    @BeforeEach
    void loadNetwork() throws Exception {
        URL url = getClass().getClassLoader().getResource("test_network.xiidm");
        assertNotNull(url, "test_network.xiidm not found on classpath");
        network = Network.read(Path.of(url.toURI()));
    }

    /** Original LV equipment (generator, aux load, transformer) must be present. */
    @Test
    void testOriginalEquipmentPresent() {
        assertNotNull(network.getGenerator("GEN_LV"));
        assertNotNull(network.getLoad("AUX_LOAD"));
        assertNotNull(network.getTwoWindingsTransformer("TX"));
    }

    /**
     * After building the equivalent:
     *  - LV equipment is removed
     *  - New HV generator exists with the correct curve
     *  - Curve has the right number of points and is strictly increasing in P
     */
    @Test
    void testEquivalentStructure() {
        EquivalentBuilder.BuildResult result =
                EquivalentBuilder.build(network, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        // LV-side equipment removed
        assertNull(network.getGenerator("GEN_LV"),           "Original generator must be removed");
        assertNull(network.getLoad("AUX_LOAD"),              "Auxiliary load must be removed");
        assertNull(network.getTwoWindingsTransformer("TX"),  "Transformer must be removed");

        // New HV generator present
        Generator eqGen = network.getGenerator("GEN_HV_EQ");
        assertNotNull(eqGen, "Equivalent generator must exist");

        // Curve structure
        List<CurveTransporter.HvCurvePoint> curve = result.curve();
        assertEquals(11, curve.size(), "Curve must have exactly 11 points");

        for (int i = 1; i < curve.size(); i++) {
            assertTrue(curve.get(i).pHv() > curve.get(i - 1).pHv(),
                    "Curve P must be strictly increasing at index " + i);
        }
    }

    /**
     * Verify the first and last transported curve points against the Python
     * reference output (n_samples=11, V_lv=20.5 kV).
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
     * The equivalent network must converge under an AC load flow.
     *
     * After convergence the equivalent generator's P injection must match
     * the transported operating point within 0.1 MW (accounting for the fact
     * that the target was transported from the generator's pre-LF setpoint).
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
                "Load flow must converge on the equivalent network");

        // The equivalent generator target P was transported from 400 MW → ≈383.68 MW
        Generator eqGen = result.equivalentGenerator();
        double injP = -eqGen.getTerminal().getP(); // generator convention: -p = injected
        assertEquals(383.680, injP, 0.5, "HV generator P injection after LF");
    }

    /** Missing generator ID must throw IllegalArgumentException. */
    @Test
    void testMissingGeneratorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.build(network, "NONEXISTENT", "TX", "AUX_LOAD", "GEN_HV_EQ", 11));
    }

    /** Missing transformer ID must throw IllegalArgumentException. */
    @Test
    void testMissingTransformerThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.build(network, "GEN_LV", "NONEXISTENT", "AUX_LOAD", "GEN_HV_EQ", 11));
    }
}
