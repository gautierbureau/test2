package com.example.transporter;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Discover every LV generator connected through a 2-winding transformer whose
 * HV side matches a target nominal voltage (or range), transport all reactive
 * capability curves to the HV bus, save the resulting equivalent network, and
 * write SVG files for each generator.
 *
 * <p>Three SVG files are produced per generator:
 * <ul>
 *   <li>{@code <id>_original.svg}    – original reactive curve (LV side)</li>
 *   <li>{@code <id>_transported.svg} – transported curve (HV side)</li>
 *   <li>{@code <id>_comparison.svg}  – both envelopes superposed with legend</li>
 * </ul>
 *
 * <p>Generator–auxiliary-load pairing is automatic: if the number of loads on
 * the LV bus equals the number of generators they are paired in ascending
 * alphabetical order; otherwise {@code auxLoadId} is {@code null} for all.
 */
@Command(
        name = "process-network",
        mixinStandardHelpOptions = true,
        description = {
                "Discover all generators connected through 2-winding transformers at a",
                "given HV nominal voltage (or range), transport their reactive capability",
                "curves to the HV bus, save the equivalent network, and write SVG plots.",
                "",
                "Three SVG files are produced per generator:",
                "  <id>_original.svg    – original curve (LV side)",
                "  <id>_transported.svg – transported curve (HV side)",
                "  <id>_comparison.svg  – both envelopes superposed"
        }
)
public class ProcessNetwork implements Callable<Integer> {

    @Option(names = "--network", required = true, paramLabel = "PATH",
            description = "Input network file (IIDM/XIIDM).")
    private Path networkPath;

    @Option(names = "--hv-voltage", required = true, arity = "1..2", paramLabel = "KV",
            description = {
                    "HV nominal voltage in kV.",
                    "One value: exact match with ±1 kV tolerance (e.g. --hv-voltage 400).",
                    "Two values: explicit range (e.g. --hv-voltage 380 420)."
            })
    private double[] hvVoltage;

    @Option(names = "--output-network", paramLabel = "PATH",
            description = "Output network file. Defaults to <input>_equivalent.xiidm.")
    private Path outputNetwork;

    @Option(names = "--output-dir", paramLabel = "DIR", defaultValue = ".",
            description = "Directory for SVG output files (default: current directory).")
    private Path outputDir;

    @Option(names = "--n-samples", paramLabel = "N", defaultValue = "25",
            description = "Number of P samples for curve discretisation (default: 25).")
    private int nSamples;

    @Option(names = "--loss-allocation", defaultValue = "INDEPENDENT",
            description = "Loss allocation: INDEPENDENT (default) or COMBINED_PROPORTIONAL.")
    private EquivalentBuilder.LossAllocation lossAllocation;

    // =========================================================================
    // Command execution
    // =========================================================================

