package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.BusbarSection;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.iidm.network.ShuntCompensatorAdder;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControl;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.extensions.VoltagePerReactivePowerControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Add the OpenLoadFlow outer-loop modeling that a case does not carry, always
 * keeping the base case converged — the Java port of the voltage-control /
 * modeling half of the Python {@code complete_network.py} module.
 *
 * <p>Each control target is set to the value already present in the solved base
 * case, so the matching outer loop starts satisfied and the flow still
 * converges. The convergence-sensitive additions (transformer voltage control,
 * secondary voltage control, SVC standby) re-check convergence with the outer
 * loop on and back off (halving, or removing the extension) if it does not
 * converge, so at least one representative device exercises each modeling type
 * without forcing a diverging set.
 *
 * <p>Several of these need a node-breaker network (busbar sections / feeder
 * bays): run them on the rebuilt node-breaker model after a load flow.
 */
public final class VoltageControlCompleter {

    public static final double DEFAULT_PHASE_CURRENT_MARGIN = 1.5;
    public static final int DEFAULT_TVC_COUNT = 300;
    public static final double DEFAULT_TVC_DEADBAND = 2.0;
    public static final int DEFAULT_SHUNT_VC_COUNT = 20;
    public static final int DEFAULT_SHUNT_SECTIONS = 4;
    public static final double DEFAULT_SHUNT_B_PER_SECTION = 0.001;
    public static final int DEFAULT_SHARED_VC_GROUPS = 50;
    public static final int DEFAULT_SECONDARY_VC_ZONES = 2;
    public static final int DEFAULT_SECONDARY_VC_UNITS = 2;
    public static final double DEFAULT_SVC_SLOPE = 0.01;
    public static final double DEFAULT_HVDC_DROOP = 20.0;

    private VoltageControlCompleter() {
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /** The already-solved bus a terminal connects to, or null if none/unsolved. */
    private static Bus solvedBus(Terminal t) {
        Bus b = t.getBusView().getBus();
        if (b != null && Double.isFinite(b.getV()) && b.getV() > 0) {
            return b;
        }
        return null;
    }

    /** A busbar section sitting on the given (bus-view) bus, or null (bus-breaker). */
    private static BusbarSection busbarOnBus(VoltageLevel vl, Bus busViewBus) {
        if (vl.getTopologyKind() != com.powsybl.iidm.network.TopologyKind.NODE_BREAKER) {
            return null;
        }
        for (BusbarSection bbs : vl.getNodeBreakerView().getBusbarSections()) {
            Bus b = bbs.getTerminal().getBusView().getBus();
            if (b != null && b.getId().equals(busViewBus.getId())) {
                return bbs;
            }
        }
        return null;
    }

    private static boolean converges(Network network, Consumer<LoadFlowParameters> tweak) {
        LoadFlowParameters params = new LoadFlowParameters()
                .setDistributedSlack(true)
                .setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        if (tweak != null) {
            tweak.accept(params);
        }
        return LoadFlow.run(network, params).isFullyConverged();
    }

    /** Pick {@code n} entries evenly spread across {@code items}. */
    private static <T> List<T> evenlySpread(List<T> items, int n) {
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(items.get((i * items.size()) / n));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Phase control
    // -----------------------------------------------------------------------

    public record PhaseControlStats(int phaseShifters) {
    }

    /**
     * Turn idle phase-shifting transformers into CURRENT_LIMITER shifters with a
     * threshold above the current they already carry ({@code currentMargin} x
     * base i1), so the phase-control outer loop is present but passive and the
     * flow still converges. Transformers whose ratio tap changer already
     * regulates are skipped (one control per transformer). Run after a load flow.
     */
    public static PhaseControlStats addPhaseControl(Network network, Integer count,
                                                    double currentMargin) {
        int applied = 0;
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            PhaseTapChanger ptc = tx.getPhaseTapChanger();
            if (ptc == null || ptc.isRegulating()) {
                continue;
            }
            RatioTapChanger rtc = tx.getRatioTapChanger();
            if (rtc != null && rtc.isRegulating()) {
                continue;
            }
            double i1 = tx.getTerminal1().getI();
            if (!Double.isFinite(i1)) {
                continue;
            }
            ptc.setRegulationTerminal(tx.getTerminal1());
            ptc.setTargetDeadband(10.0);
            ptc.setRegulationValue(Math.max(i1 * currentMargin, 100.0));
            ptc.setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER);
            ptc.setRegulating(true);
            applied++;
            if (count != null && applied >= count) {
                break;
            }
        }
        return new PhaseControlStats(applied);
    }

    // -----------------------------------------------------------------------
    // Transformer voltage control
    // -----------------------------------------------------------------------

    public record TransformerVcStats(int regulating, int disabled) {
    }

