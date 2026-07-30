package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControl;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.extensions.StandbyAutomatonAdder;
import com.powsybl.iidm.network.extensions.VoltagePerReactivePowerControl;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java counterpart of the OLF-modeling half of {@code test_complete_network.py}:
 * each control is added on top of a solved base case and the flow still
 * converges with the matching outer loop on.
 */
class VoltageControlCompleterTest {

    private static LoadFlowParameters flat() {
        return new LoadFlowParameters().setUseReactiveLimits(true).setDistributedSlack(true);
    }

    /** A solved node-breaker extended IEEE-14 (converges from a flat start). */
    private static Network solvedNodeBreaker() {
        Network net = BusToNodeBreakerConverter.convert(ExtendedIeee14Factory.create());
        assertTrue(LoadFlow.run(net, flat()).isFullyConverged());
        return net;
    }

    @Test
    void phaseControlEnablesIdleShifter() {
        Network net = solvedNodeBreaker();
        VoltageControlCompleter.PhaseControlStats stats =
                VoltageControlCompleter.addPhaseControl(net, null,
                        VoltageControlCompleter.DEFAULT_PHASE_CURRENT_MARGIN);
        assertTrue(stats.phaseShifters() >= 1);
        assertTrue(LoadFlow.run(net, flat().setPhaseShifterRegulationOn(true)).isFullyConverged());
    }

