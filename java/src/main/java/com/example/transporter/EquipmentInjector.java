package com.example.transporter;

import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VscConverterStation;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRangeAdder;
import com.powsybl.iidm.network.extensions.StandbyAutomatonAdder;
import com.powsybl.iidm.network.extensions.VoltageRegulationAdder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inject synthetic FACTS / storage / HVDC equipment (batteries, static var
 * compensators, VSC HVDC links) into a network — the Java port of
 * {@code add_equipment.py}.
 *
 * <p>The source cases (PEGASE, ACTIVSg) carry none of these; a sparse,
 * electrically neutral set is added (zero power/reactive target, regulation off)
 * so the base-case load flow is unchanged and the point is structural coverage
 * of the IIDM object types. Hosts are load buses, evenly spread and chosen
 * deterministically. Run on the bus-breaker network before the load flow.
 */
public final class EquipmentInjector {

    // Devices per bus, from a real reference network (27/7/6 over 7803 buses).
    public static final double DEFAULT_BATTERY_RATE = 27.0 / 7803.0;
    public static final double DEFAULT_SVC_RATE = 7.0 / 7803.0;
    public static final double DEFAULT_HVDC_RATE = 6.0 / 7803.0;
    public static final double DEFAULT_HVDC_NOMINAL_V = 320.0;

    private EquipmentInjector() {
    }

    private record Host(VoltageLevel vl, String busId) {
    }

    /** Distinct load-bus hosts, sorted by bus id for determinism. */
    private static List<Host> hostBuses(Network network) {
        Map<String, Host> byBus = new LinkedHashMap<>();
        for (Load load : network.getLoads()) {
            Bus b = load.getTerminal().getBusBreakerView().getBus();
            if (b != null) {
                byBus.putIfAbsent(b.getId(), new Host(load.getTerminal().getVoltageLevel(), b.getId()));
            }
        }
        List<Host> hosts = new ArrayList<>(byBus.values());
        hosts.sort((a, c) -> a.busId().compareTo(c.busId()));
        return hosts;
    }

    private static <T> List<T> evenlySpaced(List<T> items, int n) {
        List<T> out = new ArrayList<>();
        if (n <= 0 || items.isEmpty()) {
            return out;
        }
        n = Math.min(n, items.size());
        for (int i = 0; i < n; i++) {
            out.add(items.get((i * items.size()) / n));
        }
        return out;
    }

    private static int resolveCount(Network network, Integer count, double rate) {
        if (count != null) {
            return Math.max(0, count);
        }
        return Math.max(1, (int) Math.round(network.getBusView().getBusStream().count() * rate));
    }

    public record BatteryStats(int added) {
    }

    /**
     * Add sparse grid-storage batteries (idle: target P/Q = 0), each with a
     * {@code voltageRegulation} extension (regulator off, neutral).
     */
    public static BatteryStats addBatteries(Network network, Integer count, double powerMw) {
        List<Host> hosts = evenlySpaced(hostBuses(network), resolveCount(network, count, DEFAULT_BATTERY_RATE));
        int i = 0;
        for (Host h : hosts) {
            Battery battery = h.vl().newBattery()
                    .setId("SYN_BAT_" + i)
                    .setBus(h.busId()).setConnectableBus(h.busId())
                    .setMinP(-powerMw).setMaxP(powerMw)
                    .setTargetP(0.0).setTargetQ(0.0)
                    .add();
            battery.newMinMaxReactiveLimits().setMinQ(-powerMw).setMaxQ(powerMw).add();
            battery.newExtension(VoltageRegulationAdder.class)
                    .withVoltageRegulatorOn(false)
                    .withRegulatingTerminal(battery.getTerminal())
                    .add();
            i++;
        }
        return new BatteryStats(i);
    }

    public record SvcStats(int added) {
    }

