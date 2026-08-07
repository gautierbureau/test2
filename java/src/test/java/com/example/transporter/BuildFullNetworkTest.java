package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.extensions.BusbarSectionPosition;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the full-network build pipeline (Java port of
 * {@code build_full_network.py}) on the extended IEEE-14 case.
 */
class BuildFullNetworkTest {

    private static LoadFlowParameters dc() {
        return new LoadFlowParameters()
                .setDistributedSlack(true).setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
    }

    @Test
    void buildFullNodeBreakerPopulatesAndConverges() {
        Network net = BuildFullNetwork.buildFull(ExtendedIeee14Factory.create(), true);

        // Node-breaker, with busbar-section positions set by the converter.
        assertTrue(net.getBusbarSectionCount() > 0);
        var bbs = net.getBusbarSections().iterator().next();
        assertEquals(TopologyKind.NODE_BREAKER, bbs.getTerminal().getVoltageLevel().getTopologyKind());
        assertNotNull(bbs.getExtension(BusbarSectionPosition.class));

        // Completions populated: curves, short circuit, load detail, properties.
        Generator g = net.getGenerators().iterator().next();
        assertNotNull(g.getExtension(com.powsybl.iidm.network.extensions.GeneratorShortCircuit.class));
        assertTrue(net.getLoads().iterator().next()
                .getExtension(com.powsybl.iidm.network.extensions.LoadDetail.class) != null);
        assertTrue(net.getVoltageLevels().iterator().next().getPropertyNames().contains("voltage_class"));

        // The finished network converges (DC init).
        assertTrue(LoadFlow.run(net, dc()).isFullyConverged());
    }

    @Test
    void builtinCaseNamesResolveToTheIeeeNetworks() {
        assertEquals(57, BuildFullNetwork.resolveCase("ieee57").getBusBreakerView().getBusCount());
        assertEquals(118, BuildFullNetwork.resolveCase("ieee118").getBusBreakerView().getBusCount());
    }

    @Test
    void buildFullOnBuiltinIeee57Converges() {
        Network net = BuildFullNetwork.buildFull(BuildFullNetwork.resolveCase("ieee57"), true);
        assertTrue(net.getBusbarSectionCount() >= 57);
        assertTrue(net.getBatteryCount() > 0);
        assertTrue(net.getHvdcLineCount() > 0);
        assertTrue(LoadFlow.run(net, dc()).isFullyConverged());
    }

    @Test
    void converterSetsBusbarSectionPositions() {
        Network net = BusToNodeBreakerConverter.convert(IeeeCdfNetworkFactory.create14());
        for (var bbs : net.getBusbarSections()) {
            BusbarSectionPosition pos = bbs.getExtension(BusbarSectionPosition.class);
            assertNotNull(pos);
            assertEquals(1, pos.getBusbarIndex());
            assertTrue(pos.getSectionIndex() >= 1);
        }
    }
}
