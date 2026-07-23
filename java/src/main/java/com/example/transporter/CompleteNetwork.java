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
                Fill in network data a case is missing, sized from an AC load flow:
                generator reactive limits, voltage-regulating ratio tap changers, a
                realistic generation mix, apparent-power ratings and active power
                control. With no completion flag, all of those run. Synthetic
                measurements and observability are opt-in (--measurements /
                --observability).
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

    @Option(names = {"--generation-mix"},
            description = "Assign a realistic energy-source mix across the fleet.")
    private boolean generationMix;

    @Option(names = {"--active-power-control"},
            description = "Add participation factors for distributed slack.")
    private boolean activePowerControl;

    @Option(names = {"--rated-s"},
            description = "Fill missing apparent-power ratings on generators and transformers.")
    private boolean ratedS;

    @Option(names = {"--measurements"},
            description = "Add synthetic measurements from the load flow "
                    + "(opt-in; not part of the run-all default).")
    private boolean measurements;

    @Option(names = {"--observability"},
            description = "Mark injections and branches observable "
                    + "(opt-in; not part of the run-all default).")
    private boolean observability;

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

    @Option(names = {"--droop"}, paramLabel = "PCT",
            defaultValue = "" + NetworkCompleter.DEFAULT_DROOP,
            description = "Active power control droop percent (default: ${DEFAULT-VALUE}).")
    private double droop;

    @Option(names = {"--generator-power-factor"}, paramLabel = "PF",
            defaultValue = "" + NetworkCompleter.DEFAULT_GENERATOR_POWER_FACTOR,
            description = "Rated-S generator power factor (default: ${DEFAULT-VALUE}).")
    private double generatorPowerFactor;

    @Option(names = {"--transformer-loading"}, paramLabel = "FRAC",
            defaultValue = "" + NetworkCompleter.DEFAULT_TRANSFORMER_LOADING,
            description = "Base-case transformer loading as a share of rated_s "
                    + "(default: ${DEFAULT-VALUE}).")
    private double transformerLoading;

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

            // Any explicit completion flag disables the run-all default.
            // Measurements and observability are opt-in: never part of run-all.
            boolean selected = reactiveLimits || ratioTapChangers
                    || generationMix || activePowerControl || ratedS
                    || measurements || observability;
            boolean doReactive = reactiveLimits || !selected;
            boolean doTaps = ratioTapChangers || !selected;
            boolean doMix = generationMix || !selected;
            boolean doApc = activePowerControl || !selected;
            boolean doRatedS = ratedS || !selected;
            boolean doMeasurements = measurements;
            boolean doObservability = observability;

            // One load flow shared by all completions.
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
            if (doMix) {
                NetworkCompleter.GenerationMixStats m = NetworkCompleter.setGenerationMix(
                        network, NetworkCompleter.DEFAULT_GENERATION_MIX, true);
                System.out.printf("Generation mix: assigned %d of %d generator(s) "
                                + "(%d already defined) -> %s.%n",
                        m.assigned(), m.generators(), m.skippedDefined(), m.bySource());
            }
            if (doApc) {
                NetworkCompleter.ActivePowerControlStats a =
                        NetworkCompleter.addActivePowerControl(network, droop, true);
                System.out.printf("Active power control: added %d of %d generator(s) "
                                + "(%d already had it).%n",
                        a.added(), a.generators(), a.skippedExisting());
            }
            if (doRatedS) {
                NetworkCompleter.RatedSStats s = NetworkCompleter.addRatedS(network,
                        generatorPowerFactor, transformerLoading, true, false);
                System.out.printf("Rated S: set %d of %d generator(s) and %d of %d "
                                + "transformer(s) (%d transformer(s) had no base-case flow).%n",
                        s.generatorsSet(), s.generators(), s.transformersSet(),
                        s.transformers(), s.transformersNoFlow());
            }
            if (doMeasurements) {
                NetworkCompleter.MeasurementsStats me = NetworkCompleter.addMeasurements(network,
                        NetworkCompleter.DEFAULT_MEASUREMENT_STD_DEV,
                        NetworkCompleter.DEFAULT_STD_DEV_FLOOR, true, false);
                System.out.printf("Measurements: added %d measurement(s) on %d element(s).%n",
                        me.measurements(), me.elements());
            }
            if (doObservability) {
                NetworkCompleter.ObservabilityStats ob = NetworkCompleter.addObservability(network,
                        NetworkCompleter.DEFAULT_OBSERVABILITY_STD_DEV, true);
                System.out.printf("Observability: marked %d injection(s) and %d branch(es) "
                                + "observable.%n", ob.injections(), ob.branches());
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
