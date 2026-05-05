package com.example.transporter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates SVG files showing the reactive capability envelope of a generator.
 *
 * <p>Uses only standard JDK XML APIs ({@code javax.xml.parsers} / {@code org.w3c.dom} /
 * {@code javax.xml.transform}) – no external SVG or charting library is required.
 *
 * <p>The layout constants and drawing algorithm mirror {@code plot_reactive_curve.py}
 * so both implementations produce visually equivalent output.
 */
public final class ReactiveCurveSvg {

    // ---- Layout (same constants as plot_reactive_curve.py) ------------------
    private static final int W = 700, H = 500;
    private static final int ML = 80, MR = 30, MT = 65, MB = 65;
    private static final int PW = W - ML - MR;
    private static final int PH = H - MT - MB;

    // ---- Colour palette -----------------------------------------------------
    private static final String C_AXIS = "#333333";
    private static final String C_GRID = "#e0e0e0";
    private static final String C_ZERO = "#aaaaaa";
    private static final String C_TEXT = "#333333";
    private static final String C_BG   = "#ffffff";

    /** Default fill / stroke for the original (LV-side) curve. */
    public static final String FILL_ORIG   = "#fddcb5";
    public static final String STROKE_ORIG = "#c0622a";

    /** Default fill / stroke for the transported (HV-side) curve. */
    public static final String FILL_TRANS   = "#b8d4f0";
    public static final String STROKE_TRANS = "#1a5fa8";

    private ReactiveCurveSvg() {}

    // =========================================================================
    // Public data model
    // =========================================================================

    /**
     * Specification of one curve in a multi-curve overlay.
     *
     * @param label       legend entry text
     * @param pPts        active power values (MW)
     * @param qminPts     minimum reactive power values (MVar)
     * @param qmaxPts     maximum reactive power values (MVar)
     * @param fillColor   SVG fill colour string
     * @param strokeColor SVG stroke colour string
     */
    public record CurveSpec(
            String label,
            List<Double> pPts,
            List<Double> qminPts,
            List<Double> qmaxPts,
            String fillColor,
            String strokeColor) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Write a single-curve SVG.
     *
     * @param out         output file path
     * @param title       title text (bold, centred at top)
     * @param subtitle    subtitle text (smaller, below title; may be empty)
     * @param pPts        P values (MW)
     * @param qminPts     Qmin values (MVar)
     * @param qmaxPts     Qmax values (MVar)
     * @param fillColor   curve fill colour
     * @param strokeColor curve stroke colour
     */
    public static void writeSvg(Path out, String title, String subtitle,
                                 List<Double> pPts, List<Double> qminPts, List<Double> qmaxPts,
                                 String fillColor, String strokeColor) throws Exception {
        writeOverlaySvg(out, title, subtitle,
                List.of(new CurveSpec("", pPts, qminPts, qmaxPts, fillColor, strokeColor)));
    }

    /**
     * Write a multi-curve overlay SVG with shared axes and a legend.
     *
     * @param out      output file path
     * @param title    SVG title
     * @param subtitle SVG subtitle (may be empty)
     * @param curves   list of curve specifications (rendered back-to-front)
     */
    public static void writeOverlaySvg(Path out, String title, String subtitle,
                                        List<CurveSpec> curves) throws Exception {
        Document doc = buildDocument(title, subtitle, curves);

        var tf = TransformerFactory.newInstance();
        var xf = tf.newTransformer();
        xf.setOutputProperty(OutputKeys.METHOD, "xml");
        xf.setOutputProperty(OutputKeys.INDENT, "no");
        xf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        xf.transform(new DOMSource(doc), new StreamResult(out.toFile()));
    }

    // =========================================================================
    // DOM construction
    // =========================================================================

