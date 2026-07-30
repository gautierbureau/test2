package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java counterpart of {@code test_add_remote_voltage_control.py}: deporting a
 * generator behind a GSU transformer makes it regulate the HV bus remotely, and
 * the network still converges.
 */
class RemoteVoltageControlTest {

    private static LoadFlowParameters dc() {
        return new LoadFlowParameters()
                .setDistributedSlack(true)
                .setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
    }

    @Test
    void deportsGeneratorsBehindGsuWithRemoteControl() {
        Network net = BusToNodeBreakerConverter.convert(IeeeCdfNetworkFactory.create14());
        // Three generators sit on 135 kV voltage levels (B1/B2/B3).
        RemoteVoltageControl.DeportResult r =
                RemoteVoltageControl.deportGenerators(net, 2, 100.0, 20.0, 0.06);
        assertEquals(3, r.eligible());
        assertEquals(2, r.deported());
        assertEquals(0, r.skipped());

        Network out = r.network();
        // The deported generator now lives on a new 20 kV bus behind its GSU.
        assertNotNull(out.getVoltageLevel("B1-G_GSU_VL"));
        assertNotNull(out.getTwoWindingsTransformer("B1-G_GSU_TX"));
        Generator g = out.getGenerator("B1-G");
        assertNotNull(g);
        assertEquals("B1-G_GSU_VL", g.getTerminal().getVoltageLevel().getId());
        assertTrue(g.isVoltageRegulatorOn());
        // It regulates a bus in a different (the HV) voltage level: remote control.
        assertNotEquals(g.getTerminal().getVoltageLevel().getId(),
                g.getRegulatingTerminal().getVoltageLevel().getId());
        // The result still converges (that is the loop's invariant).
        assertTrue(LoadFlow.run(out, dc()).isFullyConverged());
    }

    @Test
    void noEligibleGeneratorsIsNoop() {
        Network net = BusToNodeBreakerConverter.convert(IeeeCdfNetworkFactory.create14());
        // Threshold above every voltage level: nothing to deport.
        RemoteVoltageControl.DeportResult r =
                RemoteVoltageControl.deportGenerators(net, null, 1000.0, 20.0, 0.06);
        assertEquals(0, r.eligible());
        assertEquals(0, r.deported());
    }
}
