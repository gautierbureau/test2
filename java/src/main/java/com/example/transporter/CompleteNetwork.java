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
import java.util.concurrent.Callable;

/**
 * Fill in missing generator reactive limits and/or ratio tap changers on a
 * network, sized from an AC load flow. With neither flag set, both completions
 * run.
 */
@Command(
        name = "complete-network",
        mixinStandardHelpOptions = true,
        version = "complete-network 1.0.0",
        description = """
                Fill in missing generator reactive limits and/or voltage-regulating
                ratio tap changers on a network, sized from an AC load flow.
                """
)
public class CompleteNetwork implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompleteNetwork.class);

    @Option(names = {"-i", "--input"}, description = "Path to the input IIDM network.")
    private Path input;

    @Option(names = {"--ieee14"}, description = "Use the bundled IEEE-14 network.")
    private boolean ieee14;

    @Option(names = {"--ieee14-extended"}, description = "Use the extended IEEE-14 network.")
    private boolean ieee14Extended;

    @Option(names = {"-o", "--output"}, description = "Path to write the completed network (.xiidm).")
    private Path output;

    @Option(names = {"--reactive-limits"},
            description = "Fill missing/placeholder generator reactive limits.")
    private boolean reactiveLimits;

    @Option(names = {"--ratio-tap-changers"},
            description = "Add regulating ratio tap changers where missing.")
    private boolean ratioTapChangers;

    @Option(names = {"--power-factor"}, paramLabel = "PF",
            defaultValue = "" + NetworkCompleter.DEFAULT_POWER_FACTOR,
            description = "Reactive sizing fallback power factor (default: ${DEFAULT-VALUE}).")
    private double powerFactor;

    @Option(names = {"--rtc-steps"}, paramLabel = "N",
            defaultValue = "" + NetworkCompleter.DEFAULT_RTC_STEPS_PER_SIDE,
            description = "Ratio tap changer steps per side (default: ${DEFAULT-VALUE}).")
    private int rtcSteps;

    @Option(names = {"--rtc-step"}, paramLabel = "INC",
            defaultValue = "" + NetworkCompleter.DEFAULT_RTC_STEP_INCREMENT,
            description = "Ratio tap changer step increment (default: ${DEFAULT-VALUE}).")
    private double rtcStep;

    @Override
    public Integer call() {
        try {
            Network network = loadSource();
            if (network == null) {
                return 2;
            }
            LOGGER.info("Loaded network '{}': {} generator(s), {} two-winding transformer(s)",
                    network.getId(), network.getGeneratorCount(),
                    network.getTwoWindingsTransformerCount());

            // With no explicit selection, run both completions.
            boolean doReactive = reactiveLimits || !ratioTapChangers;
            boolean doTaps = ratioTapChangers || !reactiveLimits;

            // One load flow shared by both completions.
            NetworkCompleter.runAcOrThrow(network);

            if (doReactive) {
                NetworkCompleter.ReactiveStats r = NetworkCompleter.addReactiveLimits(network,
                        NetworkCompleter.ReactiveConfig.defaults()
                                .withPowerFactor(powerFactor).withRunLoadFlow(false));
                System.out.printf("Reactive limits: filled %d of %d generator(s) "
                                + "(%d already had a band, %d unsizable).%n",
                        r.filled(), r.generators(), r.skippedExisting(), r.skippedNoSize());
            }
            if (doTaps) {
                NetworkCompleter.RatioTapStats t = NetworkCompleter.addRatioTapChangers(network,
                        NetworkCompleter.RatioTapConfig.defaults()
                                .withStepsPerSide(rtcSteps).withStepIncrement(rtcStep)
                                .withRunLoadFlow(false));
                System.out.printf("Ratio tap changers: added %d of %d transformer(s) "
                                + "(%d already had a tap changer, %d without a base-case voltage).%n",
                        t.added(), t.transformers(), t.skippedExisting(), t.skippedNoVoltage());
            }

            if (output != null) {
                Path outDir = output.toAbsolutePath().getParent();
                if (outDir != null && !Files.exists(outDir)) {
                    Files.createDirectories(outDir);
                }
                LOGGER.info("Writing completed network to {}", output);
                NetworkSerDe.write(network, new ExportOptions(), output);
                System.out.println("Wrote " + output);
            }
            return 0;

        } catch (Exception e) {
            LOGGER.error("Failed to complete network: {}", e.getMessage(), e);
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
            return IeeeCdfNetworkFactory.create14();
        }
        if (ieee14Extended) {
            return ExtendedIeee14Factory.create();
        }
        if (!Files.exists(input)) {
            System.err.println("Input file does not exist: " + input);
            return null;
        }
        return Network.read(input);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new CompleteNetwork()).execute(args));
    }
}