    private static Document buildDocument(String title, String subtitle,
                                           List<CurveSpec> curves) throws Exception {
        // ---- combined axis ranges with padding
        double pLo = curves.stream().flatMapToDouble(c -> c.pPts().stream().mapToDouble(Double::doubleValue)).min().orElse(0);
        double pHi = curves.stream().flatMapToDouble(c -> c.pPts().stream().mapToDouble(Double::doubleValue)).max().orElse(1);
        double qLo = curves.stream().flatMapToDouble(c -> c.qminPts().stream().mapToDouble(Double::doubleValue)).min().orElse(-1);
        double qHi = curves.stream().flatMapToDouble(c -> c.qmaxPts().stream().mapToDouble(Double::doubleValue)).max().orElse(1);
        double pPad = Math.max((pHi - pLo) * 0.05, 1.0);
        double qPad = Math.max((qHi - qLo) * 0.08, 1.0);
        double[] pR = {pLo - pPad, pHi + pPad};
        double[] qR = {qLo - qPad, qHi + qPad};

        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

        var svg = doc.createElement("svg");
        doc.appendChild(svg);
        svg.setAttribute("xmlns",   "http://www.w3.org/2000/svg");
        svg.setAttribute("width",   str(W));
        svg.setAttribute("height",  str(H));
        svg.setAttribute("viewBox", "0 0 " + W + " " + H);

        // background
        el(doc, svg, "rect", "x", "0", "y", "0",
           "width", str(W), "height", str(H), "fill", C_BG);

        // ---- grid
        double[] pTicks = niceTicks(pR[0], pR[1], 6);
        double[] qTicks = niceTicks(qR[0], qR[1], 6);
        for (double pt : pTicks) {
            double x = toX(pt, pR);
            el(doc, svg, "line", "x1", f1(x), "y1", str(MT),
               "x2", f1(x), "y2", str(MT + PH), "stroke", C_GRID, "stroke-width", "1");
        }
        for (double qt : qTicks) {
            double y = toY(qt, qR);
            el(doc, svg, "line", "x1", str(ML), "y1", f1(y),
               "x2", str(ML + PW), "y2", f1(y), "stroke", C_GRID, "stroke-width", "1");
        }

        // zero reference lines
        if (pR[0] < 0 && 0 < pR[1]) {
            double x0 = toX(0, pR);
            el(doc, svg, "line", "x1", f1(x0), "y1", str(MT),
               "x2", f1(x0), "y2", str(MT + PH),
               "stroke", C_ZERO, "stroke-width", "1", "stroke-dasharray", "4,3");
        }
        if (qR[0] < 0 && 0 < qR[1]) {
            double y0 = toY(0, qR);
            el(doc, svg, "line", "x1", str(ML), "y1", f1(y0),
               "x2", str(ML + PW), "y2", f1(y0),
               "stroke", C_ZERO, "stroke-width", "1", "stroke-dasharray", "4,3");
        }

        // ---- closed envelopes (back-to-front)
        for (var c : curves) {
            el(doc, svg, "path",
               "d", buildPath(c, pR, qR),
               "fill", c.fillColor(), "fill-opacity", "0.50",
               "stroke", c.strokeColor(), "stroke-width", "2",
               "stroke-linejoin", "round");
        }

        // ---- plot area border
        el(doc, svg, "rect",
           "x", str(ML), "y", str(MT), "width", str(PW), "height", str(PH),
           "fill", "none", "stroke", C_AXIS, "stroke-width", "1");

        // ---- X-axis ticks and labels
        int yBot = MT + PH;
        for (double pt : pTicks) {
            if (pt < pR[0] || pt > pR[1]) continue;
            double x = toX(pt, pR);
            el(doc, svg, "line", "x1", f1(x), "y1", str(yBot),
               "x2", f1(x), "y2", str(yBot + 5),
               "stroke", C_AXIS, "stroke-width", "1.5");
            txt(doc, svg, fmt(pt),
                "x", f1(x), "y", str(yBot + 18), "text-anchor", "middle",
                "font-family", "sans-serif", "font-size", "12", "fill", C_TEXT);
        }

        // ---- Y-axis ticks and labels
        for (double qt : qTicks) {
            if (qt < qR[0] || qt > qR[1]) continue;
            double y = toY(qt, qR);
            el(doc, svg, "line", "x1", str(ML - 5), "y1", f1(y),
               "x2", str(ML), "y2", f1(y),
               "stroke", C_AXIS, "stroke-width", "1.5");
            txt(doc, svg, fmt(qt),
                "x", str(ML - 8), "y", f1(y + 4), "text-anchor", "end",
                "font-family", "sans-serif", "font-size", "12", "fill", C_TEXT);
        }

        // ---- axis labels
        txt(doc, svg, "Active power P (MW)",
            "x", str(ML + PW / 2), "y", str(H - 12),
            "text-anchor", "middle",
            "font-family", "sans-serif", "font-size", "13", "fill", C_TEXT);
        txt(doc, svg, "Reactive power Q (MVar)",
            "transform", "translate(16," + (MT + PH / 2) + ") rotate(-90)",
            "text-anchor", "middle",
            "font-family", "sans-serif", "font-size", "13", "fill", C_TEXT);

        // ---- title / subtitle
        if (!title.isEmpty()) {
            txt(doc, svg, title,
                "x", str(W / 2), "y", "24", "text-anchor", "middle",
                "font-family", "sans-serif", "font-size", "15",
                "font-weight", "bold", "fill", C_TEXT);
        }
        if (!subtitle.isEmpty()) {
            txt(doc, svg, subtitle,
                "x", str(W / 2), "y", "44", "text-anchor", "middle",
                "font-family", "sans-serif", "font-size", "11", "fill", "#666666");
        }

        // ---- legend (shown when more than one curve)
        if (curves.size() > 1) {
            int entryH  = 22, swatchW = 18, swatchH = 11, pad = 8;
            int maxChars = curves.stream().mapToInt(c -> c.label().length()).max().orElse(10);
            int boxW = pad + swatchW + 6 + (int) (maxChars * 7.2) + pad;
            int boxH = curves.size() * entryH + 6;
            int bx   = ML + PW - boxW - 6;
            int by   = MT + 8;

            el(doc, svg, "rect",
               "x", str(bx), "y", str(by),
               "width", str(boxW), "height", str(boxH),
               "fill", "#ffffff", "fill-opacity", "0.88",
               "stroke", "#cccccc", "stroke-width", "1", "rx", "4");

            for (int i = 0; i < curves.size(); i++) {
                var c  = curves.get(i);
                int ey = by + 6 + i * entryH;
                int ex = bx + pad;
                el(doc, svg, "rect",
                   "x", str(ex), "y", str(ey),
                   "width", str(swatchW), "height", str(swatchH),
                   "fill", c.fillColor(), "fill-opacity", "0.65",
                   "stroke", c.strokeColor(), "stroke-width", "1.5", "rx", "2");
                txt(doc, svg, c.label(),
                    "x", str(ex + swatchW + 6), "y", str(ey + swatchH - 1),
                    "font-family", "sans-serif", "font-size", "11", "fill", C_TEXT);
            }
        }

        // ---- data-point markers (single-curve SVG only)
        if (curves.size() == 1) {
            var c = curves.get(0);
            for (int i = 0; i < c.pPts().size(); i++) {
                for (double q : List.of(c.qminPts().get(i), c.qmaxPts().get(i))) {
                    el(doc, svg, "circle",
                       "cx", f2(toX(c.pPts().get(i), pR)),
                       "cy", f2(toY(q, qR)),
                       "r", "3", "fill", c.strokeColor());
                }
            }
        }

        return doc;
    }

