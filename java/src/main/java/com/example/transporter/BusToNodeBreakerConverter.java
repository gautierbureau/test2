package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateBranchFeederBaysBuilder;
import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Convert a <b>bus-breaker</b> network into an electrically equivalent
 * <b>node-breaker</b> network.
 *
 * <p>The transformation is purely topological — no electrical parameter is
 * changed — so a load flow on the converted network yields the same bus
 * voltages and branch flows as on the original.
 *
 * <p>The rule is one busbar section per configured bus. Every piece of
 * equipment that used to sit directly on a bus-breaker bus is reconnected to
 * the corresponding busbar section through its own <i>feeder bay</i>: a
 * disconnector to the busbar plus a series breaker, created by powsybl's
 * {@link CreateFeederBayBuilder} (injections) and
 * {@link CreateBranchFeederBaysBuilder} (branches). This mirrors how a real
 * node-breaker substation is drawn, where each feeder reaches the busbar
 * through switchgear rather than being wired straight onto the bus.
 *
 * <pre>
 *   bus-breaker              node-breaker (after conversion)
 *
 *      BUS  ── GEN            BBS ─[disc]─[brk]─ GEN
 *       │                      │
 *       ├──── LOAD             ├──[disc]─[brk]─ LOAD
 *       │                      │
 *       └──── LINE             └──[disc]─[brk]─ LINE
 * </pre>
 *
 * <h2>Scope</h2>
 * A brand-new {@link Network} is returned; the source is left untouched. A
 * voltage level's topology kind is immutable in IIDM, so the network has to be
 * rebuilt rather than mutated in place.
 *
 * <p>Supported equipment (covers the IEEE-14 scoping network): generators
 * (min/max or curve reactive limits), loads, linear and non-linear shunt
 * compensators, lines and two-winding transformers, together with their
 * operational current limits. Bus couplers become breakers between the two
 * busbar sections they join. Element types not yet handled
 * (three-winding transformers, HVDC, static var compensators, dangling lines,
 * batteries, tie lines) raise a clear {@link UnsupportedOperationException} so
 * an unsupported input never fails silently.
 */
