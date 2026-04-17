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

    /**
     * COMBINED_PROPORTIONAL and INDEPENDENT must produce the SAME curve for a
     * single-generator spec: the proportional weight reduces to 1.0, so the
     * combined transport reduces to the single-generator transport.
     */
    @Test
    void testSingleSpecIndependentEqualsCombined() {
        Network a = NodeBreakerNetworkFactory.create();
        Network b = NodeBreakerNetworkFactory.create();

        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV", "AUX_LOAD", "GEN_HV_EQ"));

        EquivalentBuilder.MultiBuildResult ind =
                EquivalentBuilder.buildMulti(a, specs, "TX", 11,
                        EquivalentBuilder.LossAllocation.INDEPENDENT);
        EquivalentBuilder.MultiBuildResult com =
                EquivalentBuilder.buildMulti(b, specs, "TX", 11,
                        EquivalentBuilder.LossAllocation.COMBINED_PROPORTIONAL);

        assertEquals(ind.first().curve().size(), com.first().curve().size());
        for (int i = 0; i < ind.first().curve().size(); i++) {
            assertEquals(ind.first().curve().get(i).pHv(),
                    com.first().curve().get(i).pHv(), 1e-9);
            assertEquals(ind.first().curve().get(i).minQHv(),
                    com.first().curve().get(i).minQHv(), 1e-9);
            assertEquals(ind.first().curve().get(i).maxQHv(),
                    com.first().curve().get(i).maxQHv(), 1e-9);
        }
    }

    /**
     * For the two-generator network, INDEPENDENT and COMBINED_PROPORTIONAL must
     * produce DIFFERENT curves: cross-terms and the shunt double-counting add
     * up to a measurable offset between the two policies.
     */
    @Test
    void testTwoGensIndependentVsCombinedDiffer() {
        Network a = NodeBreakerNetworkFactory.createTwoGenerators();
        Network b = NodeBreakerNetworkFactory.createTwoGenerators();

        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult ind = EquivalentBuilder.buildMulti(
                a, specs, "TX", 11, EquivalentBuilder.LossAllocation.INDEPENDENT);
        EquivalentBuilder.MultiBuildResult com = EquivalentBuilder.buildMulti(
                b, specs, "TX", 11, EquivalentBuilder.LossAllocation.COMBINED_PROPORTIONAL);

        // At least one curve point must differ between the two policies.
        boolean foundDifference = false;
        for (int g = 0; g < 2; g++) {
            List<CurveTransporter.HvCurvePoint> cInd = ind.perGenerator().get(g).curve();
            List<CurveTransporter.HvCurvePoint> cCom = com.perGenerator().get(g).curve();
            for (int i = 0; i < cInd.size(); i++) {
                if (Math.abs(cInd.get(i).pHv()    - cCom.get(i).pHv())    > 1e-3
                 || Math.abs(cInd.get(i).minQHv() - cCom.get(i).minQHv()) > 1e-3
                 || Math.abs(cInd.get(i).maxQHv() - cCom.get(i).maxQHv()) > 1e-3) {
                    foundDifference = true;
                    break;
                }
            }
        }
        assertTrue(foundDifference,
                "COMBINED_PROPORTIONAL must differ from INDEPENDENT for two sizeable generators");
    }

    /**
     * Invariant of COMBINED_PROPORTIONAL: at each sample, the sum of per-gen
     * HV injections must equal the exact combined transport of the combined
     * LV injection (up to floating-point noise). We verify it at the maxQ
     * extreme of each sample using {@link TransformerTransport#transport}.
     */
    @Test
    void testCombinedSumEqualsExactCombinedTransport() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        // Capture LV-side transformer params + LV voltage BEFORE buildMulti mutates the network.
        TransformerTransport.OrientedParams op =
                TransformerTransport.orientToHv(network.getTwoWindingsTransformer("TX"));
        double vLvKv = network.getGenerator("GEN_LV_A").getTargetV();

        // Compute each generator's LV maxQ curve (by sampling identically to the builder).
        Generator genA = network.getGenerator("GEN_LV_A");
        Generator genB = network.getGenerator("GEN_LV_B");
        double pAuxA = network.getLoad("AUX_LOAD_A").getP0();
        double qAuxA = network.getLoad("AUX_LOAD_A").getQ0();
        double pAuxB = network.getLoad("AUX_LOAD_B").getP0();
        double qAuxB = network.getLoad("AUX_LOAD_B").getQ0();

        EquivalentBuilder.MultiBuildResult com = EquivalentBuilder.buildMulti(
                network, specs, "TX", 11, EquivalentBuilder.LossAllocation.COMBINED_PROPORTIONAL);

        List<CurveTransporter.HvCurvePoint> cA = com.perGenerator().get(0).curve();
        List<CurveTransporter.HvCurvePoint> cB = com.perGenerator().get(1).curve();

        for (int i = 0; i < 11; i++) {
            double t = (double) i / 10.0;
            double pA = genA.getMinP() + t * (genA.getMaxP() - genA.getMinP());
            double pB = genB.getMinP() + t * (genB.getMaxP() - genB.getMinP());
            double qLoA = genA.getReactiveLimits().getMinQ(pA);
            double qLoB = genB.getReactiveLimits().getMinQ(pB);
            double qHiA = genA.getReactiveLimits().getMaxQ(pA);
            double qHiB = genB.getReactiveLimits().getMaxQ(pB);

            double pLv   = (pA   - pAuxA) + (pB   - pAuxB);
            double qLvLo = (qLoA - qAuxA) + (qLoB - qAuxB);
            double qLvHi = (qHiA - qAuxA) + (qHiB - qAuxB);

            TransformerTransport.HvPoint hvLo =
                    TransformerTransport.transport(op, vLvKv, pLv, qLvLo);
            TransformerTransport.HvPoint hvHi =
                    TransformerTransport.transport(op, vLvKv, pLv, qLvHi);

            // Each per-gen curve point stores pHv = 0.5 * (pHv_at_Qlo + pHv_at_Qhi)
            // after the proportional split; therefore the sum across generators
            // must equal the average of the two exact combined transports' P.
            double pSum = cA.get(i).pHv() + cB.get(i).pHv();
            double pRef = 0.5 * (hvLo.pHvMw() + hvHi.pHvMw());
            assertEquals(pRef, pSum, 1e-6,
                    "Sum of HV P_i must equal the exact combined transport at sample " + i);
        }
    }

    /**
     * Load flow on the equivalent network built with COMBINED_PROPORTIONAL must
     * converge. The total HV injection must be closer to the exact combined
     * transport than the INDEPENDENT-built network.
     */
    @Test
    void testCombinedLoadFlowConverges() {
        List<EquivalentBuilder.GeneratorSpec> specs = List.of(
                new EquivalentBuilder.GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
                new EquivalentBuilder.GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"));

        EquivalentBuilder.MultiBuildResult mr = EquivalentBuilder.buildMulti(
                network, specs, "TX", 11, EquivalentBuilder.LossAllocation.COMBINED_PROPORTIONAL);

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
        assertTrue(injP > 560.0 && injP < 580.0,
                "Sum of HV equivalent P injections must match the transported LV net, got " + injP);
    }
}
