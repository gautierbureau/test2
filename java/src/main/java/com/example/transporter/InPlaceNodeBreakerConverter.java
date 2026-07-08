package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateCouplingDeviceBuilder;
import com.powsybl.iidm.modification.topology.MoveFeederBayBuilder;
import com.powsybl.iidm.modification.topology.RemoveVoltageLevelBuilder;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prototype bus-breaker to node-breaker converter that works <b>in place</b> by
 * <em>moving</em> feeders rather than rebuilding the network.
 *
 * <p>Where {@link BusToNodeBreakerConverter} builds a brand-new network and
 * re-creates every element (so anything not explicitly copied - operational
 * limits, extensions, properties, aliases - is dropped unless special-cased),
 * this converter keeps the original {@link Connectable}s and only relocates
 * their terminals with powsybl's {@link com.powsybl.iidm.modification.topology.MoveFeederBay}
 * network modification. Because the connectables themselves are never destroyed,
 * <b>every piece of data attached to them survives automatically</b>: limit
 * groups and their selection, all extensions, properties, aliases, reactive
 * limits, tap changers, and so on. No per-attribute copy code is needed.
 *
 * <p>The recipe:
 * <ol>
 *   <li>For each bus-breaker voltage level, create a sibling node-breaker
 *   voltage level in the same substation with the wanted busbar sections per
 *   bus, chained by closed coupling devices.</li>
 *   <li>Move every feeder terminal onto a busbar section of its bus
 *   ({@code MoveFeederBay} builds the disconnector + breaker bay).</li>
 *   <li>Re-create bus couplers as coupling devices between the busbar sections.</li>
 *   <li>Remove the now-empty bus-breaker voltage levels.</li>
 * </ol>
 *
 * <p>The busbar layout matches {@link BusToNodeBreakerConverter}: the two counts
 * are decoupled, so a bus hosting generators gets one section per generator
 * (when {@code oneBusbarPerGenerator}) while buses without generators get
 * {@code busbarSectionsPerBus} sections.
 *
 * <p><b>Prototype limitation.</b> Node-breaker voltage levels take a new id
 * ({@value #NB_SUFFIX} suffix) because IIDM fixes a voltage level's topology
 * kind at creation and offers no rename - so the <em>containers</em> (voltage
 * levels, busbar sections) are new while every <em>connectable</em> keeps its id
 * and data. Substation ids are preserved. Voltage levels without a substation
 * and open coupler switches are not handled.
 */
public final class InPlaceNodeBreakerConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InPlaceNodeBreakerConverter.class);
    public static final String NB_SUFFIX = "_NB";
    private static final String BBS_SUFFIX = "_BBS";

    private InPlaceNodeBreakerConverter() {
    }

    private record Move(String connectableId, Terminal terminal, String targetVl, String targetBbs) {
    }

    private record Coupler(String bbs1, String bbs2, String switchPrefix) {
    }

    /** Convert to node-breaker in place with one busbar section per generator / bus. */
    public static Network convert(Network network) {
        return convert(network, 1, true);
    }

    /**
     * Convert {@code network} to node-breaker in place; the same instance is returned.
     *
     * @param busbarSectionsPerBus busbar sections for buses without generators ({@code >= 1})
     * @param oneBusbarPerGenerator when {@code true}, a generator bus gets one section per generator
     */
    public static Network convert(Network network, int busbarSectionsPerBus,
                                  boolean oneBusbarPerGenerator) {
        if (busbarSectionsPerBus < 1) {
            throw new IllegalArgumentException(
                    "busbarSectionsPerBus must be >= 1, got " + busbarSectionsPerBus);
        }
        List<VoltageLevel> busBreakerVls = new ArrayList<>();
        for (VoltageLevel vl : network.getVoltageLevels()) {
            if (vl.getTopologyKind() == TopologyKind.BUS_BREAKER) {
                busBreakerVls.add(vl);
            }
        }
        if (busBreakerVls.isEmpty()) {
            throw new IllegalArgumentException("network has no bus-breaker voltage level to convert");
        }

        Map<String, Integer> gensPerBus = new HashMap<>();
        if (oneBusbarPerGenerator) {
            for (Generator g : network.getGenerators()) {
                Bus b = g.getTerminal().getBusBreakerView().getConnectableBus();
                if (b != null) {
                    gensPerBus.merge(b.getId(), 1, Integer::sum);
                }
            }
        }

        BusbarPlan plan = new BusbarPlan();
        Map<String, String> vlToNbVl = new HashMap<>();       // old VL id -> new node-breaker VL id
        List<Coupler> intraBusChains = new ArrayList<>();      // sections of one bus, chained closed
        List<Coupler> busCouplers = new ArrayList<>();         // original bus-breaker couplers

        // 1. Create a node-breaker sibling voltage level with the wanted busbars.
        for (VoltageLevel vl : busBreakerVls) {
            Substation substation = vl.getSubstation().orElseThrow(() ->
                    new UnsupportedOperationException(
                            "voltage level without substation is not supported: " + vl.getId()));
            String nbId = vl.getId() + NB_SUFFIX;
            vlToNbVl.put(vl.getId(), nbId);
            VoltageLevel nb = substation.newVoltageLevel()
                    .setId(nbId)
                    .setNominalV(vl.getNominalV())
                    .setLowVoltageLimit(vl.getLowVoltageLimit())
                    .setHighVoltageLimit(vl.getHighVoltageLimit())
                    .setTopologyKind(TopologyKind.NODE_BREAKER)
                    .add();
            int node = 0;
            for (Bus bus : vl.getBusBreakerView().getBuses()) {
                int count = sectionCount(bus.getId(), busbarSectionsPerBus, oneBusbarPerGenerator,
                        gensPerBus);
                String prev = null;
                for (int k = 0; k < count; k++) {
                    String bbsId = bus.getId() + BBS_SUFFIX + (count > 1 ? "_" + (k + 1) : "");
                    nb.getNodeBreakerView().newBusbarSection().setId(bbsId).setNode(node++).add();
                    plan.register(bus.getId(), bbsId);
                    if (prev != null) {
                        intraBusChains.add(new Coupler(prev, bbsId, bbsId + "_LINK_"));
                    }
                    prev = bbsId;
                }
            }
            // Remember bus couplers (bus-breaker switches) to re-create after the move.
            for (Switch sw : vl.getBusBreakerView().getSwitches()) {
                Bus b1 = vl.getBusBreakerView().getBus1(sw.getId());
                Bus b2 = vl.getBusBreakerView().getBus2(sw.getId());
                busCouplers.add(new Coupler(b1.getId(), b2.getId(), sw.getId() + "_"));
            }
        }

        // 2. Chain the sections of each bus with closed coupling devices, so a
        //    multi-section bus stays one electrical node.
        for (Coupler c : intraBusChains) {
            applyCoupling(network, c.bbs1(), c.bbs2(), c.switchPrefix());
        }

        // 3. Plan every feeder move up front (bus-breaker views are still valid),
        //    then apply - so we never read a terminal we have already relocated.
        List<Move> moves = new ArrayList<>();
        for (Connectable<?> connectable : network.getConnectables()) {
            boolean generator = connectable instanceof Generator;
            for (Terminal terminal : connectable.getTerminals()) {
                VoltageLevel vl = terminal.getVoltageLevel();
                if (!vlToNbVl.containsKey(vl.getId())) {
                    continue;
                }
                Bus bus = terminal.getBusBreakerView().getConnectableBus();
                if (bus == null) {
                    throw new IllegalStateException(
                            "terminal of " + connectable.getId() + " has no connectable bus");
                }
                moves.add(new Move(connectable.getId(), terminal,
                        vlToNbVl.get(vl.getId()), plan.pick(bus.getId(), generator)));
            }
        }
        for (Move m : moves) {
            new MoveFeederBayBuilder()
                    .withConnectableId(m.connectableId())
                    .withTargetVoltageLevelId(m.targetVl())
                    .withTargetBusOrBusBarSectionId(m.targetBbs())
                    .withTerminal(m.terminal())
                    .build()
                    .apply(network);
        }

        // 4. Re-create bus couplers as coupling devices between the buses' first sections.
        for (Coupler c : busCouplers) {
            applyCoupling(network, plan.first(c.bbs1()), plan.first(c.bbs2()), c.switchPrefix());
        }

        // 5. Remove the now-empty bus-breaker voltage levels.
        for (String vlId : vlToNbVl.keySet()) {
            new RemoveVoltageLevelBuilder().withVoltageLevelId(vlId).build().apply(network);
        }

        LOGGER.info("Converted '{}' to node-breaker in place: {} voltage level(s), {} busbar section(s)",
                network.getId(), network.getVoltageLevelCount(), plan.total());
        return network;
    }

    private static int sectionCount(String busId, int busbarSectionsPerBus,
                                    boolean oneBusbarPerGenerator, Map<String, Integer> gensPerBus) {
        // A bus hosting generators gets exactly one section per generator;
        // busbarSectionsPerBus applies only to buses without generators.
        if (oneBusbarPerGenerator && gensPerBus.getOrDefault(busId, 0) >= 1) {
            return Math.max(1, gensPerBus.get(busId));
        }
        return Math.max(1, busbarSectionsPerBus);
    }

    private static void applyCoupling(Network network, String bbs1, String bbs2, String prefix) {
        new CreateCouplingDeviceBuilder()
                .withBusOrBusbarSectionId1(bbs1)
                .withBusOrBusbarSectionId2(bbs2)
                .withSwitchPrefixId(prefix)
                .build()
                .apply(network);
    }

    /** Busbar sections of each bus and the round-robin cursors that place feeders. */
    private static final class BusbarPlan {
        private final Map<String, List<String>> sections = new LinkedHashMap<>();
        private final Map<String, Integer> genCursor = new HashMap<>();
        private final Map<String, Integer> feederCursor = new HashMap<>();

        void register(String busId, String bbsId) {
            sections.computeIfAbsent(busId, k -> new ArrayList<>()).add(bbsId);
        }

        String pick(String busId, boolean generator) {
            List<String> secs = sections.get(busId);
            Map<String, Integer> cursor = generator ? genCursor : feederCursor;
            int i = cursor.merge(busId, 1, Integer::sum) - 1;
            return secs.get(i % secs.size());
        }

        String first(String busId) {
            return sections.get(busId).get(0);
        }

        int total() {
            return sections.values().stream().mapToInt(List::size).sum();
        }
    }
}
