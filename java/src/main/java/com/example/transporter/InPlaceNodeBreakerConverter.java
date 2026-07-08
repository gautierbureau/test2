package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateCouplingDeviceBuilder;
import com.powsybl.iidm.modification.topology.MoveFeederBayBuilder;
import com.powsybl.iidm.modification.topology.RemoveVoltageLevelBuilder;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *   voltage level in the same substation with one busbar section per bus.</li>
 *   <li>Move every feeder terminal onto its bus's new busbar section
 *   ({@code MoveFeederBay} builds the disconnector + breaker bay).</li>
 *   <li>Re-create bus couplers as coupling devices between the busbar sections.</li>
 *   <li>Remove the now-empty bus-breaker voltage levels.</li>
 * </ol>
 *
 * <p><b>Prototype scope / known limitations.</b> This is a proof of concept for
 * the "move, don't rebuild" approach: it creates one busbar section per bus (no
 * sectionalizing / one-busbar-per-generator options), and node-breaker voltage
 * levels take a new id ({@value #NB_SUFFIX} suffix) because IIDM fixes a voltage
 * level's topology kind at creation and offers no rename - so the <em>containers</em>
 * (voltage levels, busbar sections) are new while every <em>connectable</em>
 * keeps its id and data. Substation ids are preserved. Voltage levels without a
 * substation and open coupler switches are not handled.
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

    /** Convert {@code network} to node-breaker in place; the same instance is returned. */
    public static Network convert(Network network) {
        List<VoltageLevel> busBreakerVls = new ArrayList<>();
        for (VoltageLevel vl : network.getVoltageLevels()) {
            if (vl.getTopologyKind() == TopologyKind.BUS_BREAKER) {
                busBreakerVls.add(vl);
            }
        }
        if (busBreakerVls.isEmpty()) {
            throw new IllegalArgumentException("network has no bus-breaker voltage level to convert");
        }

        Map<String, String> busToBbs = new HashMap<>();       // bus id -> new busbar section id
        Map<String, String> vlToNbVl = new HashMap<>();       // old VL id -> new node-breaker VL id
        List<Coupler> couplers = new ArrayList<>();

        // 1. Create a node-breaker sibling voltage level with one busbar per bus.
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
                String bbsId = bus.getId() + BBS_SUFFIX;
                nb.getNodeBreakerView().newBusbarSection().setId(bbsId).setNode(node++).add();
                busToBbs.put(bus.getId(), bbsId);
            }
            // Remember couplers (bus-breaker switches) to re-create after the move.
            for (Switch sw : vl.getBusBreakerView().getSwitches()) {
                Bus b1 = vl.getBusBreakerView().getBus1(sw.getId());
                Bus b2 = vl.getBusBreakerView().getBus2(sw.getId());
                couplers.add(new Coupler(b1.getId() + BBS_SUFFIX, b2.getId() + BBS_SUFFIX,
                        sw.getId() + "_"));
            }
        }

        // 2. Plan every feeder move up front (bus-breaker views are still valid),
        //    then apply - so we never read a terminal we have already relocated.
        Set<String> converting = vlToNbVl.keySet();
        List<Move> moves = new ArrayList<>();
        for (Connectable<?> connectable : network.getConnectables()) {
            for (Terminal terminal : connectable.getTerminals()) {
                VoltageLevel vl = terminal.getVoltageLevel();
                if (!converting.contains(vl.getId())) {
                    continue;
                }
                Bus bus = terminal.getBusBreakerView().getConnectableBus();
                if (bus == null) {
                    throw new IllegalStateException(
                            "terminal of " + connectable.getId() + " has no connectable bus");
                }
                moves.add(new Move(connectable.getId(), terminal,
                        vlToNbVl.get(vl.getId()), busToBbs.get(bus.getId())));
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

        // 3. Re-create bus couplers as coupling devices between busbar sections.
        for (Coupler c : couplers) {
            new CreateCouplingDeviceBuilder()
                    .withBusOrBusbarSectionId1(c.bbs1())
                    .withBusOrBusbarSectionId2(c.bbs2())
                    .withSwitchPrefixId(c.switchPrefix())
                    .build()
                    .apply(network);
        }

        // 4. Remove the now-empty bus-breaker voltage levels.
        for (String vlId : new HashSet<>(converting)) {
            new RemoveVoltageLevelBuilder().withVoltageLevelId(vlId).build().apply(network);
        }

        LOGGER.info("Converted '{}' to node-breaker in place: {} voltage level(s), {} busbar section(s)",
                network.getId(), network.getVoltageLevelCount(), busToBbs.size());
        return network;
    }
}