    @Override
    public Integer call() throws Exception {

        // ---- Resolve voltage range
        double hvVMin, hvVMax;
        String hvLabel;
        if (hvVoltage.length == 1) {
            hvVMin  = hvVoltage[0] - 1.0;
            hvVMax  = hvVoltage[0] + 1.0;
            hvLabel = hvVoltage[0] + " kV";
        } else {
            hvVMin  = Math.min(hvVoltage[0], hvVoltage[1]);
            hvVMax  = Math.max(hvVoltage[0], hvVoltage[1]);
            hvLabel = hvVMin + "–" + hvVMax + " kV";
        }

        // ---- Load network
        Network network = Network.read(networkPath);
        System.out.println("Loaded  : " + networkPath);

        // ---- Discover transformer groups
        List<TransformerGroup> groups = discoverGroups(network, hvVMin, hvVMax);
        if (groups.isEmpty()) {
            System.out.println("No generators found behind transformers at " + hvLabel + ".");
            return 0;
        }

        int total = groups.stream().mapToInt(g -> g.specs().size()).sum();
        System.out.printf("Found   : %d generator(s) in %d transformer group(s) at %s%n",
                total, groups.size(), hvLabel);
        for (var grp : groups) {
            var sb = new StringBuilder("  TX=").append(grp.txId())
                    .append("  LV=").append(grp.lvVlId()).append("  ");
            grp.specs().forEach(s -> {
                sb.append(s.generatorId());
                if (s.auxLoadId() != null) sb.append("(aux=").append(s.auxLoadId()).append(")");
                sb.append("  ");
            });
            System.out.println(sb.toString().stripTrailing());
        }

        // ---- Prepare output directory
        Files.createDirectories(outputDir);

        // ---- Process transformer groups
        // IMPORTANT: EquivalentBuilder.buildMulti mutates the network in-place.
        // Read original curve data BEFORE calling it.
        var origCurves = new LinkedHashMap<String, OriginalCurveData>();
        var hvCurves   = new LinkedHashMap<String, List<CurveTransporter.HvCurvePoint>>();

        for (var grp : groups) {
            // Save original curves before mutation
            for (var spec : grp.specs()) {
                Generator gen = network.getGenerator(spec.generatorId());
                origCurves.put(spec.generatorId(), readOriginalCurve(gen));
            }
            // Build equivalent (modifies network in-place)
            var result = EquivalentBuilder.buildMulti(
                    network, grp.specs(), grp.txId(), nSamples, lossAllocation);
            // Save transported curves
            for (int i = 0; i < grp.specs().size(); i++) {
                hvCurves.put(grp.specs().get(i).generatorId(),
                        result.perGenerator().get(i).curve());
            }
        }

        // ---- Save equivalent network
        Path outNet = (outputNetwork != null) ? outputNetwork
                : networkPath.resolveSibling(
                        networkPath.getFileName().toString()
                                .replaceFirst("\\.[^.]+$", "") + "_equivalent.xiidm");
        if (outNet.getParent() != null) Files.createDirectories(outNet.getParent());
        NetworkSerDe.write(network, new ExportOptions(), outNet);
        System.out.println("\nNetwork : " + outNet);

        // ---- Write SVGs
        System.out.println("SVGs    : " + outputDir + "/");
        for (var entry : origCurves.entrySet()) {
            String genId = entry.getKey();
            OriginalCurveData orig = entry.getValue();
            List<CurveTransporter.HvCurvePoint> hv = hvCurves.get(genId);

            List<Double> pHv    = hv.stream().map(CurveTransporter.HvCurvePoint::pHv).toList();
            List<Double> qminHv = hv.stream().map(CurveTransporter.HvCurvePoint::minQHv).toList();
            List<Double> qmaxHv = hv.stream().map(CurveTransporter.HvCurvePoint::maxQHv).toList();

            // 1. Original (LV) curve
            Path svgOrig = outputDir.resolve(genId + "_original.svg");
            ReactiveCurveSvg.writeSvg(svgOrig,
                    "Reactive Capability — " + genId,
                    orig.curveType() + " — LV side",
                    orig.pPts(), orig.qminPts(), orig.qmaxPts(),
                    ReactiveCurveSvg.FILL_ORIG, ReactiveCurveSvg.STROKE_ORIG);
            System.out.println("  " + svgOrig.getFileName());

            // 2. Transported (HV) curve
            Path svgHv = outputDir.resolve(genId + "_transported.svg");
            ReactiveCurveSvg.writeSvg(svgHv,
                    "Reactive Capability — HV_" + genId,
                    "Transported Reactive Capability Curve — HV side",
                    pHv, qminHv, qmaxHv,
                    ReactiveCurveSvg.FILL_TRANS, ReactiveCurveSvg.STROKE_TRANS);
            System.out.println("  " + svgHv.getFileName());

            // 3. Comparison overlay
            Path svgCmp = outputDir.resolve(genId + "_comparison.svg");
            ReactiveCurveSvg.writeOverlaySvg(svgCmp,
                    "Reactive Capability Comparison — " + genId, "",
                    List.of(
                            new ReactiveCurveSvg.CurveSpec(
                                    genId + "  (LV — original)",
                                    orig.pPts(), orig.qminPts(), orig.qmaxPts(),
                                    ReactiveCurveSvg.FILL_ORIG, ReactiveCurveSvg.STROKE_ORIG),
                            new ReactiveCurveSvg.CurveSpec(
                                    "HV_" + genId + "  (HV — transported)",
                                    pHv, qminHv, qmaxHv,
                                    ReactiveCurveSvg.FILL_TRANS, ReactiveCurveSvg.STROKE_TRANS)));
            System.out.println("  " + svgCmp.getFileName());
        }

        System.out.printf("%nDone    : %d generator(s) processed.%n", origCurves.size());
        return 0;
    }

