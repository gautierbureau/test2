package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateBranchFeederBaysBuilder;
import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.BusbarSectionPositionAdder;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRange;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRangeAdder;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.extensions.StandbyAutomatonAdder;
import com.powsybl.iidm.network.extensions.VoltageRegulation;
import com.powsybl.iidm.network.extensions.VoltageRegulationAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 * <p>By default each configured bus becomes one busbar section. Every piece of
 * equipment that used to sit directly on a bus-breaker bus is reconnected to
 * the corresponding busbar section through its own <i>feeder bay</i>: a
 * disconnector to the busbar plus a series breaker, created by powsybl's
 * {@link CreateFeederBayBuilder} (injections) and
 * {@link CreateBranchFeederBaysBuilder} (branches). This mirrors how a real
 * node-breaker substation is drawn, where each feeder reaches the busbar
 * through switchgear rather than being wired straight onto the bus.
 *
 * <p>{@link #convert(Network, int)} can instead split each bus into several
 * busbar sections joined by closed coupler breakers (a sectionalized busbar),
 * distributing the feeders across them - see that method for details.
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
 * compensators, boundary lines, VSC and LCC converter stations (all reconnected
 * as injection bays); lines, two- and three-winding transformers with their
 * ratio/phase tap changers and current limits; HVDC lines; tie lines; linear
 * and non-linear shunt compensators. Bus couplers become breakers between the
 * two busbar sections they join. The one remaining unhandled connectable type,
 * {@link Ground}, raises a clear {@link UnsupportedOperationException} so an
 * unsupported input never fails silently.
 */
public final class BusToNodeBreakerConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusToNodeBreakerConverter.class);

    /** Suffix appended to a configured bus id to name its busbar section. */
    public static final String BBS_SUFFIX = "_BBS";

    private BusToNodeBreakerConverter() {
        // Static utility
    }

    /**
     * Tracks the busbar section(s) created for each configured bus and hands
     * them out to feeders. Non-generator feeders are spread round-robin; each
     * generator on a multi-generator bus is given its own section (up to the
     * number of sections) so units stay independently switchable.
     */
    private static final class BusbarAssignment {

        private final int minSectionsPerBus;
        private final boolean oneBusbarPerGenerator;
        private final Map<String, Integer> gensPerBus;
        private final Map<String, List<String>> sections = new HashMap<>();
        private final Map<String, Integer> feederCursor = new HashMap<>();
        private final Map<String, Integer> generatorCursor = new HashMap<>();

        BusbarAssignment(int minSectionsPerBus, boolean oneBusbarPerGenerator,
                         Map<String, Integer> gensPerBus) {
            this.minSectionsPerBus = minSectionsPerBus;
            this.oneBusbarPerGenerator = oneBusbarPerGenerator;
            this.gensPerBus = gensPerBus;
        }

        /** Number of busbar sections to create for a given bus. */
        int sectionCountFor(String busId) {
            // A bus hosting generators gets exactly one busbar section per
            // generator; minSectionsPerBus applies only to buses without
            // generators (the two counts are decoupled).
            if (oneBusbarPerGenerator && gensPerBus.getOrDefault(busId, 0) >= 1) {
                return Math.max(1, gensPerBus.get(busId));
            }
            return Math.max(1, minSectionsPerBus);
        }

        void register(String busId, String bbsId) {
            sections.computeIfAbsent(busId, k -> new ArrayList<>()).add(bbsId);
        }

        private List<String> sectionsOf(String busId) {
            List<String> list = sections.get(busId);
            if (list == null || list.isEmpty()) {
                throw new IllegalStateException("No busbar section created for bus " + busId);
            }
            return list;
        }

        /**
         * Section a feeder attaches to. Generators walk their own cursor so each
         * lands on a distinct section (while sections remain); everything else is
         * round-robined independently.
         */
        String pick(String busId, boolean generatorFeeder) {
            List<String> list = sectionsOf(busId);
            Map<String, Integer> cursor = generatorFeeder ? generatorCursor : feederCursor;
            int i = cursor.merge(busId, 1, Integer::sum) - 1;
            return list.get(i % list.size());
        }

        /** First section of a bus (used for bus-coupler wiring). */
        String first(String busId) {
            return sectionsOf(busId).get(0);
        }

        /** Total number of busbar sections created across all buses. */
        int total() {
            return sections.values().stream().mapToInt(List::size).sum();
        }
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
        return convert(source, 1);
    }

    /**
     * Convert with {@code busbarSectionsPerBus} busbar sections per bus, keeping
     * the default policy of giving each generator its own busbar section.
     */
    public static Network convert(Network source, int busbarSectionsPerBus) {
        return convert(source, busbarSectionsPerBus, true);
    }

    /**
     * Build and return the node-breaker equivalent of {@code source}, splitting
     * each configured bus into busbar sections.
     *
     * <p>When {@code oneBusbarPerGenerator} is set, a bus that hosts generators
     * gets exactly one busbar section per generator - the usual practice for a
     * multi-unit power station, where each unit must be independently switchable
     * - regardless of {@code busbarSectionsPerBus}. Buses without generators get
     * {@code busbarSectionsPerBus} sections. The two counts are decoupled. When
     * the policy is off, every bus gets {@code busbarSectionsPerBus} sections.
     * Feeders are spread round-robin across a bus's sections.
     *
     * <p>Sections of the same bus are chained by <b>closed coupler breakers</b>
     * (BBS_1 —[brk]— BBS_2 —[brk]— …), so they form one electrical node and the
     * load flow is unchanged; opening a coupler later splits the substation
     * exactly as it would in the field.
     *
     * @param source                a fully bus-breaker network
     * @param busbarSectionsPerBus  busbar sections for buses without generators
     *                              ({@code >= 1})
     * @param oneBusbarPerGenerator when {@code true}, a generator bus gets one
     *                              busbar section per generator instead
     * @return a new, electrically identical, all node-breaker network
     * @throws IllegalArgumentException if {@code busbarSectionsPerBus < 1}
     */
    public static Network convert(Network source, int busbarSectionsPerBus,
                                  boolean oneBusbarPerGenerator) {
        if (busbarSectionsPerBus < 1) {
            throw new IllegalArgumentException(
                    "busbarSectionsPerBus must be >= 1, got " + busbarSectionsPerBus);
        }
        Network target = Network.create(source.getId(), source.getSourceFormat());
        target.setCaseDate(source.getCaseDate());
        target.setForecastDistance(source.getForecastDistance());

        // Count generators per configured bus, to size busbars when the
        // one-busbar-per-generator policy is active.
        Map<String, Integer> gensPerBus = new HashMap<>();
        if (oneBusbarPerGenerator) {
            for (Generator g : source.getGenerators()) {
                Bus b = g.getTerminal().getBusBreakerView().getConnectableBus();
                if (b != null) {
                    gensPerBus.merge(b.getId(), 1, Integer::sum);
                }
            }
        }

        // Configured bus id -> its busbar section(s) in the target network.
        BusbarAssignment busToBbs =
                new BusbarAssignment(busbarSectionsPerBus, oneBusbarPerGenerator, gensPerBus);
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
        for (BoundaryLine dl : source.getBoundaryLines()) {
            copyBoundaryLine(target, dl, busToBbs, nextOrder);
        }
        for (VscConverterStation vsc : source.getVscConverterStations()) {
            copyVscConverterStation(target, vsc, busToBbs, nextOrder);
        }
        for (LccConverterStation lcc : source.getLccConverterStations()) {
            copyLccConverterStation(target, lcc, busToBbs, nextOrder);
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

        // 5b. Tie lines - their two half (boundary) lines were recreated in the
        //     injection phase; here they are re-paired into the tie line.
        for (TieLine tl : source.getTieLines()) {
            copyTieLine(target, tl);
        }

        // 6. Tap changers - copied once every terminal exists so a tap changer
        //    that regulates a remote terminal can be re-pointed at it.
        for (TwoWindingsTransformer tx : source.getTwoWindingsTransformers()) {
            copyTapChangers2wt(target, tx);
        }
        for (ThreeWindingsTransformer t3 : source.getThreeWindingsTransformers()) {
            copyTapChangers3wt(target, t3);
        }

        // 6b. Extensions attached to a connectable by id (the rebuild would
        //     otherwise drop them).
        copyExtensions(source, target);

        // 7. Reject anything not handled above, loudly rather than silently.
        rejectUnsupported(source);

        LOGGER.info("Converted '{}' to node-breaker: {} substation(s), {} voltage level(s), "
                        + "{} busbar section(s)",
                target.getId(), target.getSubstationCount(), target.getVoltageLevelCount(),
                busToBbs.total());
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

    /**
     * Create {@code busbarSectionsPerBus} busbar sections per configured bus and
     * record the mapping. When more than one section is requested, consecutive
     * sections of the same bus are joined by a closed coupler breaker so they
     * form one electrical node while being separable in the field.
     */
    private static void createBusbarSections(Network target, VoltageLevel vl,
                                             BusbarAssignment busToBbs) {
        VoltageLevel tvl = target.getVoltageLevel(vl.getId());
        VoltageLevel.NodeBreakerView nbv = tvl.getNodeBreakerView();
        int node = 0;
        int sectionIndex = 0;
        for (Bus bus : vl.getBusBreakerView().getBuses()) {
            int perBus = busToBbs.sectionCountFor(bus.getId());
            int prevNode = -1;
            for (int k = 0; k < perBus; k++) {
                int myNode = node++;
                String bbsId = bus.getId() + BBS_SUFFIX + (perBus > 1 ? "_" + (k + 1) : "");
                var bbs = nbv.newBusbarSection().setId(bbsId).setNode(myNode).add();
                // One busbar per voltage level; sections numbered 1..k across it.
                bbs.newExtension(BusbarSectionPositionAdder.class)
                        .withBusbarIndex(1).withSectionIndex(++sectionIndex).add();
                busToBbs.register(bus.getId(), bbsId);
                if (prevNode >= 0) {
                    nbv.newBreaker()
                            .setId(bus.getId() + "_COUPLER_" + k)
                            .setNode1(prevNode).setNode2(myNode)
                            .setOpen(false).add();
                }
                prevNode = myNode;
            }
        }
    }

    /**
     * Reproduce every bus-breaker switch (bus coupler) as a breaker wired
     * directly between the two busbar sections of the buses it used to join.
     */
    private static void copyBusCouplers(Network target, VoltageLevel vl,
                                        BusbarAssignment busToBbs) {
        VoltageLevel tvl = target.getVoltageLevel(vl.getId());
        VoltageLevel.NodeBreakerView nbv = tvl.getNodeBreakerView();
        for (Switch sw : vl.getBusBreakerView().getSwitches()) {
            Bus b1 = vl.getBusBreakerView().getBus1(sw.getId());
            Bus b2 = vl.getBusBreakerView().getBus2(sw.getId());
            int n1 = nbv.getBusbarSection(busToBbs.first(b1.getId())).getTerminal().getNodeBreakerView().getNode();
            int n2 = nbv.getBusbarSection(busToBbs.first(b2.getId())).getTerminal().getNodeBreakerView().getNode();
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
                                      BusbarAssignment busToBbs,
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

        createInjectionBay(target, adder, feederBus(g.getTerminal()), busToBbs, nextOrder, true);

        Generator created = target.getGenerator(g.getId());
        copyReactiveLimits(g, created);
    }

    private static void copyLoad(Network target, Load l,
                                 BusbarAssignment busToBbs,
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
                                  BusbarAssignment busToBbs,
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
                                    BusbarAssignment busToBbs,
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
                                                 BusbarAssignment busToBbs,
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

    private static void copyBoundaryLine(Network target, BoundaryLine dl,
                                         BusbarAssignment busToBbs,
                                         Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(dl.getTerminal().getVoltageLevel().getId());
        BoundaryLineAdder adder = tvl.newBoundaryLine()
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
        copyFlowsLimitGroups(dl, target.getBoundaryLine(dl.getId()));
    }

    private static void copyVscConverterStation(Network target, VscConverterStation vsc,
                                                BusbarAssignment busToBbs,
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

    private static void copyLccConverterStation(Network target, LccConverterStation lcc,
                                                BusbarAssignment busToBbs,
                                                Map<String, Integer> nextOrder) {
        VoltageLevel tvl = target.getVoltageLevel(lcc.getTerminal().getVoltageLevel().getId());
        LccConverterStationAdder adder = tvl.newLccConverterStation()
                .setId(lcc.getId())
                .setLossFactor(lcc.getLossFactor())
                .setPowerFactor(lcc.getPowerFactor());
        lcc.getOptionalName().ifPresent(adder::setName);

        createInjectionBay(target, adder, feederBus(lcc.getTerminal()), busToBbs, nextOrder);
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    private static void copyLine(Network target, Line line,
                                 BusbarAssignment busToBbs,
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

        copyBranchLimitGroups(line, target.getLine(line.getId()));
    }

    private static void copyTwoWindingsTransformer(Network target, TwoWindingsTransformer tx,
                                                   BusbarAssignment busToBbs,
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

        copyBranchLimitGroups(tx, target.getTwoWindingsTransformer(tx.getId()));
    }

    /**
     * Three-winding transformers have three terminals, so powsybl's
     * {@code CreateBranchFeederBays} (two ends only) cannot help. Each leg's bay
     * is built by hand: a disconnector from the leg's busbar section to a fresh
     * node, then a breaker to the node the leg terminal sits on.
     */
    private static void copyThreeWindingsTransformer(Network target, ThreeWindingsTransformer t3,
                                                     BusbarAssignment busToBbs) {
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
            copyFlowsLimitGroups(srcLeg, dstLeg);
        }
    }

    /**
     * A tie line is a pair of boundary lines joined at a boundary point. Both
     * boundary lines were already recreated as feeder bays; this re-pairs them
     * into the tie line by id.
     */
    private static void copyTieLine(Network target, TieLine tl) {
        TieLineAdder adder = target.newTieLine()
                .setId(tl.getId())
                .setBoundaryLine1(tl.getBoundaryLine1().getId())
                .setBoundaryLine2(tl.getBoundaryLine2().getId());
        tl.getOptionalName().ifPresent(adder::setName);
        adder.add();
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
                                           String busId, BusbarAssignment busToBbs,
                                           Map<String, Integer> nextOrder) {
        createInjectionBay(target, adder, busId, busToBbs, nextOrder, false);
    }

    private static void createInjectionBay(Network target, InjectionAdder<?, ?> adder,
                                           String busId, BusbarAssignment busToBbs,
                                           Map<String, Integer> nextOrder,
                                           boolean generatorFeeder) {
        String bbsId = busToBbs.pick(busId, generatorFeeder);
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
                                         BusbarAssignment busToBbs,
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

    /**
     * Copy every operational-limit group of a branch (both sides) and restore
     * the selected group. The rebuild would otherwise drop all but nothing, so
     * this preserves each current / apparent-power / active-power limit and the
     * active group id.
     */
    private static void copyBranchLimitGroups(Branch<?> src, Branch<?> dst) {
        for (OperationalLimitsGroup g : src.getOperationalLimitsGroups1()) {
            copyLimitGroup(g, dst.newOperationalLimitsGroup1(g.getId()));
        }
        src.getSelectedOperationalLimitsGroupId1().ifPresent(dst::setSelectedOperationalLimitsGroup1);
        for (OperationalLimitsGroup g : src.getOperationalLimitsGroups2()) {
            copyLimitGroup(g, dst.newOperationalLimitsGroup2(g.getId()));
        }
        src.getSelectedOperationalLimitsGroupId2().ifPresent(dst::setSelectedOperationalLimitsGroup2);
    }

    /** Copy every operational-limit group of a single-terminal holder (leg, boundary line). */
    private static void copyFlowsLimitGroups(FlowsLimitsHolder src, FlowsLimitsHolder dst) {
        for (OperationalLimitsGroup g : src.getOperationalLimitsGroups()) {
            copyLimitGroup(g, dst.newOperationalLimitsGroup(g.getId()));
        }
        src.getSelectedOperationalLimitsGroupId().ifPresent(dst::setSelectedOperationalLimitsGroup);
    }

    private static void copyLimitGroup(OperationalLimitsGroup src, OperationalLimitsGroup dst) {
        src.getCurrentLimits().ifPresent(l -> copyLoadingLimits(l, dst.newCurrentLimits()));
        src.getApparentPowerLimits().ifPresent(l -> copyLoadingLimits(l, dst.newApparentPowerLimits()));
        src.getActivePowerLimits().ifPresent(l -> copyLoadingLimits(l, dst.newActivePowerLimits()));
    }

    private static <A extends LoadingLimitsAdder<?, A>> void copyLoadingLimits(
            LoadingLimits src, A dst) {
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

    /**
     * Copy connectable extensions that the rebuild would drop. The powsybl Java
     * model has no generic extension clone, so the ones attached by this
     * project's tooling are copied explicitly; extensions bound to a terminal or
     * to feeder position (which the conversion itself sets) are intentionally
     * left out.
     */
    private static void copyExtensions(Network source, Network target) {
        for (Generator src : source.getGenerators()) {
            ActivePowerControl<Generator> apc = src.getExtension(ActivePowerControl.class);
            Generator dst = target.getGenerator(src.getId());
            if (apc == null || dst == null) {
                continue;
            }
            dst.newExtension(ActivePowerControlAdder.class)
                    .withParticipate(apc.isParticipate())
                    .withDroop(apc.getDroop())
                    .withParticipationFactor(apc.getParticipationFactor())
                    .add();
        }
        // Equipment-injected control extensions the rebuild would otherwise drop.
        for (StaticVarCompensator src : source.getStaticVarCompensators()) {
            StandbyAutomaton sa = src.getExtension(StandbyAutomaton.class);
            StaticVarCompensator dst = target.getStaticVarCompensator(src.getId());
            if (sa == null || dst == null) {
                continue;
            }
            dst.newExtension(StandbyAutomatonAdder.class)
                    .withStandbyStatus(sa.isStandby()).withB0(sa.getB0())
                    .withLowVoltageThreshold(sa.getLowVoltageThreshold())
                    .withLowVoltageSetpoint(sa.getLowVoltageSetpoint())
                    .withHighVoltageThreshold(sa.getHighVoltageThreshold())
                    .withHighVoltageSetpoint(sa.getHighVoltageSetpoint())
                    .add();
        }
        for (Battery src : source.getBatteries()) {
            VoltageRegulation vr = src.getExtension(VoltageRegulation.class);
            Battery dst = target.getBattery(src.getId());
            if (vr == null || dst == null) {
                continue;
            }
            dst.newExtension(VoltageRegulationAdder.class)
                    .withVoltageRegulatorOn(vr.isVoltageRegulatorOn())
                    .withRegulatingTerminal(dst.getTerminal())
                    .add();
        }
        for (HvdcLine src : source.getHvdcLines()) {
            HvdcOperatorActivePowerRange opr = src.getExtension(HvdcOperatorActivePowerRange.class);
            HvdcLine dst = target.getHvdcLine(src.getId());
            if (opr == null || dst == null) {
                continue;
            }
            dst.newExtension(HvdcOperatorActivePowerRangeAdder.class)
                    .withOprFromCS1toCS2(opr.getOprFromCS1toCS2())
                    .withOprFromCS2toCS1(opr.getOprFromCS2toCS1())
                    .add();
        }
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

    /** Pick the busbar section a new (non-generator) feeder on {@code busId} attaches to. */
    private static String requireBbs(String busId, BusbarAssignment busToBbs) {
        return busToBbs.pick(busId, false);
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
        if (source.getGroundCount() > 0) {
            throw new UnsupportedOperationException(
                    "Grounds are not supported yet (" + source.getGroundCount() + " found).");
        }
    }
}
