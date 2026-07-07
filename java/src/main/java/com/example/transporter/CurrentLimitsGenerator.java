package com.example.transporter;

import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.CurrentLimitsAdder;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Create operational current-limit sets on every branch of a network from a
 * load flow.
 *
 * <p>Many cases carry no operational limits at all — the PEGASE snapshots are
 * the usual example — which leaves security / overload analysis with nothing to
 * check against. This tool fills that gap: it runs an AC load flow, reads the
 * current that actually flows through each branch side, and sizes a complete
 * operational-limit group (a "current limits set") around it — one permanent
 * limit (PATL) plus a configurable ladder of temporary limits (TATL), each with
 * its own acceptable duration ("temporisation").
 *
 * <p>Sizing is <em>per side</em>, because the amperage on the two ends of a
 * transformer differs by the voltage ratio: a current limit is only meaningful
 * next to the current it bounds. For a side carrying {@code I} amps the set is
 *
 * <pre>
 *   permanent          value = I * permanentMargin           duration = -1 (∞)
 *   temporary tier k   value = permanent * tierMargin_k       duration = tierSeconds_k
 * </pre>
 *
 * so with the defaults a branch loaded to {@code I} in the base case sits at
 * {@code 1 / permanentMargin} (80 %) of its permanent limit, with temporary
 * steps above it. The set is stored as a named operational-limit group and, by
 * default, selected as the branch's active group; existing groups are left
 * untouched.
 *
 * <p>This is the Java port of the Python {@code add_current_limits.py} module.
 * Supported branches: lines, two- and three-winding transformers, and boundary
 * (dangling) lines. HVDC lines carry no AC current limits and are ignored.
 */
public final class CurrentLimitsGenerator {

    public static final double DEFAULT_PERMANENT_MARGIN = 1.25;
    public static final String DEFAULT_GROUP_NAME = "LOADFLOW_BASED";

    /** 20 min @ 110 %, 10 min @ 120 %, 1 min @ 140 % of the permanent limit. */
    public static final List<TemporaryTier> DEFAULT_TEMPORARY_TIERS = List.of(
            new TemporaryTier(1200, 1.10),
            new TemporaryTier(600, 1.20),
            new TemporaryTier(60, 1.40));

    private CurrentLimitsGenerator() {
    }

    /** One rung of the temporary-limit ladder. */
    public record TemporaryTier(int durationSeconds, double marginOverPermanent) {
    }

    /** How the generator sizes and places the limit sets. */
    public record Config(double permanentMargin,
                         List<TemporaryTier> temporaryTiers,
                         String groupName,
                         boolean includeTransformers,
                         boolean includeBoundaryLines,
                         double minCurrent,
                         boolean runLoadFlow,
                         boolean onlyMissing,
                         boolean select) {

        public static Config defaults() {
            return new Config(DEFAULT_PERMANENT_MARGIN, DEFAULT_TEMPORARY_TIERS,
                    DEFAULT_GROUP_NAME, true, true, 1.0, true, false, true);
        }

        public Config withPermanentMargin(double m) {
            return new Config(m, temporaryTiers, groupName, includeTransformers,
                    includeBoundaryLines, minCurrent, runLoadFlow, onlyMissing, select);
        }

        public Config withTemporaryTiers(List<TemporaryTier> t) {
            return new Config(permanentMargin, t, groupName, includeTransformers,
                    includeBoundaryLines, minCurrent, runLoadFlow, onlyMissing, select);
        }

        public Config withGroupName(String n) {
            return new Config(permanentMargin, temporaryTiers, n, includeTransformers,
                    includeBoundaryLines, minCurrent, runLoadFlow, onlyMissing, select);
        }

        public Config withIncludeTransformers(boolean b) {
            return new Config(permanentMargin, temporaryTiers, groupName, b,
                    includeBoundaryLines, minCurrent, runLoadFlow, onlyMissing, select);
        }

        public Config withIncludeBoundaryLines(boolean b) {
            return new Config(permanentMargin, temporaryTiers, groupName, includeTransformers,
                    b, minCurrent, runLoadFlow, onlyMissing, select);
        }

        public Config withMinCurrent(double c) {
            return new Config(permanentMargin, temporaryTiers, groupName, includeTransformers,
                    includeBoundaryLines, c, runLoadFlow, onlyMissing, select);
        }

        public Config withRunLoadFlow(boolean b) {
            return new Config(permanentMargin, temporaryTiers, groupName, includeTransformers,
                    includeBoundaryLines, minCurrent, b, onlyMissing, select);
        }

        public Config withOnlyMissing(boolean b) {
            return new Config(permanentMargin, temporaryTiers, groupName, includeTransformers,
                    includeBoundaryLines, minCurrent, runLoadFlow, b, select);
        }

        public Config withSelect(boolean b) {
            return new Config(permanentMargin, temporaryTiers, groupName, includeTransformers,
                    includeBoundaryLines, minCurrent, runLoadFlow, onlyMissing, b);
        }
    }

    /** What the generator produced. */
    public record Stats(String groupName, int branches, int sides,
                        int permanentLimits, int temporaryLimits,
                        int skippedNoFlow, int skippedExisting) {
    }

