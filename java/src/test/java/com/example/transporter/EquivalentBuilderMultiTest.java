package com.example.transporter;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EquivalentBuilder#buildMulti} with two generators
 * (GEN_LV_A + GEN_LV_B, each with its own aux load) sharing a single transformer.
 */
class EquivalentBuilderMultiTest {

    private static final double TOLERANCE_MW = 0.01;

    private Network network;

    @BeforeEach
    void buildNetwork() {
        network = NodeBreakerNetworkFactory.createTwoGenerators();
    }

    @Test
    void testOriginalEquipmentPresent() {
        assertNotNull(network.getGenerator("GEN_LV_A"));
        assertNotNull(network.getGenerator("GEN_LV_B"));
        assertNotNull(network.getLoad("AUX_LOAD_A"));
        assertNotNull(network.getLoad("AUX_LOAD_B"));
        assertNotNull(network.getTwoWindingsTransformer("TX"));
    }

    /**
     * After buildMulti:
     *  - Both LV generators, both aux loads and the transformer are gone
     *  - Two new HV equivalent generators exist on VL_HV
     *  - Each carries a curve with the requested number of points, strictly
     *    increasing in P
     */
    @Test
    void testEquivalentStructure() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult mr =
                EquivalentBuilder.buildMulti(network, specs, "TX", 11);

        assertNull(network.getGenerator("GEN_LV_A"));
        assertNull(network.getGenerator("GEN_LV_B"));
        assertNull(network.getLoad("AUX_LOAD_A"));
        assertNull(network.getLoad("AUX_LOAD_B"));
        assertNull(network.getTwoWindingsTransformer("TX"));

        Generator eqA = network.getGenerator("EQ_GEN_HV_A");
        Generator eqB = network.getGenerator("EQ_GEN_HV_B");
        assertNotNull(eqA);
        assertNotNull(eqB);
        assertEquals("VL_HV", eqA.getTerminal().getVoltageLevel().getId());
        assertEquals("VL_HV", eqB.getTerminal().getVoltageLevel().getId());