    /** Add sparse SVCs (regulation off, neutral), each with a {@code standbyAutomaton}. */
    public static SvcStats addStaticVarCompensators(Network network, Integer count, double susceptance) {
        List<Host> hosts = evenlySpaced(hostBuses(network), resolveCount(network, count, DEFAULT_SVC_RATE));
        int i = 0;
        for (Host h : hosts) {
            double targetV = h.vl().getNominalV();
            StaticVarCompensator svc = h.vl().newStaticVarCompensator()
                    .setId("SYN_SVC_" + i)
                    .setBus(h.busId()).setConnectableBus(h.busId())
                    .setBmin(-susceptance).setBmax(susceptance)
                    .setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE)
                    .setRegulating(false)
                    .setVoltageSetpoint(targetV)
                    .setReactivePowerSetpoint(0.0)
                    .add();
            svc.newExtension(StandbyAutomatonAdder.class)
                    .withStandbyStatus(false).withB0(0.0)
                    .withLowVoltageThreshold(0.90 * targetV).withLowVoltageSetpoint(0.95 * targetV)
                    .withHighVoltageThreshold(1.10 * targetV).withHighVoltageSetpoint(1.05 * targetV)
                    .add();
            i++;
        }
        return new SvcStats(i);
    }

    public record HvdcStats(int hvdcLines, int vscStations) {
    }

    /**
     * Add sparse VSC-based HVDC links (idle: target P = 0), with the
     * {@code hvdcAngleDroopActivePowerControl} (disabled) and
     * {@code hvdcOperatorActivePowerRange} extensions. The two ends of each link
     * are placed far apart in the host ordering so the link spans the network.
     */
    public static HvdcStats addHvdcLinks(Network network, Integer count, double maxP) {
        int n = resolveCount(network, count, DEFAULT_HVDC_RATE);
        List<Host> hosts = evenlySpaced(hostBuses(network), 2 * n);
        int links = hosts.size() / 2;
        if (links == 0) {
            return new HvdcStats(0, 0);
        }
        int vsc = 0;
        for (int i = 0; i < links; i++) {
            Host side1 = hosts.get(i);
            Host side2 = hosts.get(links + i);
            createVsc(side1, "SYN_VSC_" + i + "_1", maxP);
            createVsc(side2, "SYN_VSC_" + i + "_2", maxP);
            vsc += 2;

            HvdcLine hvdc = network.newHvdcLine()
                    .setId("SYN_HVDC_" + i)
                    .setConverterStationId1("SYN_VSC_" + i + "_1")
                    .setConverterStationId2("SYN_VSC_" + i + "_2")
                    .setR(1.0).setNominalV(DEFAULT_HVDC_NOMINAL_V)
                    .setMaxP(maxP).setActivePowerSetpoint(0.0)
                    .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                    .add();
            hvdc.newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                    .withDroop(180.0f).withP0(0.0f).withEnabled(false).add();
            hvdc.newExtension(HvdcOperatorActivePowerRangeAdder.class)
                    .withOprFromCS1toCS2((float) maxP).withOprFromCS2toCS1((float) maxP).add();
        }
        return new HvdcStats(links, vsc);
    }

    private static void createVsc(Host host, String id, double maxP) {
        VscConverterStation station = host.vl().newVscConverterStation()
                .setId(id)
                .setBus(host.busId()).setConnectableBus(host.busId())
                .setLossFactor(1.0f)
                .setVoltageRegulatorOn(false)
                .setReactivePowerSetpoint(0.0)
                .add();
        station.newMinMaxReactiveLimits().setMinQ(-maxP / 2).setMaxQ(maxP / 2).add();
    }

    public record EquipmentStats(BatteryStats batteries, SvcStats svcs, HvdcStats hvdc) {
    }

    /** Add batteries, SVCs and HVDC links with default sparse counts. */
    public static EquipmentStats addEquipment(Network network) {
        return new EquipmentStats(
                addBatteries(network, null, 25.0),
                addStaticVarCompensators(network, null, 0.01),
                addHvdcLinks(network, null, 200.0));
    }
}