    /**
     * Size and attach a current-limit set to every branch side of {@code network}
     * from a load flow. The network is modified in place.
     */
    public static Stats apply(Network network, Config cfg) {
        validateConfig(cfg);
        if (cfg.runLoadFlow()) {
            runAcOrThrow(network, null);
        }

        Counters total = new Counters();
        for (Line line : network.getLines()) {
            processElement(line.getId(), branchSides(line), cfg, total);
        }
        if (cfg.includeTransformers()) {
            for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
                processElement(tx.getId(), branchSides(tx), cfg, total);
            }
            for (ThreeWindingsTransformer t3 : network.getThreeWindingsTransformers()) {
                processElement(t3.getId(), legSides(t3), cfg, total);
            }
        }
        if (cfg.includeBoundaryLines()) {
            for (BoundaryLine dl : network.getBoundaryLines()) {
                processElement(dl.getId(), List.of(boundarySide(dl)), cfg, total);
            }
        }
        return new Stats(cfg.groupName(), total.branches, total.sides,
                total.permanentLimits, total.temporaryLimits,
                total.skippedNoFlow, total.skippedExisting);
    }

    // -----------------------------------------------------------------------
    // Per-side handling
    // -----------------------------------------------------------------------

    /** A single side of a branch: its current, existing limits, and group hooks. */
    private interface SideHandle {
        double current();

        boolean hasExistingLimits();

        OperationalLimitsGroup newGroup(String id);

        void select(String id);
    }

    private static SideHandle side(DoubleSupplier current,
                                   Supplier<Collection<OperationalLimitsGroup>> existing,
                                   Function<String, OperationalLimitsGroup> newGroup,
                                   Consumer<String> select) {
        return new SideHandle() {
            @Override
            public double current() {
                return current.getAsDouble();
            }

            @Override
            public boolean hasExistingLimits() {
                return existing.get().stream().anyMatch(g -> !g.isEmpty());
            }

            @Override
            public OperationalLimitsGroup newGroup(String id) {
                return newGroup.apply(id);
            }

            @Override
            public void select(String id) {
                select.accept(id);
            }
        };
    }

    private static List<SideHandle> branchSides(Branch<?> b) {
        return List.of(
                side(() -> b.getTerminal1().getI(), b::getOperationalLimitsGroups1,
                        b::newOperationalLimitsGroup1, b::setSelectedOperationalLimitsGroup1),
                side(() -> b.getTerminal2().getI(), b::getOperationalLimitsGroups2,
                        b::newOperationalLimitsGroup2, b::setSelectedOperationalLimitsGroup2));
    }

    private static List<SideHandle> legSides(ThreeWindingsTransformer t3) {
        List<SideHandle> sides = new ArrayList<>(3);
        for (ThreeWindingsTransformer.Leg leg : t3.getLegs()) {
            sides.add(side(() -> leg.getTerminal().getI(), leg::getOperationalLimitsGroups,
                    leg::newOperationalLimitsGroup, leg::setSelectedOperationalLimitsGroup));
        }
        return sides;
    }

    private static SideHandle boundarySide(BoundaryLine dl) {
        return side(() -> dl.getTerminal().getI(), dl::getOperationalLimitsGroups,
                dl::newOperationalLimitsGroup, dl::setSelectedOperationalLimitsGroup);
    }

    private static void processElement(String id, List<SideHandle> sides, Config cfg,
                                       Counters total) {
        if (cfg.onlyMissing() && sides.stream().anyMatch(SideHandle::hasExistingLimits)) {
            total.skippedExisting++;
            return;
        }
        boolean anySide = false;
        for (SideHandle side : sides) {
            double current = side.current();
            if (!isUsable(current, cfg.minCurrent())) {
                total.skippedNoFlow++;
                continue;
            }
            OperationalLimitsGroup group = side.newGroup(cfg.groupName());
            buildLimits(group.newCurrentLimits(), current, cfg);
            // Select the group only on a side that actually got a limit — pointing
            // a side at a group with no limit there is rejected by IIDM.
            if (cfg.select()) {
                side.select(cfg.groupName());
            }
            total.sides++;
            total.permanentLimits++;
            total.temporaryLimits += cfg.temporaryTiers().size();
            anySide = true;
        }
        if (anySide) {
            total.branches++;
        }
    }

    private static void buildLimits(CurrentLimitsAdder adder, double current, Config cfg) {
        double permanent = current * cfg.permanentMargin();
        adder.setPermanentLimit(permanent);
        for (TemporaryTier tier : cfg.temporaryTiers()) {
            adder.beginTemporaryLimit()
                    .setName(tierName(tier.durationSeconds()))
                    .setAcceptableDuration(tier.durationSeconds())
                    .setValue(permanent * tier.marginOverPermanent())
                    .setFictitious(false)
                    .endTemporaryLimit();
        }
        adder.add();
    }

    /** Readable temporary-limit name, e.g. 1200 -&gt; "IT20min", 90 -&gt; "IT90s". */
    static String tierName(int durationSeconds) {
        if (durationSeconds % 60 == 0) {
            return "IT" + (durationSeconds / 60) + "min";
        }
        return "IT" + durationSeconds + "s";
    }

    private static boolean isUsable(double current, double minCurrent) {
        return Double.isFinite(current) && current >= minCurrent;
    }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    /** Base-case loading of each side against its group's permanent limit. */
    public record LoadingReport(int sides, double maxLoading, int overloaded) {
    }

    /**
     * Load-flow each branch side against the permanent limit stored in
     * {@code groupName} and report the worst base-case loading and how many
     * sides exceed their permanent limit (which, by construction, should be
     * none in the base case).
     */
    public static LoadingReport loadingReport(Network network, String groupName,
                                              boolean runLoadFlow) {
        if (runLoadFlow) {
            runAcOrThrow(network, null);
        }
        Counters c = new Counters();
        double[] max = {0.0};
        for (Line line : network.getLines()) {
            checkLoading(line.getOperationalLimitsGroup1(groupName), line.getTerminal1().getI(), c, max);
            checkLoading(line.getOperationalLimitsGroup2(groupName), line.getTerminal2().getI(), c, max);
        }
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            checkLoading(tx.getOperationalLimitsGroup1(groupName), tx.getTerminal1().getI(), c, max);
            checkLoading(tx.getOperationalLimitsGroup2(groupName), tx.getTerminal2().getI(), c, max);
        }
        for (ThreeWindingsTransformer t3 : network.getThreeWindingsTransformers()) {
            for (ThreeWindingsTransformer.Leg leg : t3.getLegs()) {
                checkLoading(leg.getOperationalLimitsGroup(groupName), leg.getTerminal().getI(), c, max);
            }
        }
        for (BoundaryLine dl : network.getBoundaryLines()) {
            checkLoading(dl.getOperationalLimitsGroup(groupName), dl.getTerminal().getI(), c, max);
        }
        return new LoadingReport(c.sides, c.sides > 0 ? max[0] : Double.NaN, c.overloaded);
    }

    private static void checkLoading(java.util.Optional<OperationalLimitsGroup> group,
                                     double current, Counters c, double[] max) {
        if (group.isEmpty()) {
            return;
        }
        var currentLimits = group.get().getCurrentLimits();
        if (currentLimits.isEmpty() || !Double.isFinite(current)) {
            return;
        }
        double permanent = currentLimits.get().getPermanentLimit();
        if (!(permanent > 0)) {
            return;
        }
        double loading = current / permanent;
        c.sides++;
        max[0] = Math.max(max[0], loading);
        if (loading > 1.0) {
            c.overloaded++;
        }
    }

    // -----------------------------------------------------------------------
    // Load flow
    // -----------------------------------------------------------------------

    private static void runAcOrThrow(Network network, LoadFlowParameters given) {
        List<LoadFlowParameters> candidates = new ArrayList<>();
        if (given != null) {
            candidates.add(given);
        } else {
            // Flat start first, then a DC-based start, which converges large
            // cases where a flat start does not (e.g. case9241pegase).
            for (LoadFlowParameters.VoltageInitMode init : new LoadFlowParameters.VoltageInitMode[]{
                    LoadFlowParameters.VoltageInitMode.UNIFORM_VALUES,
                    LoadFlowParameters.VoltageInitMode.DC_VALUES}) {
                candidates.add(new LoadFlowParameters()
                        .setUseReactiveLimits(true)
                        .setTransformerVoltageControlOn(false)
                        .setDistributedSlack(true)
                        .setVoltageInitMode(init));
            }
        }
        LoadFlowResult result = null;
        for (LoadFlowParameters params : candidates) {
            result = LoadFlow.run(network, params);
            if (result.isFullyConverged()) {
                return;
            }
        }
        throw new IllegalStateException("load flow did not converge: "
                + (result == null ? "no attempt" : result.getStatus()));
    }

    // -----------------------------------------------------------------------
    // Config guard
    // -----------------------------------------------------------------------

    private static void validateConfig(Config cfg) {
        if (!(cfg.permanentMargin() > 1.0)) {
            throw new IllegalArgumentException("permanentMargin must be > 1 (the permanent "
                    + "limit must sit above the base-case current)");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (TemporaryTier tier : cfg.temporaryTiers()) {
            if (tier.durationSeconds() <= 0) {
                throw new IllegalArgumentException(
                        "temporary tier duration must be > 0, got " + tier.durationSeconds());
            }
            if (tier.marginOverPermanent() <= 1.0) {
                throw new IllegalArgumentException("temporary tier margin must be > 1 (above "
                        + "the permanent limit), got " + tier.marginOverPermanent());
            }
            if (!seen.add(tier.durationSeconds())) {
                throw new IllegalArgumentException(
                        "duplicate temporary tier duration " + tier.durationSeconds());
            }
        }
    }

    /** Mutable tally, collapsed into an immutable {@link Stats} at the end. */
    private static final class Counters {
        int branches;
        int sides;
        int permanentLimits;
        int temporaryLimits;
        int skippedNoFlow;
        int skippedExisting;
        int overloaded;
    }
}