    @Test
    void transformerVoltageControlKeepsConvergingSubset() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        NetworkCompleter.addRatioTapChangers(net,
                NetworkCompleter.RatioTapConfig.defaults().withRunLoadFlow(false));
        VoltageControlCompleter.TransformerVcStats stats =
                VoltageControlCompleter.addTransformerVoltageControl(net, 3,
                        VoltageControlCompleter.DEFAULT_TVC_DEADBAND);
        assertTrue(stats.regulating() >= 1);
        assertTrue(LoadFlow.run(net, flat().setTransformerVoltageControlOn(true)).isFullyConverged());
    }

    @Test
    void shuntVoltageControlAddsSwitchableShunts() {
        Network net = solvedNodeBreaker();
        VoltageControlCompleter.ShuntVcStats stats = VoltageControlCompleter.addShuntVoltageControl(
                net, 5, VoltageControlCompleter.DEFAULT_SHUNT_SECTIONS,
                VoltageControlCompleter.DEFAULT_SHUNT_B_PER_SECTION, 1.0);
        assertTrue(stats.shunts() >= 1);
        assertNotNull(net.getShuntCompensator("SYN_SHVC_0"));
        assertTrue(net.getShuntCompensator("SYN_SHVC_0").isVoltageRegulatorOn());
        assertTrue(LoadFlow.run(net, flat().setShuntCompensatorVoltageControlOn(true)).isFullyConverged());
    }

    @Test
    void sharedVoltageControlGroupsGenerators() {
        Network net = twoGeneratorsOneVoltageLevel();
        LoadFlow.run(net, flat());
        VoltageControlCompleter.SharedVcStats stats =
                VoltageControlCompleter.addSharedVoltageControl(net, 10);
        assertEquals(1, stats.groups());
        assertEquals(1, stats.generators());
        assertTrue(LoadFlow.run(net, flat()).isFullyConverged());
    }

    @Test
    void secondaryVoltageControlTwoZonesBusBreaker() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        VoltageControlCompleter.SecondaryVcStats stats =
                VoltageControlCompleter.addSecondaryVoltageControl(net, 2, 2);
        assertEquals(2, stats.zones());
        assertEquals(4, stats.units());
        assertNotNull(net.getExtension(SecondaryVoltageControl.class));
        LoadFlowParameters p = flat();
        OpenLoadFlowParameters.create(p).setSecondaryVoltageControl(true);
        assertTrue(LoadFlow.run(net, p).isFullyConverged());
    }

    @Test
    void secondaryVoltageControlNodeBreakerUsesBusbarPilot() {
        Network net = solvedNodeBreaker();
        VoltageControlCompleter.SecondaryVcStats stats =
                VoltageControlCompleter.addSecondaryVoltageControl(net, 2, 2);
        // IEEE-14 has one generator per voltage level, so zones come from the
        // fallback (spare locals); at least one zone should form and converge.
        assertTrue(stats.zones() >= 1);
        assertNotNull(net.getExtension(SecondaryVoltageControl.class));
    }

    @Test
    void svcVoltageControlEnablesAndConverges() {
        Network net = svcNetwork();
        net.getStaticVarCompensator("SVC").setRegulating(false);
        LoadFlow.run(net, flat());
        VoltageControlCompleter.SvcVcStats stats =
                VoltageControlCompleter.addSvcVoltageControl(net, VoltageControlCompleter.DEFAULT_SVC_SLOPE);
        assertEquals(1, stats.svcs());
        StaticVarCompensator svc = net.getStaticVarCompensator("SVC");
        assertEquals(StaticVarCompensator.RegulationMode.VOLTAGE, svc.getRegulationMode());
        assertTrue(svc.isRegulating());
        assertNotNull(svc.getExtension(VoltagePerReactivePowerControl.class));
        assertTrue(LoadFlow.run(net, flat()).isFullyConverged());
    }

    @Test
    void svcStandbyAutomatonEnablesAndConverges() {
        Network net = svcNetwork();
        LoadFlow.run(net, flat());
        StaticVarCompensator svc = net.getStaticVarCompensator("SVC");
        svc.newExtension(StandbyAutomatonAdder.class)
                .withStandbyStatus(false).withB0(0.0)
                .withLowVoltageThreshold(0.9 * 138.0).withLowVoltageSetpoint(0.95 * 138.0)
                .withHighVoltageThreshold(1.1 * 138.0).withHighVoltageSetpoint(1.05 * 138.0)
                .add();
        VoltageControlCompleter.StandbyStats stats =
                VoltageControlCompleter.addSvcStandbyAutomaton(net, null);
        assertTrue(stats.standby() >= 1);
        assertTrue(svc.getExtension(StandbyAutomaton.class).isStandby());
        Bus b = svc.getTerminal().getBusView().getBus();
        StandbyAutomaton sa = svc.getExtension(StandbyAutomaton.class);
        assertTrue(sa.getLowVoltageThreshold() < b.getV() && b.getV() < sa.getHighVoltageThreshold());
        assertTrue(LoadFlow.run(net, flat()).isFullyConverged());
    }

    @Test
    void hvdcAcEmulationEnablesAngleDroop() {
        Network net = solvedNodeBreaker();
        VoltageControlCompleter.HvdcStats stats = VoltageControlCompleter.addHvdcAcEmulation(
                net, VoltageControlCompleter.DEFAULT_HVDC_DROOP, null);
        assertTrue(stats.hvdc() >= 1);
        assertNotNull(net.getHvdcLines().iterator().next().getExtension(
                com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl.class));
        assertTrue(LoadFlow.run(net, flat()).isFullyConverged());
    }

    @Test
    void noStaticVarCompensatorsIsNoop() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertEquals(0, VoltageControlCompleter.addSvcVoltageControl(net, 0.01).svcs());
        assertEquals(0, VoltageControlCompleter.addSvcStandbyAutomaton(net, null).standby());
    }

    // -----------------------------------------------------------------------
    // Small fixtures
    // -----------------------------------------------------------------------

    /** Two voltage-regulating generators in one voltage level (+ a load). */
    private static Network twoGeneratorsOneVoltageLevel() {
        Network net = Network.create("shared", "test");
        var s = net.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(138.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("B").add();
        for (String id : new String[]{"G1", "G2"}) {
            vl.newGenerator().setId(id).setBus("B").setConnectableBus("B")
                    .setMinP(0.0).setMaxP(100.0).setTargetP(30.0).setTargetV(138.0)
                    .setVoltageRegulatorOn(true).add()
                    .newMinMaxReactiveLimits().setMinQ(-50.0).setMaxQ(50.0).add();
        }
        vl.newLoad().setId("L").setBus("B").setConnectableBus("B").setP0(50.0).setQ0(10.0).add();
        return net;
    }

    /** A 2-bus network with a static var compensator (VOLTAGE, regulating) at bus 2. */
    private static Network svcNetwork() {
        Network net = Network.create("svc", "test");
        var s1 = net.newSubstation().setId("S1").add();
        VoltageLevel vl1 = s1.newVoltageLevel().setId("VL1").setNominalV(138.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("B1").add();
        vl1.newGenerator().setId("SLACK").setBus("B1").setConnectableBus("B1")
                .setMinP(0.0).setMaxP(300.0).setTargetP(60.0).setTargetV(138.0)
                .setVoltageRegulatorOn(true).add()
                .newMinMaxReactiveLimits().setMinQ(-200.0).setMaxQ(200.0).add();
        var s2 = net.newSubstation().setId("S2").add();
        VoltageLevel vl2 = s2.newVoltageLevel().setId("VL2").setNominalV(138.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl2.getBusBreakerView().newBus().setId("B2").add();
        vl2.newLoad().setId("LOAD").setBus("B2").setConnectableBus("B2").setP0(50.0).setQ0(10.0).add();
        vl2.newStaticVarCompensator().setId("SVC").setBus("B2").setConnectableBus("B2")
                .setBmin(-0.01).setBmax(0.01)
                .setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE)
                .setRegulating(true).setVoltageSetpoint(138.0).setReactivePowerSetpoint(0.0)
                .add();
        net.newLine().setId("LINE")
                .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
                .setVoltageLevel2("VL2").setBus2("B2").setConnectableBus2("B2")
                .setR(1.0).setX(10.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0).add();
        return net;
    }
}
