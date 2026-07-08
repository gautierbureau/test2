package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Convert a bus-breaker network into an equivalent node-breaker network:
 * one busbar section per bus, every feeder reconnected through a
 * disconnector + breaker bay.
 *
 * <p>Read an IIDM file (or build the bundled IEEE-14 network with
 * {@code --ieee14}), convert it with {@link BusToNodeBreakerConverter}, and
 * write the result. With {@code --validate}, an AC load flow is run on both
 * networks and the per-voltage-level bus voltages are compared to confirm the
 * conversion is electrically transparent.
 */
@Command(
        name = "convert-to-node-breaker",
        mixinStandardHelpOptions = true,
        version = "convert-to-node-breaker 1.0.0",
        description = """
                Convert a bus-breaker IIDM network into an electrically equivalent
                node-breaker network: one busbar section per bus, every feeder
                reconnected through a disconnector + breaker bay.
                """
)
public class ConvertToNodeBreaker implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConvertToNodeBreaker.class);

    @Option(names = {"-i", "--input"},
            description = "Path to the bus-breaker IIDM network to convert.")
    private Path input;

    @Option(names = {"--ieee14"},
            description = "Use the bundled IEEE-14 network as input instead of --input.")
    private boolean ieee14;

    @Option(names = {"--ieee14-extended"},
            description = "Use the extended IEEE-14 network (adds a battery, SVC, boundary "
                    + "line, non-linear shunt, tap changers, a 3-winding transformer, a VSC "
                    + "HVDC link and a bus coupler) as input.")
    private boolean ieee14Extended;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Path to write the node-breaker network (.xiidm).")
    private Path output;

    @Option(names = {"--busbars-per-bus"}, paramLabel = "N", defaultValue = "1",
            description = "Number of busbar sections to create per bus, chained by closed "
                    + "coupler breakers (default: ${DEFAULT-VALUE}). Feeders are spread across "
                    + "them.")
    private int busbarsPerBus;

    @Option(names = {"--no-generator-busbars"},
            description = "Disable the default policy of giving each generator its own busbar "
                    + "section on buses that host more than one generator.")
    private boolean noGeneratorBusbars;

    @Option(names = {"--in-place"},
            description = "Convert by moving feeders in place (InPlaceNodeBreakerConverter) "
                    + "instead of rebuilding. Preserves limits, extensions, properties and "
                    + "aliases automatically; node-breaker voltage levels take a '_NB' id.")
    private boolean inPlace;

    @Option(names = {"--validate"},
            description = "Run an AC load flow and check the converted network matches.")
    private boolean validate;

    @Override
    public Integer call() {
        try {
            Network source = loadSource();
            if (source == null) {
                return 2;
            }
            LOGGER.info("Loaded network '{}': {} voltage level(s), {} generator(s), {} load(s)",
                    source.getId(), source.getVoltageLevelCount(),
                    source.getGeneratorCount(), source.getLoadCount());

            // The in-place converter mutates the source, so snapshot the
            // reference solution before converting.
            Map<String, Double> reference = (validate && inPlace)
                    ? referenceGeneratorVoltages(source) : null;

            Network target = inPlace
                    ? InPlaceNodeBreakerConverter.convert(source, busbarsPerBus, !noGeneratorBusbars)
                    : BusToNodeBreakerConverter.convert(source, busbarsPerBus, !noGeneratorBusbars);

            System.out.printf("Converted '%s' to node-breaker (%s): %d busbar section(s) "
                            + "over %d voltage level(s).%n",
                    target.getId(), inPlace ? "in place" : "rebuild",
                    target.getBusbarSectionCount(), target.getVoltageLevelCount());

            if (validate) {
                boolean ok = inPlace
                        ? validateInPlace(target, reference)
                        : validate(source, target);
                if (!ok) {
                    System.err.println("Validation FAILED: converted network is not "
                            + "electrically equivalent.");
                    return 1;
                }
                System.out.println("Validation OK: bus voltages match the original.");
            }

            Path outDir = output.toAbsolutePath().getParent();
            if (outDir != null && !Files.exists(outDir)) {
                Files.createDirectories(outDir);
            }
            LOGGER.info("Writing node-breaker network to {}", output);
            NetworkSerDe.write(target, new ExportOptions(), output);
            System.out.println("Wrote " + output);
            return 0;

        } catch (Exception e) {
            LOGGER.error("Conversion failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    private Network loadSource() {
        int modes = (input != null ? 1 : 0) + (ieee14 ? 1 : 0) + (ieee14Extended ? 1 : 0);
        if (modes > 1) {
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
        if (input == null) {
            System.err.println("One of --input <file>, --ieee14 or --ieee14-extended is required.");
            return null;
        }
        if (!Files.exists(input)) {
            System.err.println("Input file does not exist: " + input);
            return null;
        }
        return Network.read(input);
    }

    /**
     * Run an AC load flow on both networks and check every voltage level's bus
     * voltage matches. Angles are compared after normalizing against a common
     * reference bus, since the absolute angle depends on the solver's slack
     * choice.
     */
    private boolean validate(Network source, Network target) {
        System.out.println();
        System.out.println("=".repeat(64));
        System.out.println("Validation load flow (source vs. converted)");
        System.out.println("=".repeat(64));

        // A flat (uniform) start can fail to converge on very large networks -
        // and the node-breaker graph can trip it where the bus-breaker one did
        // not (different slack pick / ordering). Fall back to a DC-based start,
        // which is far more robust on cases like case9241pegase.
        LoadFlowParameters.VoltageInitMode[] inits = {
                LoadFlowParameters.VoltageInitMode.UNIFORM_VALUES,
                LoadFlowParameters.VoltageInitMode.DC_VALUES
        };
        LoadFlowResult srcLf = null;
        LoadFlowResult tgtLf = null;
        LoadFlowParameters.VoltageInitMode usedInit = null;
        for (LoadFlowParameters.VoltageInitMode init : inits) {
            LoadFlowParameters params = new LoadFlowParameters()
                    .setUseReactiveLimits(true)
                    .setTransformerVoltageControlOn(false)
                    .setDistributedSlack(true)
                    .setVoltageInitMode(init);
            srcLf = LoadFlow.run(source, params);
            tgtLf = LoadFlow.run(target, params);
            usedInit = init;
            if (srcLf.isFullyConverged() && tgtLf.isFullyConverged()) {
                break;
            }
        }

        System.out.printf("Init mode : %s%n", usedInit);
        System.out.printf("Source    : %s%n", srcLf.isFullyConverged() ? "CONVERGED" : "NOT CONVERGED");
        System.out.printf("Converted : %s%n", tgtLf.isFullyConverged() ? "CONVERGED" : "NOT CONVERGED");
        if (!srcLf.isFullyConverged() || !tgtLf.isFullyConverged()) {
            return false;
        }

        Map<String, double[]> srcV = busVoltagesByVl(source);
        Map<String, double[]> tgtV = busVoltagesByVl(target);
        if (!srcV.keySet().equals(tgtV.keySet())) {
            return false;
        }

        String refVl = srcV.keySet().iterator().next();
        double srcRef = srcV.get(refVl)[1];
        double tgtRef = tgtV.get(refVl)[1];

        double maxDv = 0.0;
        double maxDa = 0.0;
        for (String vlId : srcV.keySet()) {
            double dv = Math.abs(srcV.get(vlId)[0] - tgtV.get(vlId)[0]);
            double da = Math.abs((srcV.get(vlId)[1] - srcRef) - (tgtV.get(vlId)[1] - tgtRef));
            maxDv = Math.max(maxDv, dv);
            maxDa = Math.max(maxDa, da);
        }
        System.out.printf("Max |dV|     : %.3e kV%n", maxDv);
        System.out.printf("Max |dAngle| : %.3e deg (referenced to %s)%n", maxDa, refVl);

        // Tolerances sit ~1000x below what a topological error would produce,
        // while absorbing solver round-off and control-loop (SVC, tap changer)
        // settling that differs with the internal element ordering.
        return maxDv < 1e-2 && maxDa < 1e-1;
    }

    private static Map<String, double[]> busVoltagesByVl(Network net) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (VoltageLevel vl : net.getVoltageLevels()) {
            for (Bus bus : vl.getBusView().getBuses()) {
                out.put(vl.getId(), new double[]{bus.getV(), bus.getAngle()});
            }
        }
        return out;
    }

    /**
     * In-place validation: the conversion mutates the network and renames its
     * voltage levels, so we compare per-generator bus voltage against a
     * reference solution captured before the conversion (generators keep their
     * ids). Angles are not comparable across the topology change, so only the
     * magnitude is checked.
     */
    private Map<String, Double> referenceGeneratorVoltages(Network net) {
        runWithFallback(net);
        return generatorVoltages(net);
    }

    private boolean validateInPlace(Network converted, Map<String, Double> reference) {
        System.out.println();
        System.out.println("=".repeat(64));
        System.out.println("Validation load flow (in-place: before vs. after)");
        System.out.println("=".repeat(64));
        LoadFlowResult lf = runWithFallback(converted);
        System.out.printf("Converted : %s%n", lf.isFullyConverged() ? "CONVERGED" : "NOT CONVERGED");
        if (!lf.isFullyConverged()) {
            return false;
        }
        Map<String, Double> after = generatorVoltages(converted);
        double maxDv = 0.0;
        for (Map.Entry<String, Double> e : reference.entrySet()) {
            Double a = after.get(e.getKey());
            if (a != null) {
                maxDv = Math.max(maxDv, Math.abs(e.getValue() - a));
            }
        }
        System.out.printf("Max |dV| : %.3e kV (per generator)%n", maxDv);
        return maxDv < 1e-2;
    }

    private static LoadFlowResult runWithFallback(Network net) {
        LoadFlowResult result = null;
        for (LoadFlowParameters.VoltageInitMode init : new LoadFlowParameters.VoltageInitMode[]{
                LoadFlowParameters.VoltageInitMode.UNIFORM_VALUES,
                LoadFlowParameters.VoltageInitMode.DC_VALUES}) {
            LoadFlowParameters params = new LoadFlowParameters()
                    .setUseReactiveLimits(true)
                    .setTransformerVoltageControlOn(false)
                    .setDistributedSlack(true)
                    .setVoltageInitMode(init);
            result = LoadFlow.run(net, params);
            if (result.isFullyConverged()) {
                return result;
            }
        }
        return result;
    }

    private static Map<String, Double> generatorVoltages(Network net) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Generator g : net.getGenerators()) {
            Bus bus = g.getTerminal().getBusView().getBus();
            if (bus != null) {
                out.put(g.getId(), bus.getV());
            }
        }
        return out;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ConvertToNodeBreaker()).execute(args));
    }
}
