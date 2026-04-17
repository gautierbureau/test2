package com.example.transporter;

import com.powsybl.iidm.network.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Build the equivalent reactive capability curve as seen from the HV side
 * of a transformer, accounting for an auxiliary load on the LV side and the
 * transformer's series impedance and magnetizing shunt.
 */
public final class CurveTransporter {

    /** A single point of the transported curve, on the HV side. */
    public record HvCurvePoint(double pHv, double minQHv, double maxQHv) { }

    private CurveTransporter() {
        // Static utility
    }

    /**
     * Sample the original generator's reactive capability curve at
     * {@code nSamples} active-power points and transport each (P, Qmin) and
     * (P, Qmax) pair to the HV bus analytically.
     *
     * @param generator    the LV-side generator carrying the original curve
     * @param transformer  the 2-winding transformer between LV and HV
     * @param auxLoad      the auxiliary load on the LV bus (or {@code null}
     *                     if there is none)
     * @param vLvKv        assumed LV-bus voltage magnitude (kV) - typically
     *                     the generator's regulating setpoint
     * @param nSamples     number of sample points along the curve
     */
    public static List<HvCurvePoint> transportCurve(
            Generator generator,
            TwoWindingsTransformer transformer,
            Load auxLoad,
            double vLvKv,
            int nSamples) {

        if (nSamples < 2) {
            throw new IllegalArgumentException("nSamples must be >= 2");
        }

        ReactiveLimits limits = generator.getReactiveLimits();
        double pMin = generator.getMinP();
        double pMax = generator.getMaxP();

        TransformerTransport.OrientedParams op =
                TransformerTransport.orientToHv(transformer);

        double pAux = (auxLoad != null) ? auxLoad.getP0() : 0.0;
        double qAux = (auxLoad != null) ? auxLoad.getQ0() : 0.0;

        List<HvCurvePoint> points = new ArrayList<>(nSamples);
        for (int i = 0; i < nSamples; i++) {
            double t = (nSamples == 1) ? 0.0 : (double) i / (nSamples - 1);
            double pGen = pMin + t * (pMax - pMin);

            double qMinOrig = limits.getMinQ(pGen);
            double qMaxOrig = limits.getMaxQ(pGen);

            // LV-bus net injection (loads consume positive p/q)
            double pInjLv = pGen - pAux;
            double qInjLvLo = qMinOrig - qAux;
            double qInjLvHi = qMaxOrig - qAux;

            TransformerTransport.HvPoint lo =
                    TransformerTransport.transport(op, vLvKv, pInjLv, qInjLvLo);
            TransformerTransport.HvPoint hi =
                    TransformerTransport.transport(op, vLvKv, pInjLv, qInjLvHi);

            // Average the very-close P values; take min/max of Q
            double pHv = 0.5 * (lo.pHvMw() + hi.pHvMw());
            double qMinHv = Math.min(lo.qHvMvar(), hi.qHvMvar());
            double qMaxHv = Math.max(lo.qHvMvar(), hi.qHvMvar());

            points.add(new HvCurvePoint(pHv, qMinHv, qMaxHv));
        }

        // Sort by P; the curve in IIDM must be ordered with strictly
        // increasing P. If two consecutive samples produce nearly the same
        // pHv (can happen at the very ends), nudge the second one.
        points.sort(Comparator.comparingDouble(HvCurvePoint::pHv));
        for (int i = 1; i < points.size(); i++) {
            HvCurvePoint prev = points.get(i - 1);
            HvCurvePoint cur = points.get(i);
            if (cur.pHv() - prev.pHv() < 1e-6) {
                points.set(i, new HvCurvePoint(prev.pHv() + 1e-6,
                        cur.minQHv(), cur.maxQHv()));
            }
        }
        return points;
    }
}
