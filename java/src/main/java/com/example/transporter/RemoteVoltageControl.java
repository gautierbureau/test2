package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateBranchFeederBaysBuilder;
import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.BusbarSection;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.GeneratorAdder;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoWindingsTransformerAdder;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.BusbarSectionPositionAdder;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuit;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuitAdder;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Introduce remote voltage control by deporting generators behind a step-up
 * transformer (node-breaker, feeder bays) — the Java port of
 * {@code add_remote_voltage_control.py}.
 *
 * <p>A generator sitting directly on a high-voltage bus is moved onto a new
 * low-voltage bus behind a dedicated generator step-up (GSU) transformer and
 * keeps regulating the high-voltage bus — i.e. remotely, through the
 * transformer — which is what exercises OpenLoadFlow's remote-voltage-control
 * outer loop. Everything is built in node-breaker form with feeder bays, so this
 * must run on a network that is already node-breaker.
 *
 * <p>Deporting an EHV generator can occasionally upset convergence, so
 * generators are deported one at a time with a convergence check: the network is
 * solved after each move and, if it no longer converges, that one deportation is
 * rolled back (via a deep-copy checkpoint) and the generator is skipped. The
 * result therefore always still converges. Because a rollback restores a copy,
 * the (possibly new) network is returned alongside the stats.
 */
public final class RemoteVoltageControl {

    public static final double DEFAULT_HV_THRESHOLD = 200.0;
    public static final double DEFAULT_LV_NOMINAL_V = 20.0;
    public static final double DEFAULT_GSU_X_PU = 0.06;
    public static final double DEFAULT_DEPORT_RATE = 6.0 / 7803.0;

    private RemoteVoltageControl() {
    }

    public record DeportResult(Network network, int deported, int eligible, int skipped,
                               String note) {
    }

    private static LoadFlowParameters dcParams() {
        return new LoadFlowParameters()
                .setDistributedSlack(true)
                .setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
    }

    private static boolean converges(Network network) {
        return LoadFlow.run(network, dcParams()).isFullyConverged();
    }

    /** Generator ids on buses >= hvThreshold (kV), highest voltage first. */
    private static List<String> eligibleGenerators(Network network, double hvThreshold) {
        List<Generator> gens = new ArrayList<>();
        for (Generator g : network.getGenerators()) {
            VoltageLevel vl = g.getTerminal().getVoltageLevel();
            if (vl.getNominalV() >= hvThreshold
                    && vl.getNodeBreakerView().getBusbarSectionStream().findFirst().isPresent()) {
                gens.add(g);
            }
        }
        gens.sort(Comparator.comparingDouble(
                (Generator g) -> g.getTerminal().getVoltageLevel().getNominalV()).reversed());
        return gens.stream().map(Generator::getId).toList();
    }

