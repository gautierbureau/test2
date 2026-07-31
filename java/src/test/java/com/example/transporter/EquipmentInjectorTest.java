package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRange;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.extensions.VoltageRegulation;
import com.powsybl.loadflow.LoadFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java counterpart of {@code test_add_equipment.py}: the injected devices are
 * created neutral (regulation off), so the base-case load flow still converges.
 */
class EquipmentInjectorTest {

    @Test
    void batteriesAddedNeutralWithVoltageRegulationOff() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertEquals(2, EquipmentInjector.addBatteries(net, 2, 25.0).added());
        Battery bat = net.getBattery("SYN_BAT_0");
        assertNotNull(bat);
        VoltageRegulation vr = bat.getExtension(VoltageRegulation.class);
        assertNotNull(vr);
        assertFalse(vr.isVoltageRegulatorOn());
        assertTrue(LoadFlow.run(net).isFullyConverged());
    }

    @Test
    void svcsAddedRegulationOff() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertEquals(2, EquipmentInjector.addStaticVarCompensators(net, 2, 0.01).added());
        StaticVarCompensator svc = net.getStaticVarCompensator("SYN_SVC_0");
        assertNotNull(svc);
        assertFalse(svc.isRegulating());
        StandbyAutomaton sa = svc.getExtension(StandbyAutomaton.class);
        assertNotNull(sa);
        assertFalse(sa.isStandby());
        assertTrue(LoadFlow.run(net).isFullyConverged());
    }

    @Test
    void hvdcLinksAddedWithControlExtensions() {
        Network net = IeeeCdfNetworkFactory.create14();
        EquipmentInjector.HvdcStats stats = EquipmentInjector.addHvdcLinks(net, 1, 200.0);
        assertEquals(1, stats.hvdcLines());
        assertEquals(2, stats.vscStations());
        var hvdc = net.getHvdcLine("SYN_HVDC_0");
        assertNotNull(hvdc);
        HvdcAngleDroopActivePowerControl droop =
                hvdc.getExtension(HvdcAngleDroopActivePowerControl.class);
        assertNotNull(droop);
        assertFalse(droop.isEnabled());
        assertNotNull(hvdc.getExtension(HvdcOperatorActivePowerRange.class));
        assertTrue(LoadFlow.run(net).isFullyConverged());
    }

    @Test
    void equipmentExtensionsSurviveNodeBreakerConversion() {
        Network net = IeeeCdfNetworkFactory.create14();
        EquipmentInjector.addStaticVarCompensators(net, 1, 0.01);
        EquipmentInjector.addBatteries(net, 1, 25.0);
        EquipmentInjector.addHvdcLinks(net, 1, 200.0);
        Network nb = BusToNodeBreakerConverter.convert(net);
        // The converter carries the equipment-injected control extensions across.
        assertNotNull(nb.getStaticVarCompensator("SYN_SVC_0")
                .getExtension(StandbyAutomaton.class));
        assertNotNull(nb.getBattery("SYN_BAT_0")
                .getExtension(com.powsybl.iidm.network.extensions.VoltageRegulation.class));
        assertNotNull(nb.getHvdcLine("SYN_HVDC_0")
                .getExtension(com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRange.class));
    }

    @Test
    void addEquipmentAllTypesAndConverges() {
        Network net = IeeeCdfNetworkFactory.create14();
        EquipmentInjector.EquipmentStats stats = EquipmentInjector.addEquipment(net);
        assertTrue(stats.batteries().added() >= 1);
        assertTrue(stats.svcs().added() >= 1);
        assertTrue(stats.hvdc().hvdcLines() >= 1);
        assertTrue(LoadFlow.run(net).isFullyConverged());
    }
}
