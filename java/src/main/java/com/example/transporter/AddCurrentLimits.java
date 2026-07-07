package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Create operational current-limit sets on every branch of a network from an AC
 * load flow: one permanent limit plus a ladder of temporary limits, sized per
 * side around the current the load flow computes.
 *
 * <p>Read an IIDM file (or build the bundled IEEE-14 / extended IEEE-14 network),
 * size the limits with {@link CurrentLimitsGenerator}, and write the result.
 * With {@code --validate} the base-case loading of every side is reported
 * against its new permanent limit.
 */
@Command(
        name = "add-current-limits",
        mixinStandardHelpOptions = true,
        version = "add-current-limits 1.0.0",
        description = """
                Create operational current-limit sets on every branch of a network
                from an AC load flow (permanent + temporary tiers, sized per side).
                """
)
public class AddCurrentLimits implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AddCurrentLimits.class);

    @Option(names = {"-i", "--input"},
            description = "Path to the input IIDM network.")
    private Path input;

    @Option(names = {"--ieee14"},
            description = "Use the bundled IEEE-14 network as input instead of --input.")
    private boolean ieee14;

    @Option(names = {"--ieee14-extended"},
            description = "Use the extended IEEE-14 network (adds a 3-winding transformer, "
                    + "boundary lines, etc.) as input.")
    private boolean ieee14Extended;

    @Option(names = {"-o", "--output"},
            description = "Path to write the network with limits (.xiidm).")
    private Path output;

    @Option(names = {"--permanent-margin"}, paramLabel = "M", defaultValue = "1.25",
            description = "Permanent limit / base-case current (default: ${DEFAULT-VALUE}).")
    private double permanentMargin;

    @Option(names = {"--tiers"}, paramLabel = "SECONDS:MARGIN,...",
            description = "Temporary tiers as 'seconds:margin' pairs "
                    + "(default: 1200:1.10,600:1.20,60:1.40).")
    private String tiers;

    @Option(names = {"--group-name"}, defaultValue = CurrentLimitsGenerator.DEFAULT_GROUP_NAME,
            description = "Operational-limit group name (default: ${DEFAULT-VALUE}).")
    private String groupName;

    @Option(names = {"--no-transformers"},
            description = "Do not size limits on transformers.")
    private boolean noTransformers;

    @Option(names = {"--no-boundary-lines"},
            description = "Do not size limits on boundary (dangling) lines.")
    private boolean noBoundaryLines;

    @Option(names = {"--min-current"}, paramLabel = "A", defaultValue = "1.0",
            description = "Skip sides below this current in A (default: ${DEFAULT-VALUE}).")
    private double minCurrent;

    @Option(names = {"--only-missing"},
            description = "Skip branches that already carry operational limits.")
    private boolean onlyMissing;

    @Option(names = {"--no-select"},
            description = "Create the group but do not make it the active set.")
    private boolean noSelect;

    @Option(names = {"--validate"},
            description = "Report base-case loading against the new permanent limits.")
    private boolean validate;

    @Override
    public Integer call() {
        try {
            Network network = loadSource();
            if (network == null) {
                return 2;
            }
            LOGGER.info("Loaded network '{}': {} line(s), {} 2wt, {} 3wt, {} boundary line(s)",
                    network.getId(), network.getLineCount(),
                    network.getTwoWindingsTransformerCount(),
                    network.getThreeWindingsTransformerCount(),
                    network.getBoundaryLineCount());

            CurrentLimitsGenerator.Config cfg = CurrentLimitsGenerator.Config.defaults()
                    .withPermanentMargin(permanentMargin)
                    .withGroupName(groupName)
                    .withIncludeTransformers(!noTransformers)
                    .withIncludeBoundaryLines(!noBoundaryLines)
                    .withMinCurrent(minCurrent)
                    .withOnlyMissing(onlyMissing)
                    .withSelect(!noSelect);
            if (tiers != null) {
                cfg = cfg.withTemporaryTiers(parseTiers(tiers));
            }

            CurrentLimitsGenerator.Stats stats = CurrentLimitsGenerator.apply(network, cfg);
            System.out.printf("Group '%s': limits on %d branch(es), %d side(s) "
                            + "(%d permanent + %d temporary).%n",
                    stats.groupName(), stats.branches(), stats.sides(),
                    stats.permanentLimits(), stats.temporaryLimits());
            if (stats.skippedNoFlow() > 0) {
                System.out.printf("Skipped %d side(s) with no usable flow.%n", stats.skippedNoFlow());
            }
            if (stats.skippedExisting() > 0) {
                System.out.printf("Skipped %d branch(es) that already had limits.%n",
                        stats.skippedExisting());
            }

            if (validate) {
                // The network was already solved by apply()'s load flow.
                CurrentLimitsGenerator.LoadingReport report =
                        CurrentLimitsGenerator.loadingReport(network, groupName, false);
                if (report.sides() > 0) {
                    System.out.printf("Base-case loading: %d side(s) checked, max %.1f %% of "
                                    + "permanent limit, %d overloaded.%n",
                            report.sides(), report.maxLoading() * 100.0, report.overloaded());
                    if (report.overloaded() > 0) {
                        System.err.println("Validation FAILED: a branch exceeds its permanent "
                                + "limit in the base case.");
                        return 1;
                    }
                    System.out.println("Validation OK: every branch sits within its permanent limit.");
                } else {
                    System.out.println("Validation: no branch sides to check.");
                }
            }

            if (output != null) {
                Path outDir = output.toAbsolutePath().getParent();
                if (outDir != null && !Files.exists(outDir)) {
                    Files.createDirectories(outDir);
                }
                LOGGER.info("Writing network with limits to {}", output);
                NetworkSerDe.write(network, new ExportOptions(), output);
                System.out.println("Wrote " + output);
            }
            return 0;

        } catch (Exception e) {
            LOGGER.error("Failed to add current limits: {}", e.getMessage(), e);
            return 1;
        }
    }

    private Network loadSource() {
        int modes = (input != null ? 1 : 0) + (ieee14 ? 1 : 0) + (ieee14Extended ? 1 : 0);
        if (modes != 1) {
            System.err.println("Provide exactly one of --input, --ieee14 or --ieee14-extended.");
            return null;
        }
        if (ieee14) {
            LOGGER.info("Building bundled IEEE-14 network");
            return IeeeCdfNetworkFactory.create14();
        }
        if (ieee14Extended) {
            LOGGER.info("Building extended IEEE-14 network");
            return ExtendedIeee14Factory.create();
        }
        if (!Files.exists(input)) {
            System.err.println("Input file does not exist: " + input);
            return null;
        }
        return Network.read(input);
    }

    /** Parse "1200:1.10,600:1.20,60:1.40" into temporary tiers. */
    static List<CurrentLimitsGenerator.TemporaryTier> parseTiers(String text) {
        List<CurrentLimitsGenerator.TemporaryTier> parsed = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] kv = trimmed.split(":");
            if (kv.length != 2) {
                throw new IllegalArgumentException("bad tier '" + trimmed + "', expected seconds:margin");
            }
            parsed.add(new CurrentLimitsGenerator.TemporaryTier(
                    Integer.parseInt(kv[0].trim()), Double.parseDouble(kv[1].trim())));
        }
        return parsed;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new AddCurrentLimits()).execute(args));
    }
}