    /** Deport one generator onto a new LV bus behind a GSU transformer (remote VC). */
    private static void deportOne(Network network, String gid, double lvNominalV, double xPu) {
        Generator g = network.getGenerator(gid);
        VoltageLevel vlHv = g.getTerminal().getVoltageLevel();
        double vhv = vlHv.getNominalV();
        Substation sub = vlHv.getSubstation().orElseThrow(
                () -> new IllegalStateException("generator voltage level without substation: " + gid));
        BusbarSection hvBbs = vlHv.getNodeBreakerView().getBusbarSectionStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no busbar section on " + vlHv.getId()));
        Bus hvBus = g.getTerminal().getBusView().getBus();
        double vTarget = (hvBus != null && Double.isFinite(hvBus.getV()) && hvBus.getV() > 0)
                ? hvBus.getV() : g.getTargetV();
        double s = (Double.isFinite(g.getRatedS()) && g.getRatedS() > 0)
                ? g.getRatedS() : Math.max(Math.abs(g.getMaxP()), 1.0) / 0.85;

        // Capture the generator's parameters and control extensions before removal.
        double maxP = g.getMaxP();
        double minP = g.getMinP();
        double targetP = g.getTargetP();
        double targetQ = g.getTargetQ();
        var energySource = g.getEnergySource();
        @SuppressWarnings("rawtypes")
        ActivePowerControl apc = g.getExtension(ActivePowerControl.class);
        boolean apcParticipate = apc != null && apc.isParticipate();
        double apcDroop = apc != null ? apc.getDroop() : 0.0;
        double apcFactor = apc != null ? apc.getParticipationFactor() : 0.0;
        boolean hasApc = apc != null;
        GeneratorShortCircuit gsc = g.getExtension(GeneratorShortCircuit.class);
        double gscTrans = gsc != null ? gsc.getDirectTransX() : 0.0;
        double gscSub = gsc != null ? gsc.getDirectSubtransX() : 0.0;
        double gscStepUp = gsc != null ? gsc.getStepUpTransformerX() : 0.0;
        boolean hasGsc = gsc != null;

        // New low-voltage level + busbar section behind the GSU transformer.
        String vlLvId = gid + "_GSU_VL";
        String lvBbsId = gid + "_GSU_BBS";
        VoltageLevel vlLv = sub.newVoltageLevel().setId(vlLvId)
                .setTopologyKind(TopologyKind.NODE_BREAKER).setNominalV(lvNominalV).add();
        BusbarSection lvBbs = vlLv.getNodeBreakerView().newBusbarSection()
                .setId(lvBbsId).setNode(0).add();
        lvBbs.newExtension(BusbarSectionPositionAdder.class)
                .withBusbarIndex(1).withSectionIndex(1).add();

        // GSU transformer as a branch feeder bay between the HV and LV busbars.
        double x = xPu * lvNominalV * lvNominalV / s;
        TwoWindingsTransformerAdder txAdder = sub.newTwoWindingsTransformer()
                .setId(gid + "_GSU_TX")
                .setR(x / 20.0).setX(x).setG(0.0).setB(0.0)
                .setRatedU1(vhv).setRatedU2(lvNominalV).setRatedS(s);
        new CreateBranchFeederBaysBuilder()
                .withBranchAdder(txAdder)
                .withBusOrBusbarSectionId1(hvBbs.getId())
                .withBusOrBusbarSectionId2(lvBbsId)
                .withPositionOrder1(10000)
                .withPositionOrder2(1)
                .withLogOrThrowIfIncorrectPositionOrder1(false)
                .withLogOrThrowIfIncorrectPositionOrder2(false)
                .build()
                .apply(network);

        // Move the generator onto the LV busbar, regulating the HV bus remotely.
        network.getGenerator(gid).remove();
        GeneratorAdder genAdder = vlLv.newGenerator()
                .setId(gid)
                .setEnergySource(energySource)
                .setMaxP(maxP).setMinP(minP)
                .setTargetP(targetP).setTargetQ(targetQ).setTargetV(vTarget)
                .setRatedS(s).setVoltageRegulatorOn(true);
        new CreateFeederBayBuilder()
                .withInjectionAdder(genAdder)
                .withBusOrBusbarSectionId(lvBbsId)
                .withInjectionPositionOrder(2)
                .build()
                .apply(network);

        Generator ng = network.getGenerator(gid);
        // Wide reactive band so remote control keeps headroom through the transformer.
        ng.newMinMaxReactiveLimits().setMinQ(-s).setMaxQ(s).add();
        ng.setRegulatingTerminal(hvBbs.getTerminal());

        if (hasApc) {
            ng.newExtension(ActivePowerControlAdder.class)
                    .withParticipate(apcParticipate).withDroop(apcDroop)
                    .withParticipationFactor(apcFactor).add();
        }
        if (hasGsc) {
            ng.newExtension(GeneratorShortCircuitAdder.class)
                    .withDirectTransX(gscTrans).withDirectSubtransX(gscSub)
                    .withStepUpTransformerX(gscStepUp).add();
        }
    }

    /**
     * Deport generators behind a GSU transformer with remote HV voltage control,
     * one at a time with a convergence check and rollback. Operates on a
     * node-breaker network and returns the (possibly reloaded) network.
     */
    public static DeportResult deportGenerators(Network network, Integer count, double hvThreshold,
                                                double lvNominalV, double xPu) {
        if (!converges(network)) {
            return new DeportResult(network, 0, 0, 0, "base did not converge");
        }
        List<String> eligible = eligibleGenerators(network, hvThreshold);
        if (eligible.isEmpty()) {
            return new DeportResult(network, 0, 0, 0, null);
        }
        int busCount = (int) network.getBusView().getBusStream().count();
        int target = count != null ? count : Math.max(1, (int) Math.round(busCount * DEFAULT_DEPORT_RATE));
        target = Math.min(target, eligible.size());

        Network working = network;
        Network checkpoint = NetworkSerDe.copy(working);
        int kept = 0;
        int skipped = 0;
        for (String gid : eligible) {
            if (kept >= target) {
                break;
            }
            deportOne(working, gid, lvNominalV, xPu);
            if (converges(working)) {
                checkpoint = NetworkSerDe.copy(working);  // new last-good state
                kept++;
            } else {
                working = NetworkSerDe.copy(checkpoint);  // roll back this one deportation
                skipped++;
            }
        }
        return new DeportResult(working, kept, eligible.size(), skipped, null);
    }
}