    // =========================================================================
    // Discovery
    // =========================================================================

    private record TransformerGroup(
            String txId,
            String hvVlId,
            String lvVlId,
            List<EquivalentBuilder.GeneratorSpec> specs) {}

    private List<TransformerGroup> discoverGroups(Network network,
                                                   double hvVMin, double hvVMax) {
        var groups  = new ArrayList<TransformerGroup>();
        var seenLv  = new HashSet<String>();

        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            double nom1 = tx.getTerminal1().getVoltageLevel().getNominalV();
            double nom2 = tx.getTerminal2().getVoltageLevel().getNominalV();

            VoltageLevel hvVl, lvVl;
            if (nom1 >= hvVMin && nom1 <= hvVMax) {
                hvVl = tx.getTerminal1().getVoltageLevel();
                lvVl = tx.getTerminal2().getVoltageLevel();
            } else if (nom2 >= hvVMin && nom2 <= hvVMax) {
                hvVl = tx.getTerminal2().getVoltageLevel();
                lvVl = tx.getTerminal1().getVoltageLevel();
            } else {
                continue;
            }

            if (!seenLv.add(lvVl.getId())) continue;

            var lvGens = new ArrayList<Generator>();
            lvVl.getGenerators().forEach(lvGens::add);
            lvGens.sort(Comparator.comparing(Identifiable::getId));
            if (lvGens.isEmpty()) continue;

            var lvLoads = new ArrayList<Load>();
            lvVl.getLoads().forEach(lvLoads::add);
            lvLoads.sort(Comparator.comparing(Identifiable::getId));

            var specs = new ArrayList<EquivalentBuilder.GeneratorSpec>();
            if (lvLoads.size() == lvGens.size()) {
                for (int i = 0; i < lvGens.size(); i++) {
                    specs.add(new EquivalentBuilder.GeneratorSpec(
                            lvGens.get(i).getId(),
                            lvLoads.get(i).getId(),
                            "HV_" + lvGens.get(i).getId()));
                }
            } else {
                if (!lvLoads.isEmpty()) {
                    System.out.printf(
                            "[warn] %s: %d generator(s) but %d load(s) on LV bus '%s'. "
                            + "No aux-load association will be made.%n",
                            tx.getId(), lvGens.size(), lvLoads.size(), lvVl.getId());
                }
                for (var gen : lvGens) {
                    specs.add(new EquivalentBuilder.GeneratorSpec(
                            gen.getId(), null, "HV_" + gen.getId()));
                }
            }
            groups.add(new TransformerGroup(
                    tx.getId(), hvVl.getId(), lvVl.getId(), specs));
        }
        return groups;
    }

    // =========================================================================
    // Original curve reader
    // =========================================================================

    private record OriginalCurveData(
            List<Double> pPts,
            List<Double> qminPts,
            List<Double> qmaxPts,
            String curveType) {}

    private OriginalCurveData readOriginalCurve(Generator gen) {
        ReactiveLimits limits = gen.getReactiveLimits();
        if (limits instanceof ReactiveCapabilityCurve rcc) {
            var pts = new ArrayList<>(rcc.getPoints());
            pts.sort(Comparator.comparingDouble(ReactiveCapabilityCurve.Point::getP));
            return new OriginalCurveData(
                    pts.stream().map(ReactiveCapabilityCurve.Point::getP).toList(),
                    pts.stream().map(ReactiveCapabilityCurve.Point::getMinQ).toList(),
                    pts.stream().map(ReactiveCapabilityCurve.Point::getMaxQ).toList(),
                    "Reactive Capability Curve");
        } else {
            var mm   = (MinMaxReactiveLimits) limits;
            double pMin = gen.getMinP(), pMax = gen.getMaxP();
            return new OriginalCurveData(
                    List.of(pMin, pMax),
                    List.of(mm.getMinQ(), mm.getMinQ()),
                    List.of(mm.getMaxQ(), mm.getMaxQ()),
                    "Min-Max Reactive Limits");
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        System.exit(new CommandLine(new ProcessNetwork()).execute(args));
    }
}