    /**
     * Keep a converging subset of the voltage-regulating ratio tap changers
     * active. With the transformer-voltage-control outer loop on, thousands
     * acting at once do not converge, so this disables them all, re-enables an
     * evenly spread subset (widening the deadband) and verifies the outer loop
     * converges, halving the subset until it does. Run after a load flow.
     */
    public static TransformerVcStats addTransformerVoltageControl(Network network, int count,
                                                                  double deadband) {
        List<RatioTapChanger> regulating = new ArrayList<>();
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            RatioTapChanger rtc = tx.getRatioTapChanger();
            if (rtc != null && rtc.isRegulating()) {
                regulating.add(rtc);
            }
        }
        if (regulating.isEmpty()) {
            return new TransformerVcStats(0, 0);
        }
        regulating.forEach(rtc -> rtc.setRegulating(false));

        int n = Math.min(count, regulating.size());
        while (n >= 1) {
            List<RatioTapChanger> subset = evenlySpread(regulating, n);
            subset.forEach(rtc -> rtc.setTargetDeadband(deadband).setRegulating(true));
            if (converges(network, p -> p.setTransformerVoltageControlOn(true))) {
                return new TransformerVcStats(n, regulating.size() - n);
            }
            subset.forEach(rtc -> rtc.setRegulating(false));
            n /= 2;
        }
        return new TransformerVcStats(0, regulating.size());
    }

    // -----------------------------------------------------------------------
    // Shunt voltage control (switchable shunts, node-breaker feeder bays)
    // -----------------------------------------------------------------------

    public record ShuntVcStats(int shunts) {
    }

    /**
     * Add a few multi-section switchable shunts (node-breaker feeder bays) in
     * voltage regulation, targeting their bus's already-solved voltage, so the
     * shunt-voltage-control outer loop is exercised while the flow still
     * converges. The source cases only carry single-section fixed shunts (which
     * cannot control), hence the synthetic ones. Run on the node-breaker network
     * after a load flow.
     */
    public static ShuntVcStats addShuntVoltageControl(Network network, int count, int sections,
                                                      double bPerSection, double deadband) {
        List<BusbarSection> cand = new ArrayList<>();
        for (BusbarSection bbs : network.getBusbarSections()) {
            if (solvedBus(bbs.getTerminal()) != null) {
                cand.add(bbs);
            }
        }
        if (cand.isEmpty()) {
            return new ShuntVcStats(0);
        }
        cand.sort(Comparator.comparing(BusbarSection::getId));
        int n = Math.min(count, cand.size());
        List<BusbarSection> sel = evenlySpread(cand, n);

        int created = 0;
        for (int i = 0; i < sel.size(); i++) {
            BusbarSection bbs = sel.get(i);
            VoltageLevel vl = bbs.getTerminal().getVoltageLevel();
            String id = "SYN_SHVC_" + i;
            ShuntCompensatorAdder adder = vl.newShuntCompensator()
                    .setId(id)
                    .setSectionCount(1)
                    .setVoltageRegulatorOn(false)
                    .setTargetV(vl.getNominalV())
                    .setTargetDeadband(deadband);
            adder.newLinearModel()
                    .setBPerSection(bPerSection)
                    .setMaximumSectionCount(sections)
                    .add();
            new CreateFeederBayBuilder()
                    .withInjectionAdder(adder)
                    .withBusOrBusbarSectionId(bbs.getId())
                    .withInjectionPositionOrder(9000 + i)
                    .build()
                    .apply(network);

            ShuntCompensator sh = network.getShuntCompensator(id);
            double v = bbs.getTerminal().getBusView().getBus().getV();
            // Set the setpoint before enabling regulation (a NaN setpoint is rejected).
            sh.setTargetV(v).setTargetDeadband(deadband)
                    .setRegulatingTerminal(sh.getTerminal());
            sh.setVoltageRegulatorOn(true);
            created++;
        }
        return new ShuntVcStats(created);
    }

    // -----------------------------------------------------------------------
    // Shared (coordinated) generator voltage control
    // -----------------------------------------------------------------------

    public record SharedVcStats(int groups, int generators) {
    }

    /**
     * Make several generators in a voltage level co-regulate one common bus
     * (shared / coordinated voltage control): the generators of a multi-unit
     * voltage level are all pointed at the first generator's bus, targeting that
     * bus's already-solved voltage, so the reactive output is split and the flow
     * still converges. Only {@code count} groups are set up. Run after a load flow.
     */
    public static SharedVcStats addSharedVoltageControl(Network network, int count) {
        Map<String, List<Generator>> byVl = new LinkedHashMap<>();
        for (Generator g : network.getGenerators()) {
            if (g.isVoltageRegulatorOn()) {
                byVl.computeIfAbsent(g.getTerminal().getVoltageLevel().getId(), k -> new ArrayList<>())
                        .add(g);
            }
        }
        List<List<Generator>> groups = byVl.values().stream()
                .filter(grp -> grp.size() >= 2)
                .sorted(Comparator.comparing(grp -> grp.get(0).getId()))
                .toList();

        int applied = 0;
        int secondaries = 0;
        for (List<Generator> grp : groups) {
            if (applied >= count) {
                break;
            }
            Generator pilot = grp.get(0);
            Bus pbus = solvedBus(pilot.getTerminal());
            if (pbus == null) {
                continue;
            }
            double v = pbus.getV();
            for (int i = 1; i < grp.size(); i++) {
                grp.get(i).setRegulatingTerminal(pilot.getTerminal()).setTargetV(v);
                secondaries++;
            }
            pilot.setTargetV(v);
            applied++;
        }
        return new SharedVcStats(applied, secondaries);
    }

    // -----------------------------------------------------------------------
    // Secondary voltage control (control zones)
    // -----------------------------------------------------------------------

    public record SecondaryVcStats(int zones, int units) {
    }

    /**
     * Set up a few secondary-voltage-control zones (pilot bus + a couple of
     * locally regulating generators each), pilot target = the pilot bus's
     * already-solved voltage. Zones are drawn from voltage levels with two or
     * more locally regulating generators (from the tail, so they do not overlap
     * the head groups {@link #addSharedVoltageControl} uses), falling back to any
     * spare local generators. Convergence with the outer loop on is verified and
     * the extension removed on divergence. Run after a load flow, before
     * {@link #addSharedVoltageControl}.
     */
    public static SecondaryVcStats addSecondaryVoltageControl(Network network, int zones,
                                                              int unitsPerZone) {
        // Locally regulating generators (regulating own bus), grouped by voltage level.
        Map<String, List<Generator>> byVl = new LinkedHashMap<>();
        List<Generator> local = new ArrayList<>();
        for (Generator g : network.getGenerators()) {
            if (!g.isVoltageRegulatorOn()) {
                continue;
            }
            Bus own = solvedBus(g.getTerminal());
            Bus regulated = g.getRegulatingTerminal().getBusView().getBus();
            if (own == null || regulated == null || !own.getId().equals(regulated.getId())) {
                continue;
            }
            local.add(g);
            byVl.computeIfAbsent(g.getTerminal().getVoltageLevel().getId(), k -> new ArrayList<>())
                    .add(g);
        }
        if (local.size() < unitsPerZone) {
            return new SecondaryVcStats(0, 0);
        }
        List<List<Generator>> multi = byVl.values().stream()
                .filter(grp -> grp.size() >= unitsPerZone)
                .sorted(Comparator.comparing(grp -> grp.get(0).getId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        List<List<Generator>> zoneUnits = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        // From the tail, so they do not collide with the head groups shared control uses.
        for (int i = multi.size() - 1; i >= 0 && zoneUnits.size() < zones; i--) {
            List<Generator> picked = new ArrayList<>(multi.get(i).subList(0, unitsPerZone));
            zoneUnits.add(picked);
            picked.forEach(g -> used.add(g.getId()));
        }
        if (zoneUnits.size() < zones) {
            List<Generator> spare = local.stream().filter(g -> !used.contains(g.getId())).toList();
            int i = 0;
            while (zoneUnits.size() < zones && i + unitsPerZone <= spare.size()) {
                zoneUnits.add(new ArrayList<>(spare.subList(i, i + unitsPerZone)));
                i += unitsPerZone;
            }
        }
        if (zoneUnits.isEmpty()) {
            return new SecondaryVcStats(0, 0);
        }

        SecondaryVoltageControlAdder adder = network.newExtension(SecondaryVoltageControlAdder.class);
        int unitCount = 0;
        for (int z = 0; z < zoneUnits.size(); z++) {
            List<Generator> units = zoneUnits.get(z);
            Generator pilot = units.get(0);
            Bus pbus = solvedBus(pilot.getTerminal());
            BusbarSection bbs = busbarOnBus(pilot.getTerminal().getVoltageLevel(), pbus);
            String pilotId = bbs != null ? bbs.getId() : pbus.getId();
            var zoneAdder = adder.newControlZone()
                    .withName("SEC_VC_ZONE_" + z)
                    .newPilotPoint()
                    .withTargetV(pbus.getV())
                    .withBusbarSectionsOrBusesIds(List.of(pilotId))
                    .add();
            for (Generator g : units) {
                zoneAdder.newControlUnit().withId(g.getId()).withParticipate(true).add();
                unitCount++;
            }
            zoneAdder.add();
        }
        adder.add();

        if (!converges(network, p -> OpenLoadFlowParameters.create(p).setSecondaryVoltageControl(true))) {
            network.removeExtension(SecondaryVoltageControl.class);
            return new SecondaryVcStats(0, 0);
        }
        return new SecondaryVcStats(zoneUnits.size(), unitCount);
    }

    // -----------------------------------------------------------------------
    // Static var compensator voltage control + standby automaton
    // -----------------------------------------------------------------------

    public record SvcVcStats(int svcs) {
    }

    /**
     * Put idle static var compensators into VOLTAGE regulation targeting their
     * bus's already-solved voltage, with a voltage-droop slope
     * ({@code voltagePerReactivePowerControl}), so the control is satisfied from
     * the start and the flow still converges. Run after a load flow.
     */
    public static SvcVcStats addSvcVoltageControl(Network network, double slope) {
        int applied = 0;
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            if (svc.isRegulating()) {
                continue;
            }
            Bus b = solvedBus(svc.getTerminal());
            if (b == null) {
                continue;
            }
            svc.setVoltageSetpoint(b.getV());
            svc.setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE);
            svc.setRegulating(true);
            svc.newExtension(VoltagePerReactivePowerControlAdder.class).withSlope(slope).add();
            applied++;
        }
        return new SvcVcStats(applied);
    }

    public record StandbyStats(int standby, int off) {
    }

    /**
     * Put a converging subset of SVCs into standby (fixed b0 = 0) with thresholds
     * recentred on the bus's already-solved voltage, so the standby automaton
     * stays in its neutral band and the flow still converges. Activates on all
     * eligible SVCs, halving the set until it converges. Run after a load flow on
     * SVCs already in VOLTAGE mode (see {@link #addSvcVoltageControl}).
     */
    public static StandbyStats addSvcStandbyAutomaton(Network network, Integer count) {
        List<StaticVarCompensator> cand = new ArrayList<>();
        Map<String, Double> vsolved = new LinkedHashMap<>();
        for (StaticVarCompensator svc : network.getStaticVarCompensators()) {
            if (svc.getExtension(StandbyAutomaton.class) == null) {
                continue;
            }
            Bus b = solvedBus(svc.getTerminal());
            if (b != null) {
                cand.add(svc);
                vsolved.put(svc.getId(), b.getV());
            }
        }
        if (cand.isEmpty()) {
            return new StandbyStats(0, 0);
        }
        int n = count == null ? cand.size() : Math.min(count, cand.size());
        while (n >= 1) {
            List<StaticVarCompensator> sel = evenlySpread(cand, n);
            for (StaticVarCompensator svc : sel) {
                double v = vsolved.get(svc.getId());
                StandbyAutomaton sa = svc.getExtension(StandbyAutomaton.class);
                sa.setStandby(true);
                sa.setB0(0.0);
                sa.setLowVoltageThreshold(0.90 * v);
                sa.setLowVoltageSetpoint(0.95 * v);
                sa.setHighVoltageThreshold(1.10 * v);
                sa.setHighVoltageSetpoint(1.05 * v);
            }
            if (converges(network, null)) {
                return new StandbyStats(n, cand.size() - n);
            }
            sel.forEach(svc -> svc.getExtension(StandbyAutomaton.class).setStandby(false));
            n /= 2;
        }
        return new StandbyStats(0, cand.size());
    }

    // -----------------------------------------------------------------------
    // HVDC AC emulation (angle droop)
    // -----------------------------------------------------------------------

    public record HvdcStats(int hvdc) {
    }

    /**
     * Switch HVDC links to AC emulation (angle-droop active power control): the
     * DC power follows {@code p0 + droop * (theta1 - theta2)}, with p0 set to
     * cancel the droop term at the already-solved angle difference so the link
     * keeps its ~zero transfer and the flow still converges. Run after a load flow.
     */
    public static HvdcStats addHvdcAcEmulation(Network network, double droop, Integer count) {
        int applied = 0;
        for (HvdcLine hvdc : network.getHvdcLines()) {
            Terminal t1 = hvdc.getConverterStation1().getTerminal();
            Terminal t2 = hvdc.getConverterStation2().getTerminal();
            Bus b1 = t1.getBusView().getBus();
            Bus b2 = t2.getBusView().getBus();
            if (b1 == null || b2 == null
                    || !Double.isFinite(b1.getAngle()) || !Double.isFinite(b2.getAngle())) {
                continue;
            }
            double p0 = -droop * (b1.getAngle() - b2.getAngle());
            hvdc.newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                    .withDroop((float) droop)
                    .withP0((float) p0)
                    .withEnabled(true)
                    .add();
            applied++;
            if (count != null && applied >= count) {
                break;
            }
        }
        return new HvdcStats(applied);
    }
}