    // =========================================================================
    // Drawing helpers
    // =========================================================================

    /** Build the SVG 'd' attribute for a closed P-Q envelope path. */
    private static String buildPath(CurveSpec c, double[] pR, double[] qR) {
        var sb = new StringBuilder();
        // Qmax boundary: left → right
        sb.append(String.format("M %.2f,%.2f",
                toX(c.pPts().get(0), pR), toY(c.qmaxPts().get(0), qR)));
        for (int i = 1; i < c.pPts().size(); i++) {
            sb.append(String.format(" L %.2f,%.2f",
                    toX(c.pPts().get(i), pR), toY(c.qmaxPts().get(i), qR)));
        }
        // Qmin boundary: right → left
        for (int i = c.pPts().size() - 1; i >= 0; i--) {
            sb.append(String.format(" L %.2f,%.2f",
                    toX(c.pPts().get(i), pR), toY(c.qminPts().get(i), qR)));
        }
        sb.append(" Z");
        return sb.toString();
    }

    // =========================================================================
    // Maths helpers
    // =========================================================================

    /** Generate ~{@code n} nicely rounded tick values spanning [{@code vmin}, {@code vmax}]. */
    static double[] niceTicks(double vmin, double vmax, int n) {
        if (vmax <= vmin) return new double[]{vmin};
        double rawStep = (vmax - vmin) / Math.max(n - 1, 1);
        double mag     = Math.pow(10, Math.floor(Math.log10(Math.abs(rawStep))));
        double best    = mag;
        double bestDif = Double.MAX_VALUE;
        for (int m : new int[]{1, 2, 5, 10}) {
            double dif = Math.abs(m * mag - rawStep);
            if (dif < bestDif) { bestDif = dif; best = m * mag; }
        }
        double t0 = Math.ceil(vmin / best) * best;
        double t1 = Math.floor(vmax / best) * best;
        var ticks = new ArrayList<Double>();
        for (double t = t0; t <= t1 + best * 0.5; t += best) ticks.add(t);
        return ticks.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double toX(double p, double[] pR) {
        return ML + (p - pR[0]) / (pR[1] - pR[0]) * PW;
    }

    private static double toY(double q, double[] qR) {
        return MT + (1.0 - (q - qR[0]) / (qR[1] - qR[0])) * PH;
    }

    // =========================================================================
    // XML / formatting helpers
    // =========================================================================

    private static String fmt(double v) {
        long r = Math.round(v);
        return (Math.abs(v - r) < 1e-9) ? String.valueOf(r) : String.format("%.1f", v);
    }

    private static String str(int v)   { return String.valueOf(v); }
    private static String f1(double v) { return String.format("%.1f", v); }
    private static String f2(double v) { return String.format("%.2f", v); }

    /** Create a child element, set alternating key/value attributes, append to parent. */
    private static Element el(Document doc, Element parent, String tag, String... kv) {
        var e = doc.createElement(tag);
        for (int i = 0; i + 1 < kv.length; i += 2) e.setAttribute(kv[i], kv[i + 1]);
        parent.appendChild(e);
        return e;
    }

    /** Create a text element with content and attributes, append to parent. */
    private static Element txt(Document doc, Element parent, String content, String... kv) {
        var e = el(doc, parent, "text", kv);
        e.setTextContent(content);
        return e;
    }
}
