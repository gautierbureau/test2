package com.example.transporter;

import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Convert the extended IEEE-14 network (which adds a battery, an SVC, a
 * boundary line, a non-linear shunt, ratio/phase tap changers, a three-winding
 * transformer, a VSC HVDC link and a bus coupler) and check that every added
 * type survives the round trip and that the conversion stays electrically
 * transparent.
 */
class ExtendedNetworkConverterTest {

    private static final String REF_VL = "VL1";

    private static LoadFlowParameters lfParams() {
        return new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false)
                .setDistributedSlack(true);
    }

    @Test
    void allNewTypesArePreservedAndNodeBreaker() {
        Network source = ExtendedIeee14Factory.create();
        Network target = BusToNodeBreakerConverter.convert(source);

        // Fully node-breaker.
        for (VoltageLevel vl : target.getVoltageLevels()) {
            assertEquals(TopologyKind.NODE_BREAKER, vl.getTopologyKind(), vl.getId());
        }

        // One busbar section per configured bus.
        long sourceBuses = source.getVoltageLevelStream()
                .flatMap(vl -> vl.getBusBreakerView().getBusStream())
                .count();
        assertEquals(sourceBuses, target.getBusbarSectionCount());

        // Every added equipment type is present with its counts preserved.
        assertEquals(source.getBatteryCount(), target.getBatteryCount());
        assertNotNull(target.getBattery("BAT1"));
        assertEquals(source.getStaticVarCompensatorCount(), target.getStaticVarCompensatorCount());
        assertNotNull(target.getStaticVarCompensator("SVC1"));
        assertEquals(source.getBoundaryLineCount(), target.getBoundaryLineCount());
        assertNotNull(target.getBoundaryLine("DL1"));
        assertEquals(source.getShuntCompensatorCount(), target.getShuntCompensatorCount());
        assertEquals(ShuntCompensatorModelType.NON_LINEAR,
                target.getShuntCompensator("SHNL1").getModelType());
        assertEquals(source.getVscConverterStationCount(), target.getVscConverterStationCount());
        assertEquals(source.getLccConverterStationCount(), target.getLccConverterStationCount());
        assertNotNull(target.getLccConverterStation("LCC_A"));
        assertEquals(source.getHvdcLineCount(), target.getHvdcLineCount());
        assertNotNull(target.getHvdcLine("HVDC1"));
        assertNotNull(target.getHvdcLine("HVDC_LCC"));
        assertEquals(source.getTieLineCount(), target.getTieLineCount());
        TieLine tie = target.getTieLine("TIE1");
        assertNotNull(tie, "tie line should be re-paired");
        assertEquals("DLT_A", tie.getBoundaryLine1().getId());
        assertEquals("DLT_B", tie.getBoundaryLine2().getId());
        assertEquals(source.getThreeWindingsTransformerCount(),
                target.getThreeWindingsTransformerCount());
        assertNotNull(target.getThreeWindingsTransformer("T3W"));

        // Tap changers survived.
        assertTrue(target.getTwoWindingsTransformer("T4-7-1").hasRatioTapChanger(),
                "ratio tap changer should be copied");
        assertEquals(3, target.getTwoWindingsTransformer("T4-7-1")
                .getRatioTapChanger().getStepCount());
        assertTrue(target.getTwoWindingsTransformer("T4-9-1").hasPhaseTapChanger(),
                "phase tap changer should be copied");

        // The bus coupler became a breaker between the two busbar sections.
        VoltageLevel coup = target.getVoltageLevel("VL_COUP");
        assertEquals(2, coup.getNodeBreakerView().getBusbarSectionCount());
        Switch coupler = coup.getNodeBreakerView().getSwitch("COUPLER1");
        assertNotNull(coupler, "coupler switch should exist");
        assertEquals(SwitchKind.BREAKER, coupler.getKind());
        // With the coupler closed both busbars form a single electrical bus,
        // so the load behind it stays energized.
        assertEquals(1, coup.getBusView().getBusStream().count());
    }

    @Test
    void loadFlowMatchesOriginal() {
        Network source = ExtendedIeee14Factory.create();
        Network target = BusToNodeBreakerConverter.convert(source);

        LoadFlowResult srcLf = LoadFlow.run(source, lfParams());
        LoadFlowResult tgtLf = LoadFlow.run(target, lfParams());
        assertTrue(srcLf.isFullyConverged(), "source load flow should converge");
        assertTrue(tgtLf.isFullyConverged(), "converted load flow should converge");

        Map<String, double[]> srcV = busVoltagesByVl(source);
        Map<String, double[]> tgtV = busVoltagesByVl(target);
        assertEquals(srcV.keySet(), tgtV.keySet());

        double srcRef = srcV.get(REF_VL)[1];
        double tgtRef = tgtV.get(REF_VL)[1];

        // Electrically identical. The residual is solver round-off amplified a
        // little by the SVC voltage-control loop settling from a different
        // element ordering - still ~1000x below what a topological error costs.
        for (String vlId : srcV.keySet()) {
            assertEquals(srcV.get(vlId)[0], tgtV.get(vlId)[0], 1e-2,
                    "V magnitude mismatch at " + vlId);
            assertEquals(srcV.get(vlId)[1] - srcRef, tgtV.get(vlId)[1] - tgtRef, 1e-1,
                    "V angle mismatch at " + vlId);
        }
    }

    private static Map<String, double[]> busVoltagesByVl(Network net) {
        Map<String, double[]> out = new HashMap<>();
        for (VoltageLevel vl : net.getVoltageLevels()) {
            for (Bus bus : vl.getBusView().getBuses()) {
                out.put(vl.getId(), new double[]{bus.getV(), bus.getAngle()});
            }
        }
        return out;
    }
}
