package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-only modeling (reachable in powsybl-core / OpenLoadFlow but not in
 * pypowsybl 1.16.1): each is added on a solved base case and the flow still
 * converges with the matching outer loop on.
 */
class JavaOnlyModelingTest {

    private static LoadFlowParameters withOlf(Consumer tweak) {
        LoadFlowParameters p = new LoadFlowParameters()
                .setDistributedSlack(true).setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        tweak.apply(OpenLoadFlowParameters.create(p));
        return p;
    }

    private interface Consumer {
        void apply(OpenLoadFlowParameters p);
    }

    @Test
    void generatorRemoteReactivePowerControl() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        assertEquals(1, JavaOnlyModeling.addGeneratorRemoteReactivePowerControl(net, 1).generators());
        Generator g = net.getGeneratorStream()
                .filter(x -> x.getExtension(RemoteReactivePowerControl.class) != null)
                .findFirst().orElseThrow();
        RemoteReactivePowerControl ext = g.getExtension(RemoteReactivePowerControl.class);
        assertTrue(ext.isEnabled());
        assertNotNull(ext.getRegulatingTerminal());
        assertTrue(LoadFlow.run(net, withOlf(p -> p.setGeneratorReactivePowerRemoteControl(true)))
                .isFullyConverged());
    }

    @Test
    void transformerReactivePowerControl() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults().withRunLoadFlow(false));
        assertEquals(1, JavaOnlyModeling.addTransformerReactivePowerControl(net, 1).transformers());
        RatioTapChanger rtc = null;
        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            if (tx.getRatioTapChanger() != null
                    && tx.getRatioTapChanger().getRegulationMode()
                    == RatioTapChanger.RegulationMode.REACTIVE_POWER) {
                rtc = tx.getRatioTapChanger();
            }
        }
        assertNotNull(rtc);
        assertTrue(rtc.isRegulating());
        assertTrue(LoadFlow.run(net, withOlf(p -> p.setTransformerReactivePowerControl(true)))
                .isFullyConverged());
    }

    @Test
    void automationSystems() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        assertEquals(1, JavaOnlyModeling.addAutomationSystems(net, 1).systems());
        assertNotNull(net.getOverloadManagementSystem("SYN_OMS_0"));
        assertTrue(LoadFlow.run(net, withOlf(p -> p.setSimulateAutomationSystems(true)))
                .isFullyConverged());
    }
}