        assertEquals(2, mr.perGenerator().size());
        for (EquivalentBuilder.BuildResult r : mr.perGenerator()) {
            List<CurveTransporter.HvCurvePoint> curve = r.curve();
            assertEquals(11, curve.size());
            for (int i = 1; i < curve.size(); i++) {
                assertTrue(curve.get(i).pHv() > curve.get(i - 1).pHv(),
                        "Curve must be strictly increasing in P for " + r.equivalentGenerator().getId());
            }
        }
    }

    /**
     * GEN_LV_A uses the same inputs as the single-generator reference test
     * (P range 0..500, curve points, aux=(15,5), V_lv=20.5 kV), so its transported
     * curve must match the known Python reference values exactly.
     */
    @Test
    void testGeneratorACurveMatchesSingleGenReference() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult mr =
                EquivalentBuilder.buildMulti(network, specs, "TX", 11);

        List<CurveTransporter.HvCurvePoint> curveA = mr.perGenerator().get(0).curve();
        CurveTransporter.HvCurvePoint first = curveA.get(0);
        assertEquals(-15.524,  first.pHv(),    TOLERANCE_MW);
        assertEquals(-171.645, first.minQHv(), TOLERANCE_MW);
        assertEquals( 130.224, first.maxQHv(), TOLERANCE_MW);

        CurveTransporter.HvCurvePoint last = curveA.get(curveA.size() - 1);
        assertEquals( 483.052, last.pHv(),    TOLERANCE_MW);
        assertEquals(-163.709, last.minQHv(), TOLERANCE_MW);
        assertEquals(  56.822, last.maxQHv(), TOLERANCE_MW);
    }

    /**
     * GEN_LV_B has its own curve and aux load, so its transported curve must be
     * clearly distinct from GEN_LV_A's (different P range, different Q bounds).
     */
    @Test
    void testGeneratorBCurveIsDistinct() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult mr =
                EquivalentBuilder.buildMulti(network, specs, "TX", 11);

        List<CurveTransporter.HvCurvePoint> curveB = mr.perGenerator().get(1).curve();

        // P_max on GEN_LV_B is 400 MW (vs 500 for A), so last point must be strictly smaller than A's.
        double pMaxB = curveB.get(curveB.size() - 1).pHv();
        assertTrue(pMaxB < 400.0,
                "GEN_LV_B last HV P must reflect its 400 MW MaxP minus aux + losses, got " + pMaxB);
        assertTrue(pMaxB > 380.0,
                "GEN_LV_B last HV P should be close to 400 MW minus 8 MW aux minus small losses, got " + pMaxB);

        // P_min on GEN_LV_B is 0; with aux = -8 MW net inj, HV side P must be negative and small.
        double pMinB = curveB.get(0).pHv();
        assertTrue(pMinB < 0.0, "With P=0 and aux=8 MW, HV P must be negative, got " + pMinB);
        assertTrue(pMinB > -15.0, "HV P for P=0 should be close to -8 MW minus losses, got " + pMinB);
    }

    /**
     * Two new HV feeder bays are created (one per equivalent generator), with
     * distinct disconnectors and breakers. TX feeder bay is removed.
     */
    @Test
    void testTwoFeederBaysCreated() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        long beforeCount = network.getVoltageLevel("VL_HV").getNodeBreakerView().getSwitchStream().count();

        EquivalentBuilder.buildMulti(network, specs, "TX", 11);

        // TX bay removed (-2 switches), two new generator bays added (+4 switches): net +2.
        long afterCount = network.getVoltageLevel("VL_HV").getNodeBreakerView().getSwitchStream().count();
        assertEquals(beforeCount + 2, afterCount,
                "Net switch count in VL_HV: -2 for TX bay, +4 for two new generator bays");

        assertNull(network.getSwitch("DISC_TX"));
        assertNull(network.getSwitch("BRK_TX"));

        // Both new generators are reachable via the bus view.
        assertNotNull(network.getGenerator("EQ_GEN_HV_A").getTerminal().getBusBreakerView().getBus());
        assertNotNull(network.getGenerator("EQ_GEN_HV_B").getTerminal().getBusBreakerView().getBus());
    }

    /**
     * Equivalent network with two HV generators must converge under AC load flow,
     * and the sum of their P injections must roughly equal the combined HV injection
     * that the two LV generators + aux loads would produce through the transformer.
     *
     * Expected sum ~= target_P_A + target_P_B - aux_A - aux_B - losses
     *             = 400 + 200 - 15 - 8 - ~2% losses
     */
    @Test
    void testLoadFlowConverges() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult mr =
                EquivalentBuilder.buildMulti(network, specs, "TX", 11);

        LoadFlowParameters params = new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false)
                .setDistributedSlack(false);
        LoadFlowResult lf = LoadFlow.run(network, params);

        assertEquals(LoadFlowResult.Status.FULLY_CONVERGED, lf.getStatus());

        double injP = 0.0;
        for (EquivalentBuilder.BuildResult r : mr.perGenerator()) {
            injP += -r.equivalentGenerator().getTerminal().getP();
        }
        // 400 + 200 - 15 - 8 = 577 MW of LV net injection -> roughly 567..576 MW at HV
        assertTrue(injP > 560.0 && injP < 580.0,
                "Sum of HV equivalent P injections must be close to the transported LV net, got " + injP);
    }

    /** A single-element specs list must behave identically to the legacy single-gen build(). */
    @Test
    void testSingleSpecIsEquivalentToLegacyBuild() {
        // Use the single-generator factory for a fair comparison.
        Network a = NodeBreakerNetworkFactory.create();
        Network b = NodeBreakerNetworkFactory.create();

        EquivalentBuilder.BuildResult legacy =
                EquivalentBuilder.build(a, "GEN_LV", "TX", "AUX_LOAD", "GEN_HV_EQ", 11);

        EquivalentBuilder.MultiBuildResult multi = EquivalentBuilder.buildMulti(b,
                List.of(new EquivalentBuilder.GeneratorSpec("GEN_LV", "AUX_LOAD", "GEN_HV_EQ")),
                "TX", 11);

        assertEquals(legacy.curve().size(), multi.first().curve().size());
        for (int i = 0; i < legacy.curve().size(); i++) {
            assertEquals(legacy.curve().get(i).pHv(),    multi.first().curve().get(i).pHv(),    1e-9);
            assertEquals(legacy.curve().get(i).minQHv(), multi.first().curve().get(i).minQHv(), 1e-9);
            assertEquals(legacy.curve().get(i).maxQHv(), multi.first().curve().get(i).maxQHv(), 1e-9);
        }
    }

    /** Duplicate aux-load IDs across specs must be rejected. */
    @Test
    void testDuplicateAuxLoadIdRejected() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_A", "EQ_GEN_HV_B"));
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.buildMulti(network, specs, "TX", 11));
    }

    /** Duplicate new-generator IDs across specs must be rejected. */
    @Test
    void testDuplicateNewIdRejected() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV"));
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.buildMulti(network, specs, "TX", 11));
    }

    /** Duplicate generator IDs across specs must be rejected. */
    @Test
    void testDuplicateGeneratorIdRejected() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_1"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_B", "EQ_GEN_HV_2"));
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.buildMulti(network, specs, "TX", 11));
    }

    /** Missing transformer ID must throw IllegalArgumentException. */
    @Test
    void testMissingTransformerThrows() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.buildMulti(network, specs, "NONEXISTENT", 11));
    }

    /** Empty specs list must throw IllegalArgumentException. */
    @Test
    void testEmptySpecsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EquivalentBuilder.buildMulti(network, List.of(), "TX", 11));
    }
}
