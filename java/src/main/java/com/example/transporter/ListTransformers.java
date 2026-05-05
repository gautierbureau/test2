package com.example.transporter;

import com.powsybl.iidm.network.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * List 2-winding transformers whose HV side matches a target nominal voltage
 * (or range) and that have at least one generator on the LV bus.  Parameters
 * R, X, G, B are written to a CSV file in SI units (Ω and μS) referred to
 * the HV side.
 *
 * <p>Columns: transformer_id, hv_vl_id, lv_vl_id, rated_u_hv_kv,
 * rated_u_lv_kv, rated_s_mva, generators, r_ohm, x_ohm, g_us, b_us,
 * z_ohm, z_percent, rho_tap.
 */
@Command(
        name = "list-transformers",
        mixinStandardHelpOptions = true,
        description = {
                "List 2-winding transformers with generators on their LV bus at a given",
                "HV nominal voltage (or range), and write their parameters R, X, G, B",
                "in SI units (Ω, μS) referred to the HV side as a CSV table.",
                "",
                "Columns: transformer_id, hv_vl_id, lv_vl_id, rated_u_hv_kv,",
                "  rated_u_lv_kv, rated_s_mva, generators (;-separated),",
                "  r_ohm, x_ohm, g_us, b_us, z_ohm, z_percent, rho_tap"
        }
)
public class ListTransformers implements Callable<Integer> {

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

    @Option(names = "--output", paramLabel = "PATH",
            description = "CSV output file (default: stdout).")
    private Path output;

    private static final String HEADER =
            "transformer_id,hv_vl_id,lv_vl_id," +
            "rated_u_hv_kv,rated_u_lv_kv,rated_s_mva," +
            "generators," +
            "r_ohm,x_ohm,g_us,b_us," +
            "z_ohm,z_percent,rho_tap";

    // =========================================================================
    // Command execution
    // =========================================================================

    @Override
    public Integer call() throws Exception {
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

        Network network = Network.read(networkPath);
        System.err.println("Loaded  : " + networkPath);

        List<String> rows = collectRows(network, hvVMin, hvVMax);
        if (rows.isEmpty()) {
            System.err.println("No transformers with LV generators found at " + hvLabel + ".");
            return 0;
        }

        writeCsv(rows);
        System.err.printf("Written : %s (%d transformer(s))%n",
                output != null ? output : "stdout", rows.size());
        return 0;
    }

    // =========================================================================
    // Data collection
    // =========================================================================

    private List<String> collectRows(Network network, double hvVMin, double hvVMax) {
        var rows   = new ArrayList<String>();
        var seenLv = new HashSet<String>();

        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            double nom1 = tx.getTerminal1().getVoltageLevel().getNominalV();
            double nom2 = tx.getTerminal2().getVoltageLevel().getNominalV();

            VoltageLevel lvVl;
            if (nom1 >= hvVMin && nom1 <= hvVMax) {
                lvVl = tx.getTerminal2().getVoltageLevel();
            } else if (nom2 >= hvVMin && nom2 <= hvVMax) {
                lvVl = tx.getTerminal1().getVoltageLevel();
            } else {
                continue;
            }

            if (!seenLv.add(lvVl.getId())) continue;

            var lvGens = new ArrayList<Generator>();
            lvVl.getGenerators().forEach(lvGens::add);
            lvGens.sort(Comparator.comparing(Identifiable::getId));
            if (lvGens.isEmpty()) continue;

            TransformerTransport.OrientedParams p = TransformerTransport.orientToHv(tx);

            double r = p.r();
            double x = p.x();
            double g = p.g();
            double b = p.b();
            double zOhm = Math.sqrt(r * r + x * x);

            double ratedS = Double.NaN;
            try { ratedS = tx.getRatedS(); } catch (Exception ignored) { }

            String ratedSStr, zPctStr;
            if (!Double.isNaN(ratedS) && ratedS > 0) {
                double zBase = p.ratedHvKv() * p.ratedHvKv() / ratedS;
                ratedSStr = fmt(ratedS);
                zPctStr   = fmt(zOhm / zBase * 100.0);
            } else {
                ratedSStr = "";
                zPctStr   = "";
            }

            String genIds = lvGens.stream()
                    .map(Identifiable::getId)
                    .reduce((a, c) -> a + ";" + c)
                    .orElse("");

            rows.add(csvLine(
                    tx.getId(),
                    p.hvVoltageLevelId(),
                    p.lvVoltageLevelId(),
                    fmt(p.ratedHvKv()),
                    fmt(p.ratedLvKv()),
                    ratedSStr,
                    genIds,
                    fmt(r),
                    fmt(x),
                    fmt(g * 1e6),
                    fmt(b * 1e6),
                    fmt(zOhm),
                    zPctStr,
                    fmt(p.rhoTap())
            ));
        }
        return rows;
    }

    // =========================================================================
    // CSV writing
    // =========================================================================

    private void writeCsv(List<String> rows) throws IOException {
        if (output != null) {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            try (Writer w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writeRows(w, rows);
            }
        } else {
            Writer w = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            writeRows(w, rows);
            w.flush();
        }
    }

    private static void writeRows(Writer w, List<String> rows) throws IOException {
        w.write(HEADER);
        w.write("\r\n");
        for (String row : rows) {
            w.write(row);
            w.write("\r\n");
        }
    }

    private static String csvLine(String... fields) {
        var sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String v = fields[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        System.exit(new CommandLine(new ListTransformers()).execute(args));
    }
}
