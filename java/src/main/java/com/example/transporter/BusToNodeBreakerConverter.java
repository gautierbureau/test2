package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateBranchFeederBaysBuilder;
import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
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
 * <p>Supported equipment: generators, loads, batteries, static var
 * compensators, dangling lines and VSC converter stations (all reconnected as
 * injection bays); lines, two- and three-winding transformers with their
 * ratio/phase tap changers and current limits; HVDC lines; linear and
 * non-linear shunt compensators. Bus couplers become breakers between the two
 * busbar sections they join. Element types not yet handled (LCC converter
 * stations, tie lines) raise a clear {@link UnsupportedOperationException} so
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
        for (Battery b : source.getBatteries()) {
            copyBattery(target, b, busToBbs, nextOrder);
        }
        for (StaticVarCompensator svc : source.getStaticVarCompensators()) {
            copyStaticVarCompensator(target, svc, busToBbs, nextOrder);
        }
        for (DanglingLine dl : source.getDanglingLines()) {
            copyDanglingLine(target, dl, busToBbs, nextOrder);
        }
        for (VscConverterStation vsc : source.getVscConverterStations()) {
            copyVscConverterStation(target, vsc, busToBbs, nextOrder);
        }

        // 4. Branches (two- and three-winding transformers, lines).
        for (Line line : source.getLines()) {
            copyLine(target, line, busToBbs, nextOrder);
        }
        for (TwoWindingsTransformer tx : source.getTwoWindingsTransformers()) {
            copyTwoWindingsTransformer(target, tx, busToBbs, nextOrder);
        }
        for (ThreeWindingsTransformer t3 : source.getThreeWindingsTransformers()) {
            copyThreeWindingsTransformer(target, t3, busToBbs);
        }

        // 5. HVDC lines - both converter stations now exist as injections.
        for (HvdcLine hvdc : source.getHvdcLines()) {
            copyHvdcLine(target, hvdc);
        }

        // 6. Tap changers - copied once every terminal exists so a tap changer
        //    that regulates a remote terminal can be re-pointed at it.
        for (TwoWindingsTransformer tx : source.getTwoWindingsTransformers()) {
            copyTapChangers2wt(target, tx);
        }
        for (ThreeWindingsTransformer t3 : source.getThreeWindingsTransformers()) {
            copyTapChangers3wt(target, t3);
        }

        // 7. Reject anything not handled above, loudly rather than silently.
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

    private static void copyBattery(Network target, Battery b,
                                    Map<String, String> busToBbs,
                                    Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(b.getTerminal().getVoltageLevel().getId());
        BatteryAdder adder = tvl.newBattery()
                .setId(b.getId())
                .setMinP(b.getMinP())
                .setMaxP(b.getMaxP())
                .setTargetP(b.getTargetP())
                .setTargetQ(b.getTargetQ());
        b.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(b.getTerminal()), busToBbs, nextOrder);
        copyReactiveLimits(b, target.getBattery(b.getId()));
    }

    private static void copyStaticVarCompensator(Network target, StaticVarCompensator svc,
                                                 Map<String, String> busToBbs,
                                                 Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(svc.getTerminal().getVoltageLevel().getId());
        StaticVarCompensatorAdder adder = tvl.newStaticVarCompensator()
                .setId(svc.getId())
                .setBmin(svc.getBmin())
                .setBmax(svc.getBmax())
                .setVoltageSetpoint(svc.getVoltageSetpoint())
                .setReactivePowerSetpoint(svc.getReactivePowerSetpoint())
                .setRegulating(svc.isRegulating());
        if (svc.getRegulationMode() != null) {
            adder.setRegulationMode(svc.getRegulationMode());
        }
        svc.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(svc.getTerminal()), busToBbs, nextOrder);
    }

    private static void copyDanglingLine(Network target, DanglingLine dl,
                                         Map<String, String> busToBbs,
                                         Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(dl.getTerminal().getVoltageLevel().getId());
        DanglingLineAdder adder = tvl.newDanglingLine()
                .setId(dl.getId())
                .setP0(dl.getP0())
                .setQ0(dl.getQ0())
                .setR(dl.getR())
                .setX(dl.getX())
                .setG(dl.getG())
                .setB(dl.getB());
        if (dl.getPairingKey() != null) {
            adder.setPairingKey(dl.getPairingKey());
        }
        dl.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(dl.getTerminal()), busToBbs, nextOrder);
    }

    private static void copyVscConverterStation(Network target, VscConverterStation vsc,
                                                Map<String, String> busToBbs,
                                                Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(vsc.getTerminal().getVoltageLevel().getId());
        VscConverterStationAdder adder = tvl.newVscConverterStation()
                .setId(vsc.getId())
                .setLossFactor(vsc.getLossFactor())
                .setVoltageRegulatorOn(vsc.isVoltageRegulatorOn())
                .setVoltageSetpoint(vsc.getVoltageSetpoint())
                .setReactivePowerSetpoint(vsc.getReactivePowerSetpoint());
        vsc.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(vsc.getTerminal()), busToBbs, nextOrder);
        copyReactiveLimits(vsc, target.getVscConverterStation(vsc.getId()));
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

    /**
     * Three-winding transformers have three terminals, so powsybl's
     * {@code CreateBranchFeederBays} (two ends only) cannot help. Each leg's bay
     * is built by hand: a disconnector from the leg's busbar section to a fresh
     * node, then a breaker to the node the leg terminal sits on.
     */
    private static void copyThreeWindingsTransformer(Network target, ThreeWindingsTransformer t3,
                                                     Map<String, String> busToBbs) {
        Substation ts = t3.getSubstation()
                .map(s -> target.getSubstation(s.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Three-winding transformer without substation: " + t3.getId()));

        ThreeWindingsTransformerAdder adder = ts.newThreeWindingsTransformer()
                .setId(t3.getId())
                .setRatedU0(t3.getRatedU0());
        t3.getOptionalName().ifPresent(adder::setName);

        int side = 1;
        for (ThreeWindingsTransformer.Leg leg : t3.getLegs()) {
            VoltageLevel tvl = target.getVoltageLevel(leg.getTerminal().getVoltageLevel().getId());
            VoltageLevel.NodeBreakerView nbv = tvl.getNodeBreakerView();
            int busbarNode = busbarNode(target, requireBbs(feederBus(leg.getTerminal()), busToBbs));
            int mid = nbv.getMaximumNodeIndex() + 1;
            int equip = mid + 1;
            nbv.newDisconnector()
                    .setId(t3.getId() + "_DISC_" + side).setNode1(busbarNode).setNode2(mid)
                    .setOpen(false).add();
            nbv.newBreaker()
                    .setId(t3.getId() + "_BRK_" + side).setNode1(mid).setNode2(equip)
                    .setOpen(false).add();

            ThreeWindingsTransformerAdder.LegAdder legAdder = newLeg(adder, side)
                    .setVoltageLevel(tvl.getId())
                    .setNode(equip)
                    .setR(leg.getR())
                    .setX(leg.getX())
                    .setG(leg.getG())
                    .setB(leg.getB())
                    .setRatedU(leg.getRatedU());
            if (!Double.isNaN(leg.getRatedS())) {
                legAdder.setRatedS(leg.getRatedS());
            }
            legAdder.add();
            side++;
        }
        adder.add();

        ThreeWindingsTransformer created = target.getThreeWindingsTransformer(t3.getId());
        for (int s = 1; s <= 3; s++) {
            ThreeWindingsTransformer.Leg srcLeg = t3.getLeg(ThreeSides.valueOf(s));
            ThreeWindingsTransformer.Leg dstLeg = created.getLeg(ThreeSides.valueOf(s));
            srcLeg.getCurrentLimits().ifPresent(cl -> copyCurrentLimits(cl, dstLeg.newCurrentLimits()));
        }
    }

    private static void copyHvdcLine(Network target, HvdcLine hvdc) {
        target.newHvdcLine()
                .setId(hvdc.getId())
                .setR(hvdc.getR())
                .setNominalV(hvdc.getNominalV())
                .setConvertersMode(hvdc.getConvertersMode())
                .setActivePowerSetpoint(hvdc.getActivePowerSetpoint())
                .setMaxP(hvdc.getMaxP())
                .setConverterStationId1(hvdc.getConverterStation1().getId())
                .setConverterStationId2(hvdc.getConverterStation2().getId())
                .add();
    }

    // ------------------------------------------------------------------
    // Tap changers (copied after every terminal exists)
    // ------------------------------------------------------------------

    private static void copyTapChangers2wt(Network target, TwoWindingsTransformer tx) {
        TwoWindingsTransformer created = target.getTwoWindingsTransformer(tx.getId());
        if (tx.hasRatioTapChanger()) {
            copyRatioTapChanger(target, tx.getRatioTapChanger(), created.newRatioTapChanger());
        }
        if (tx.hasPhaseTapChanger()) {
            copyPhaseTapChanger(target, tx.getPhaseTapChanger(), created.newPhaseTapChanger());
        }
    }

    private static void copyTapChangers3wt(Network target, ThreeWindingsTransformer t3) {
        ThreeWindingsTransformer created = target.getThreeWindingsTransformer(t3.getId());
        for (int s = 1; s <= 3; s++) {
            ThreeWindingsTransformer.Leg srcLeg = t3.getLeg(ThreeSides.valueOf(s));
            ThreeWindingsTransformer.Leg dstLeg = created.getLeg(ThreeSides.valueOf(s));
            if (srcLeg.hasRatioTapChanger()) {
                copyRatioTapChanger(target, srcLeg.getRatioTapChanger(), dstLeg.newRatioTapChanger());
            }
            if (srcLeg.hasPhaseTapChanger()) {
                copyPhaseTapChanger(target, srcLeg.getPhaseTapChanger(), dstLeg.newPhaseTapChanger());
            }
        }
    }

    private static void copyRatioTapChanger(Network target, RatioTapChanger src,
                                            RatioTapChangerAdder adder) {
        adder.setLowTapPosition(src.getLowTapPosition())
                .setTapPosition(src.getTapPosition())
                .setLoadTapChangingCapabilities(src.hasLoadTapChangingCapabilities())
                .setRegulating(src.isRegulating())
                .setTargetDeadband(src.getTargetDeadband())
                .setTargetV(src.getTargetV());
        if (src.getRegulationMode() != null) {
            adder.setRegulationMode(src.getRegulationMode());
            adder.setRegulationValue(src.getRegulationValue());
        }
        for (int p = src.getLowTapPosition(); p <= src.getHighTapPosition(); p++) {
            RatioTapChangerStep step = src.getStep(p);
            adder.beginStep()
                    .setRho(step.getRho())
                    .setR(step.getR()).setX(step.getX())
                    .setG(step.getG()).setB(step.getB())
                    .endStep();
        }
        Terminal regTerm = mapTerminal(target, src.getRegulationTerminal());
        if (regTerm != null) {
            adder.setRegulationTerminal(regTerm);
        }
        adder.add();
    }

    private static void copyPhaseTapChanger(Network target, PhaseTapChanger src,
                                            PhaseTapChangerAdder adder) {
        adder.setLowTapPosition(src.getLowTapPosition())
                .setTapPosition(src.getTapPosition())
                .setRegulating(src.isRegulating())
                .setTargetDeadband(src.getTargetDeadband())
                .setRegulationMode(src.getRegulationMode())
                .setRegulationValue(src.getRegulationValue());
        for (int p = src.getLowTapPosition(); p <= src.getHighTapPosition(); p++) {
            PhaseTapChangerStep step = src.getStep(p);
            adder.beginStep()
                    .setAlpha(step.getAlpha())
                    .setRho(step.getRho())
                    .setR(step.getR()).setX(step.getX())
                    .setG(step.getG()).setB(step.getB())
                    .endStep();
        }
        Terminal regTerm = mapTerminal(target, src.getRegulationTerminal());
        if (regTerm != null) {
            adder.setRegulationTerminal(regTerm);
        }
        adder.add();
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

    private static void copyReactiveLimits(ReactiveLimitsHolder src, ReactiveLimitsHolder dst) {
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

    private static int busbarNode(Network target, String bbsId) {
        return target.getBusbarSection(bbsId).getTerminal().getNodeBreakerView().getNode();
    }

    private static ThreeWindingsTransformerAdder.LegAdder newLeg(
            ThreeWindingsTransformerAdder adder, int side) {
        return switch (side) {
            case 1 -> adder.newLeg1();
            case 2 -> adder.newLeg2();
            case 3 -> adder.newLeg3();
            default -> throw new IllegalArgumentException("Invalid leg side: " + side);
        };
    }

    /**
     * Find, in the target network, the terminal that corresponds to a source
     * terminal - same connectable id, same side. Returns {@code null} if the
     * source terminal is {@code null} or its connectable was not recreated.
     */
    private static Terminal mapTerminal(Network target, Terminal src) {
        if (src == null) {
            return null;
        }
        Connectable<?> srcConn = src.getConnectable();
        Connectable<?> dstConn = target.getConnectable(srcConn.getId());
        if (dstConn == null) {
            return null;
        }
        List<? extends Terminal> srcTerms = srcConn.getTerminals();
        List<? extends Terminal> dstTerms = dstConn.getTerminals();
        for (int i = 0; i < srcTerms.size() && i < dstTerms.size(); i++) {
            if (srcTerms.get(i) == src) {
                return dstTerms.get(i);
            }
        }
        return null;
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
        long lccCount = source.getHvdcConverterStationStream()
                .filter(s -> s.getHvdcType() == HvdcConverterStation.HvdcType.LCC)
                .count();
        if (lccCount > 0) {
            throw new UnsupportedOperationException(
                    "LCC converter stations are not supported yet (" + lccCount + " found).");
        }
        if (source.getTieLineCount() > 0) {
            throw new UnsupportedOperationException(
                    "Tie lines are not supported yet (" + source.getTieLineCount() + " found).");
        }
    }
}
