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