public final class BusToNodeBreakerConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusToNodeBreakerConverter.class);

    /** Suffix appended to a configured bus id to name its busbar section. */
    public static final String BBS_SUFFIX = "_BBS";

    private BusToNodeBreakerConverter() {
        // Static utility
    }

    /**
     * Build and return the node-breaker equivalent of {@code source}.
     *
     * @param source a network whose voltage levels are all in
     *               {@link TopologyKind#BUS_BREAKER} topology
     * @return a new network, electrically identical, entirely in
     *         {@link TopologyKind#NODE_BREAKER} topology
     * @throws UnsupportedOperationException if the source contains a voltage
     *         level that is already node-breaker, or an equipment type that
     *         the converter does not yet handle
     */
    public static Network convert(Network source) {
        Network target = Network.create(source.getId(), source.getSourceFormat());
        target.setCaseDate(source.getCaseDate());
        target.setForecastDistance(source.getForecastDistance());

        // Maps configured bus id -> busbar section id in the target network.
        Map<String, String> busToBbs = new HashMap<>();
        // Next free ConnectablePosition order, per target voltage level id.
        Map<String, Integer> nextOrder = new HashMap<>();

        // 1. Substations, voltage levels and busbar sections.
        for (Substation s : source.getSubstations()) {
            copySubstation(target, s);
        }
        for (VoltageLevel vl : source.getVoltageLevels()) {
            if (vl.getSubstation().isEmpty()) {
                copyVoltageLevelWithoutSubstation(target, vl);
            }
            createBusbarSections(target, vl, busToBbs);
        }

        // 2. Bus couplers (bus-breaker switches) become breakers between busbars.
        for (VoltageLevel vl : source.getVoltageLevels()) {
            copyBusCouplers(target, vl, busToBbs);
        }

        // 3. Injections.
        for (Generator g : source.getGenerators()) {
            copyGenerator(target, g, busToBbs, nextOrder);
        }
        for (Load l : source.getLoads()) {
            copyLoad(target, l, busToBbs, nextOrder);
        }
        for (ShuntCompensator sh : source.getShuntCompensators()) {
            copyShunt(target, sh, busToBbs, nextOrder);
        }

        // 4. Branches.
        for (Line line : source.getLines()) {
            copyLine(target, line, busToBbs, nextOrder);
        }
        for (TwoWindingsTransformer tx : source.getTwoWindingsTransformers()) {
            copyTwoWindingsTransformer(target, tx, busToBbs, nextOrder);
        }

        // 5. Reject anything not handled above, loudly rather than silently.
        rejectUnsupported(source);

        LOGGER.info("Converted '{}' to node-breaker: {} substation(s), {} voltage level(s), "
                        + "{} busbar section(s)",
                target.getId(), target.getSubstationCount(), target.getVoltageLevelCount(),
                busToBbs.size());
        return target;
    }

    // ------------------------------------------------------------------
    // Substations / voltage levels / busbar sections
    // ------------------------------------------------------------------

    private static void copySubstation(Network target, Substation s) {
        SubstationAdder adder = target.newSubstation().setId(s.getId());
        s.getOptionalName().ifPresent(adder::setName);
        s.getCountry().ifPresent(adder::setCountry);
        if (s.getTso() != null) {
            adder.setTso(s.getTso());
        }
        String[] tags = s.getGeographicalTags().toArray(new String[0]);
        if (tags.length > 0) {
            adder.setGeographicalTags(tags);
        }
        Substation ts = adder.add();

        for (VoltageLevel vl : s.getVoltageLevels()) {
            requireBusBreaker(vl);
            VoltageLevelAdder vlAdder = ts.newVoltageLevel()
                    .setId(vl.getId())
                    .setNominalV(vl.getNominalV())
                    .setTopologyKind(TopologyKind.NODE_BREAKER)
                    .setLowVoltageLimit(vl.getLowVoltageLimit())
                    .setHighVoltageLimit(vl.getHighVoltageLimit());
            vl.getOptionalName().ifPresent(vlAdder::setName);
            vlAdder.add();
        }
    }

    private static void copyVoltageLevelWithoutSubstation(Network target, VoltageLevel vl) {
        requireBusBreaker(vl);
        VoltageLevelAdder vlAdder = target.newVoltageLevel()
                .setId(vl.getId())
                .setNominalV(vl.getNominalV())
                .setTopologyKind(TopologyKind.NODE_BREAKER)
                .setLowVoltageLimit(vl.getLowVoltageLimit())
                .setHighVoltageLimit(vl.getHighVoltageLimit());
        vl.getOptionalName().ifPresent(vlAdder::setName);
        vlAdder.add();
    }

    /** Create one busbar section per configured bus and record the mapping. */
    private static void createBusbarSections(Network target, VoltageLevel vl,
                                             Map<String, String> busToBbs) {
        VoltageLevel tvl = target.getVoltageLevel(vl.getId());
        int node = 0;
        for (Bus bus : vl.getBusBreakerView().getBuses()) {
            String bbsId = bus.getId() + BBS_SUFFIX;
            tvl.getNodeBreakerView().newBusbarSection()
                    .setId(bbsId)
                    .setNode(node++)
                    .add();
            busToBbs.put(bus.getId(), bbsId);
        }
    }

    /**
     * Reproduce every bus-breaker switch (bus coupler) as a breaker wired
     * directly between the two busbar sections of the buses it used to join.
     */
    private static void copyBusCouplers(Network target, VoltageLevel vl,
                                        Map<String, String> busToBbs) {
        VoltageLevel tvl = target.getVoltageLevel(vl.getId());
        VoltageLevel.NodeBreakerView nbv = tvl.getNodeBreakerView();
        for (Switch sw : vl.getBusBreakerView().getSwitches()) {
            Bus b1 = vl.getBusBreakerView().getBus1(sw.getId());
            Bus b2 = vl.getBusBreakerView().getBus2(sw.getId());
            int n1 = nbv.getBusbarSection(busToBbs.get(b1.getId())).getTerminal().getNodeBreakerView().getNode();
            int n2 = nbv.getBusbarSection(busToBbs.get(b2.getId())).getTerminal().getNodeBreakerView().getNode();
            nbv.newBreaker()
                    .setId(sw.getId())
                    .setNode1(n1)
                    .setNode2(n2)
                    .setOpen(sw.isOpen())
                    .setFictitious(sw.isFictitious())
                    .add();
        }
    }

    // ------------------------------------------------------------------
    // Injections
    // ------------------------------------------------------------------

    private static void copyGenerator(Network target, Generator g,
                                      Map<String, String> busToBbs,
                                      Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(g.getTerminal().getVoltageLevel().getId());
        GeneratorAdder adder = tvl.newGenerator()
                .setId(g.getId())
                .setEnergySource(g.getEnergySource())
                .setMinP(g.getMinP())
                .setMaxP(g.getMaxP())
                .setTargetP(g.getTargetP())
                .setTargetQ(g.getTargetQ())
                .setTargetV(g.getTargetV())
                .setVoltageRegulatorOn(g.isVoltageRegulatorOn())
                .setRatedS(g.getRatedS());
        g.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(g.getTerminal()), busToBbs, nextOrder);

        Generator created = target.getGenerator(g.getId());
        copyReactiveLimits(g, created);
    }

    private static void copyLoad(Network target, Load l,
                                 Map<String, String> busToBbs,
                                 Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(l.getTerminal().getVoltageLevel().getId());
        LoadAdder adder = tvl.newLoad()
                .setId(l.getId())
                .setLoadType(l.getLoadType())
                .setP0(l.getP0())
                .setQ0(l.getQ0());
        l.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(l.getTerminal()), busToBbs, nextOrder);
    }

    private static void copyShunt(Network target, ShuntCompensator sh,
                                  Map<String, String> busToBbs,
                                  Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(sh.getTerminal().getVoltageLevel().getId());
        ShuntCompensatorAdder adder = tvl.newShuntCompensator()
                .setId(sh.getId())
                .setSectionCount(sh.getSectionCount())
                .setVoltageRegulatorOn(sh.isVoltageRegulatorOn())
                .setTargetV(sh.getTargetV())
                .setTargetDeadband(sh.getTargetDeadband());
        sh.getOptionalName().ifPresent(adder::setName);

        switch (sh.getModelType()) {
            case LINEAR -> {
                ShuntCompensatorLinearModel m = (ShuntCompensatorLinearModel) sh.getModel();
                adder.newLinearModel()
                        .setBPerSection(m.getBPerSection())
                        .setGPerSection(m.getGPerSection())
                        .setMaximumSectionCount(sh.getMaximumSectionCount())
                        .add();
            }
            case NON_LINEAR -> {
                ShuntCompensatorNonLinearModel m = (ShuntCompensatorNonLinearModel) sh.getModel();
                var nl = adder.newNonLinearModel();
                for (ShuntCompensatorNonLinearModel.Section sec : m.getAllSections()) {
                    nl.beginSection()
                            .setB(sec.getB())
                            .setG(sec.getG())
                            .endSection();
                }
                nl.add();
            }
            default -> throw new UnsupportedOperationException(
                    "Unsupported shunt model type on " + sh.getId() + ": " + sh.getModelType());
        }

        createInjectionBay(target, adder, feederBus(sh.getTerminal()), busToBbs, nextOrder);
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    private static void copyLine(Network target, Line line,
                                 Map<String, String> busToBbs,
                                 Map<String, Integer> nextOrder) {
        LineAdder adder = target.newLine()
                .setId(line.getId())
                .setR(line.getR())
                .setX(line.getX())
                .setG1(line.getG1())
                .setB1(line.getB1())
                .setG2(line.getG2())
                .setB2(line.getB2());
        line.getOptionalName().ifPresent(adder::setName);

        createBranchBays(target, adder,
                feederBus(line.getTerminal1()), feederBus(line.getTerminal2()),
                busToBbs, nextOrder);

        Line created = target.getLine(line.getId());
        line.getCurrentLimits1().ifPresent(cl -> copyCurrentLimits(cl, created.newCurrentLimits1()));
        line.getCurrentLimits2().ifPresent(cl -> copyCurrentLimits(cl, created.newCurrentLimits2()));
    }

    private static void copyTwoWindingsTransformer(Network target, TwoWindingsTransformer tx,
                                                   Map<String, String> busToBbs,
                                                   Map<String, Integer> nextOrder) {
        if (tx.hasRatioTapChanger() || tx.hasPhaseTapChanger()) {
            throw new UnsupportedOperationException(
                    "Transformer " + tx.getId() + " has a tap changer, which the "
                            + "bus-to-node-breaker converter does not copy yet.");
        }
        Substation ts = tx.getSubstation()
                .map(s -> target.getSubstation(s.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Two-winding transformer without substation: " + tx.getId()));

        TwoWindingsTransformerAdder adder = ts.newTwoWindingsTransformer()
                .setId(tx.getId())
                .setR(tx.getR())
                .setX(tx.getX())
                .setG(tx.getG())
                .setB(tx.getB())
                .setRatedU1(tx.getRatedU1())
                .setRatedU2(tx.getRatedU2())
                .setRatedS(tx.getRatedS());
        tx.getOptionalName().ifPresent(adder::setName);

        createBranchBays(target, adder,
                feederBus(tx.getTerminal1()), feederBus(tx.getTerminal2()),
                busToBbs, nextOrder);

        TwoWindingsTransformer created = target.getTwoWindingsTransformer(tx.getId());
        tx.getCurrentLimits1().ifPresent(cl -> copyCurrentLimits(cl, created.newCurrentLimits1()));
        tx.getCurrentLimits2().ifPresent(cl -> copyCurrentLimits(cl, created.newCurrentLimits2()));
    }

    // ------------------------------------------------------------------
    // Feeder-bay helpers
    // ------------------------------------------------------------------

    private static void createInjectionBay(Network target, InjectionAdder<?, ?> adder,
                                           String busId, Map<String, String> busToBbs,
                                           Map<String, Integer> nextOrder) {
        String bbsId = requireBbs(busId, busToBbs);
        String vlId = target.getBusbarSection(bbsId).getTerminal().getVoltageLevel().getId();
        new CreateFeederBayBuilder()
                .withInjectionAdder(adder)
                .withBusOrBusbarSectionId(bbsId)
                .withInjectionPositionOrder(nextOrder(vlId, nextOrder))
                .withLogOrThrowIfIncorrectPositionOrder(false)
                .build()
                .apply(target);
    }

    private static void createBranchBays(Network target, BranchAdder<?, ?> adder,
                                         String bus1Id, String bus2Id,
                                         Map<String, String> busToBbs,
                                         Map<String, Integer> nextOrder) {
        String bbs1 = requireBbs(bus1Id, busToBbs);
        String bbs2 = requireBbs(bus2Id, busToBbs);
        String vl1 = target.getBusbarSection(bbs1).getTerminal().getVoltageLevel().getId();
        String vl2 = target.getBusbarSection(bbs2).getTerminal().getVoltageLevel().getId();
        new CreateBranchFeederBaysBuilder()
                .withBranchAdder(adder)
                .withBusOrBusbarSectionId1(bbs1)
                .withBusOrBusbarSectionId2(bbs2)
                .withPositionOrder1(nextOrder(vl1, nextOrder))
                .withPositionOrder2(nextOrder(vl2, nextOrder))
                .withLogOrThrowIfIncorrectPositionOrder1(false)
                .withLogOrThrowIfIncorrectPositionOrder2(false)
                .build()
                .apply(target);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static void copyReactiveLimits(Generator src, Generator dst) {
        ReactiveLimits limits = src.getReactiveLimits();
        if (limits instanceof MinMaxReactiveLimits mm) {
            dst.newMinMaxReactiveLimits().setMinQ(mm.getMinQ()).setMaxQ(mm.getMaxQ()).add();
        } else if (limits instanceof ReactiveCapabilityCurve curve) {
            ReactiveCapabilityCurveAdder adder = dst.newReactiveCapabilityCurve();
            for (ReactiveCapabilityCurve.Point p : curve.getPoints()) {
                adder.beginPoint().setP(p.getP()).setMinQ(p.getMinQ()).setMaxQ(p.getMaxQ()).endPoint();
            }
            adder.add();
        }
    }

    private static void copyCurrentLimits(CurrentLimits src, CurrentLimitsAdder dst) {
        dst.setPermanentLimit(src.getPermanentLimit());
        for (LoadingLimits.TemporaryLimit tl : src.getTemporaryLimits()) {
            dst.beginTemporaryLimit()
                    .setName(tl.getName())
                    .setAcceptableDuration(tl.getAcceptableDuration())
                    .setValue(tl.getValue())
                    .setFictitious(tl.isFictitious())
                    .endTemporaryLimit();
        }
        dst.add();
    }

    /** Configured bus a terminal feeds from (works even if disconnected). */
    private static String feederBus(Terminal t) {
        Bus bus = t.getBusBreakerView().getConnectableBus();
        if (bus == null) {
            throw new IllegalStateException(
                    "Terminal of " + t.getConnectable().getId() + " has no connectable bus");
        }
        return bus.getId();
    }

    private static String requireBbs(String busId, Map<String, String> busToBbs) {
        String bbsId = busToBbs.get(busId);
        if (bbsId == null) {
            throw new IllegalStateException("No busbar section created for bus " + busId);
        }
        return bbsId;
    }

    private static int nextOrder(String vlId, Map<String, Integer> nextOrder) {
        int order = nextOrder.getOrDefault(vlId, 1);
        nextOrder.put(vlId, order + 1);
        return order;
    }

    private static void requireBusBreaker(VoltageLevel vl) {
        if (vl.getTopologyKind() != TopologyKind.BUS_BREAKER) {
            throw new UnsupportedOperationException(
                    "Voltage level " + vl.getId() + " is " + vl.getTopologyKind()
                            + "; the converter only handles fully bus-breaker networks.");
        }
    }

    /** Fail fast on equipment types the converter does not reproduce yet. */
    private static void rejectUnsupported(Network source) {
        if (source.getThreeWindingsTransformerCount() > 0) {
            throw new UnsupportedOperationException(
                    "Three-winding transformers are not supported yet ("
                            + source.getThreeWindingsTransformerCount() + " found).");
        }
        if (source.getHvdcLineCount() > 0) {
            throw new UnsupportedOperationException(
                    "HVDC lines are not supported yet (" + source.getHvdcLineCount() + " found).");
        }
        if (source.getStaticVarCompensatorCount() > 0) {
            throw new UnsupportedOperationException(
                    "Static var compensators are not supported yet ("
                            + source.getStaticVarCompensatorCount() + " found).");
        }
        if (source.getDanglingLineCount() > 0) {
            throw new UnsupportedOperationException(
                    "Dangling lines are not supported yet ("
                            + source.getDanglingLineCount() + " found).");
        }
        if (source.getBatteryCount() > 0) {
            throw new UnsupportedOperationException(
                    "Batteries are not supported yet (" + source.getBatteryCount() + " found).");
        }
    }
}
