package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.CurrentLimits;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LoadingLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Size load-flow-based current-limit sets on the IEEE-14 and extended IEEE-14
 * networks and check they are per side, cover every branch type, and leave the
 * base case within its own permanent limits — the Java counterpart of the
 * Python {@code test_add_current_limits.py} suite.
 */
class CurrentLimitsGeneratorTest {

    private static final String GROUP = CurrentLimitsGenerator.DEFAULT_GROUP_NAME;

    @Test
    void limitsCreatedOnEveryBranchSide() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.Stats stats =
                CurrentLimitsGenerator.apply(net, CurrentLimitsGenerator.Config.defaults());

        // 17 lines + 3 two-winding transformers, both sides = 40 sides.
        int expectedSides = 2 * (net.getLineCount() + net.getTwoWindingsTransformerCount());
        assertEquals(expectedSides, stats.sides());
        assertEquals(net.getLineCount() + net.getTwoWindingsTransformerCount(), stats.branches());
        assertEquals(expectedSides, stats.permanentLimits());
        assertEquals(expectedSides * 3, stats.temporaryLimits());
        assertEquals(0, stats.skippedNoFlow());
    }

    @Test
    void sizingIsPerSideAndScaled() {
        Network net = IeeeCdfNetworkFactory.create14();
        LoadFlow.run(net);
        CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withRunLoadFlow(false));

        for (TwoWindingsTransformer tx : net.getTwoWindingsTransformers()) {
            double perm1 = permanent(tx.getOperationalLimitsGroup1(GROUP));
            double perm2 = permanent(tx.getOperationalLimitsGroup2(GROUP));
            assertEquals(tx.getTerminal1().getI() * 1.25, perm1, 1e-6, tx.getId() + " side 1");
            assertEquals(tx.getTerminal2().getI() * 1.25, perm2, 1e-6, tx.getId() + " side 2");
            // The two sides carry very different current (voltage ratio).
            assertNotEquals(perm1, perm2, 1.0);
        }
    }

    @Test
    void temporaryTiersLadder() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.Config cfg = CurrentLimitsGenerator.Config.defaults()
                .withPermanentMargin(1.2)
                .withTemporaryTiers(List.of(
                        new CurrentLimitsGenerator.TemporaryTier(900, 1.15),
                        new CurrentLimitsGenerator.TemporaryTier(90, 1.30)));
        CurrentLimitsGenerator.apply(net, cfg);

        Line line = net.getLines().iterator().next();
        CurrentLimits cl = line.getOperationalLimitsGroup1(GROUP).orElseThrow()
                .getCurrentLimits().orElseThrow();
        double permanent = cl.getPermanentLimit();

        LoadingLimits.TemporaryLimit t900 = cl.getTemporaryLimit(900);
        LoadingLimits.TemporaryLimit t90 = cl.getTemporaryLimit(90);
        assertEquals("IT15min", t900.getName());
        assertEquals(permanent * 1.15, t900.getValue(), 1e-6);
        assertEquals("IT90s", t90.getName());     // 90 s is not a whole minute
        assertEquals(permanent * 1.30, t90.getValue(), 1e-6);
    }

    @Test
    void groupSelectedByDefaultAndOptional() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withGroupName("MY_SET"));
        for (Line line : net.getLines()) {
            assertEquals(Optional.of("MY_SET"), line.getSelectedOperationalLimitsGroupId1());
            assertEquals(Optional.of("MY_SET"), line.getSelectedOperationalLimitsGroupId2());
        }

        Network net2 = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.apply(net2, CurrentLimitsGenerator.Config.defaults()
                .withGroupName("MY_SET").withSelect(false));
        Line line = net2.getLines().iterator().next();
        assertNotEquals(Optional.of("MY_SET"), line.getSelectedOperationalLimitsGroupId1());
        // The group still exists, just not active.
        assertTrue(line.getOperationalLimitsGroup1("MY_SET").isPresent());
    }

    @Test
    void baseCaseWithinPermanentLimits() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withPermanentMargin(1.25));
        CurrentLimitsGenerator.LoadingReport report =
                CurrentLimitsGenerator.loadingReport(net, GROUP, false);
        assertEquals(40, report.sides());
        assertEquals(0, report.overloaded());
        // Base case sits at exactly 1 / permanentMargin of the permanent limit.
        assertEquals(1.0 / 1.25, report.maxLoading(), 1e-6);
    }

    @Test
    void allBranchTypesCoveredAndRoundtrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Network net = ExtendedIeee14Factory.create();
        CurrentLimitsGenerator.apply(net, CurrentLimitsGenerator.Config.defaults());

        assertTrue(net.getThreeWindingsTransformerCount() > 0);
        assertTrue(net.getBoundaryLineCount() > 0);
        // Three-winding transformer legs and boundary lines got the group.
        ThreeWindingsTransformer t3 = net.getThreeWindingsTransformers().iterator().next();
        assertTrue(t3.getLeg1().getOperationalLimitsGroup(GROUP).isPresent());

        java.nio.file.Path out = dir.resolve("with_limits.xiidm");
        com.powsybl.iidm.serde.NetworkSerDe.write(net,
                new com.powsybl.iidm.serde.ExportOptions(), out);
        Network reloaded = Network.read(out);
        CurrentLimitsGenerator.LoadingReport report =
                CurrentLimitsGenerator.loadingReport(reloaded, GROUP, true);
        assertTrue(report.sides() > 0);
        assertEquals(0, report.overloaded());
    }

    @Test
    void partialFlowBranchSelectsOnlyLiveSides() {
        // A three-winding transformer with one de-energised leg: that leg carries
        // NaN, so no limit is created there and the group must not be selected on
        // it (IIDM rejects selecting a group that has no limit on a side).
        Network net = ExtendedIeee14Factory.create();
        net.getThreeWindingsTransformer("T3W").getLeg3().getTerminal().disconnect();
        CurrentLimitsGenerator.Stats stats =
                CurrentLimitsGenerator.apply(net, CurrentLimitsGenerator.Config.defaults());
        assertTrue(stats.skippedNoFlow() >= 1);

        ThreeWindingsTransformer t3 = net.getThreeWindingsTransformer("T3W");
        assertEquals(Optional.of(GROUP), t3.getLeg1().getSelectedOperationalLimitsGroupId());
        assertEquals(Optional.of(GROUP), t3.getLeg2().getSelectedOperationalLimitsGroupId());
        assertNotEquals(Optional.of(GROUP), t3.getLeg3().getSelectedOperationalLimitsGroupId());
    }

    @Test
    void onlyMissingPreservesExistingSets() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withGroupName("FIRST"));
        CurrentLimitsGenerator.Stats stats = CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withGroupName("SECOND").withOnlyMissing(true));
        assertEquals(0, stats.branches());
        assertEquals(20, stats.skippedExisting());
        // No SECOND group anywhere.
        Line line = net.getLines().iterator().next();
        assertTrue(line.getOperationalLimitsGroup1("SECOND").isEmpty());
        assertTrue(line.getOperationalLimitsGroup1("FIRST").isPresent());
    }

    @Test
    void rejectsBadConfig() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withPermanentMargin(0.0)));
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withPermanentMargin(0.9)));
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withTemporaryTiers(
                        List.of(new CurrentLimitsGenerator.TemporaryTier(600, 0.9)))));
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withTemporaryTiers(List.of(
                        new CurrentLimitsGenerator.TemporaryTier(600, 1.1),
                        new CurrentLimitsGenerator.TemporaryTier(600, 1.2)))));
    }

    @Test
    void tierNaming() {
        assertEquals("IT20min", CurrentLimitsGenerator.tierName(1200));
        assertEquals("IT1min", CurrentLimitsGenerator.tierName(60));
        assertEquals("IT90s", CurrentLimitsGenerator.tierName(90));
    }

    @Test
    void apparentPowerLimitsAlongsideCurrent() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.Stats stats = CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withLimitTypes(List.of(
                        com.powsybl.iidm.network.LimitType.CURRENT,
                        com.powsybl.iidm.network.LimitType.APPARENT_POWER)));
        // Both types share the one group; twice as many permanents as a single run.
        assertEquals(2 * stats.sides(), stats.permanentLimits());

        Line line = net.getLines().iterator().next();
        OperationalLimitsGroup g = line.getOperationalLimitsGroup1(GROUP).orElseThrow();
        assertTrue(g.getCurrentLimits().isPresent());
        assertTrue(g.getApparentPowerLimits().isPresent());

        for (com.powsybl.iidm.network.LimitType lt : List.of(
                com.powsybl.iidm.network.LimitType.CURRENT,
                com.powsybl.iidm.network.LimitType.APPARENT_POWER)) {
            CurrentLimitsGenerator.LoadingReport report =
                    CurrentLimitsGenerator.loadingReport(net, GROUP, false, lt);
            assertEquals(0, report.overloaded());
            assertEquals(1.0 / CurrentLimitsGenerator.DEFAULT_PERMANENT_MARGIN,
                    report.maxLoading(), 1e-6);
        }
    }

    @Test
    void activePowerLimitType() {
        Network net = IeeeCdfNetworkFactory.create14();
        CurrentLimitsGenerator.apply(net, CurrentLimitsGenerator.Config.defaults()
                .withLimitTypes(List.of(com.powsybl.iidm.network.LimitType.ACTIVE_POWER)));
        Line line = net.getLines().iterator().next();
        OperationalLimitsGroup g = line.getOperationalLimitsGroup1(GROUP).orElseThrow();
        assertTrue(g.getActivePowerLimits().isPresent());
        assertTrue(g.getCurrentLimits().isEmpty());
    }

    @Test
    void seasonalLimitGroups() {
        Network net = IeeeCdfNetworkFactory.create14();
        java.util.Map<String, Double> seasons = new java.util.LinkedHashMap<>();
        seasons.put("WINTER", 1.1);
        seasons.put("SUMMER", 0.9);
        CurrentLimitsGenerator.Stats stats = CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults()
                        .withSeasons(seasons).withSelectedSeason("SUMMER"));
        assertEquals(List.of("WINTER", "SUMMER"), stats.groups());
        assertEquals("SUMMER", stats.groupName());  // active

        Line line = net.getLines().iterator().next();
        double winter = line.getOperationalLimitsGroup1("WINTER").orElseThrow()
                .getCurrentLimits().orElseThrow().getPermanentLimit();
        double summer = line.getOperationalLimitsGroup1("SUMMER").orElseThrow()
                .getCurrentLimits().orElseThrow().getPermanentLimit();
        assertEquals(1.1 / 0.9, winter / summer, 1e-6);
        // The selected (summer) group is active on the branches.
        assertEquals(Optional.of("SUMMER"), line.getSelectedOperationalLimitsGroupId1());

        // Base case loads the summer group at (1 / margin) / 0.9.
        CurrentLimitsGenerator.LoadingReport report =
                CurrentLimitsGenerator.loadingReport(net, "SUMMER", false);
        assertEquals((1.0 / CurrentLimitsGenerator.DEFAULT_PERMANENT_MARGIN) / 0.9,
                report.maxLoading(), 1e-6);
        assertEquals(0, report.overloaded());
    }

    @Test
    void seasonsRejectBadSelection() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults()
                        .withSeasons(java.util.Map.of("WINTER", 1.1))
                        .withSelectedSeason("SPRING")));
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults()
                        .withSeasons(java.util.Map.of("WINTER", -1.0))));
    }

    @Test
    void rejectsEmptyLimitTypes() {
        Network net = IeeeCdfNetworkFactory.create14();
        assertThrows(IllegalArgumentException.class, () -> CurrentLimitsGenerator.apply(net,
                CurrentLimitsGenerator.Config.defaults().withLimitTypes(List.of())));
    }

    private static double permanent(Optional<OperationalLimitsGroup> group) {
        return group.orElseThrow().getCurrentLimits().orElseThrow().getPermanentLimit();
    }
}
