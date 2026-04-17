package com.example.transporter;

import com.powsybl.iidm.network.Network;
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
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "curve-transporter",
        mixinStandardHelpOptions = true,
        version = "curve-transporter 1.0.0",
        description = """
                Read an IIDM network file, replace a generator behind a transformer
                (with its auxiliary load) by an equivalent generator on the HV bus
                carrying the analytically-transported reactive capability curve,
                and write the modified network to an output file.
                """
)
public class Main implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @Option(names = {"-i", "--input"}, required = true,
            description = "Path to the input IIDM network (e.g. .xiidm or .xml).")
    private Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Path to write the equivalent network (.xiidm).")
    private Path output;

    @Option(names = {"-g", "--generator"}, required = true,
            description = "ID of the LV-side generator carrying the original capability curve.")
    private String generatorId;

    @Option(names = {"-t", "--transformer"}, required = true,
            description = "ID of the 2-winding transformer between LV and HV.")
    private String transformerId;

    @Option(names = {"-a", "--aux-load"},
            description = "ID of the auxiliary load on the LV bus (optional).")
    private String auxLoadId;

    @Option(names = {"-n", "--new-id"},
            description = "ID for the new equivalent generator (default: ${DEFAULT-VALUE}).")
    private String newGeneratorId = "EQ_GEN_HV";

    @Option(names = {"-s", "--samples"},
            description = "Number of curve points to generate (default: ${DEFAULT-VALUE}).")
    private int nSamples = 25;

    @Option(names = {"--validate"},
            description = "Run an AC load flow on the equivalent network after building it.")
    private boolean validate = false;

    @Override
    public Integer call() {
        try {
            if (!Files.exists(input)) {
                System.err.println("Input file does not exist: " + input);
                return 2;
            }

            LOGGER.info("Reading network from {}", input);
            Network network = Network.read(input);
            LOGGER.info("Loaded network: {} ({} substations, {} generators, {} loads)",
                    network.getId(),
                    network.getSubstationCount(),
                    network.getGeneratorCount(),
                    network.getLoadCount());

            EquivalentBuilder.BuildResult result = EquivalentBuilder.build(
                    network, generatorId, transformerId, auxLoadId,
                    newGeneratorId, nSamples);

            // Print the transported curve
            System.out.println();
            System.out.println("=".repeat(78));
            System.out.println("Transported reactive capability curve (HV side, MW / MVar)");
            System.out.println("=".repeat(78));
            System.out.printf("%12s %12s %12s%n", "P", "Qmin", "Qmax");
            for (CurveTransporter.HvCurvePoint pt : result.curve()) {
                System.out.printf("%12.3f %12.3f %12.3f%n",
                        pt.pHv(), pt.minQHv(), pt.maxQHv());
            }
            System.out.printf("%nNew equivalent generator: id=%s, target P=%.3f MW, target Q=%.3f MVar%n",
                    result.equivalentGenerator().getId(),
                    result.equivalentGenerator().getTargetP(),
                    result.equivalentGenerator().getTargetQ());

            if (validate) {
                runValidation(network);
            }

            // Make sure the parent directory exists
            Path outDir = output.toAbsolutePath().getParent();
            if (outDir != null && !Files.exists(outDir)) {
                Files.createDirectories(outDir);
            }
            LOGGER.info("Writing equivalent network to {}", output);
            NetworkSerDe.write(network, new ExportOptions(), output);

            return 0;

        } catch (Exception e) {
            LOGGER.error("Error building equivalent: {}", e.getMessage(), e);
            return 1;
        }
    }

    /** Run an AC load flow and print convergence + the new generator's flow. */
    private void runValidation(Network network) {
        LOGGER.info("Running AC load flow on the equivalent network");
        LoadFlowParameters params = new LoadFlowParameters()
                .setUseReactiveLimits(true)
                .setTransformerVoltageControlOn(false)
                .setDistributedSlack(false);
        LoadFlowResult lf = LoadFlow.run(network, params);
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("Validation load flow on equivalent network");
        System.out.println("=".repeat(78));
        System.out.printf("Status: %s%n", lf.getStatus());
        for (LoadFlowResult.ComponentResult cr : lf.getComponentResults()) {
            System.out.printf("  CC %d / SC %d : %s (%d iterations, slack mismatch %.3f MW)%n",
                    cr.getConnectedComponentNum(),
                    cr.getSynchronousComponentNum(),
                    cr.getStatus(),
                    cr.getIterationCount(),
                    cr.getSlackBusActivePowerMismatch());
        }
        var eqGen = network.getGenerator(newGeneratorId);
        // Generator convention: P,Q from getP/getQ are receiver convention,
        // so the actual injection into the bus is the negative.
        double injP = -eqGen.getTerminal().getP();
        double injQ = -eqGen.getTerminal().getQ();
        double vHv = eqGen.getTerminal().getBusBreakerView().getBus().getV();
        System.out.printf("Equivalent generator injection : P = %.3f MW, Q = %.3f MVar at V = %.3f kV%n",
                injP, injQ, vHv);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
