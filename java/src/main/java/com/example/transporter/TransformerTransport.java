package com.example.transporter;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import org.apache.commons.math3.complex.Complex;

/**
 * Analytical transport of an injection through the pi-model of a 2-winding
 * transformer.
 *
 * <p>Computation is done in per-unit on the HV side to avoid ambiguity around
 * line-to-line vs line-to-neutral voltages and the implicit sqrt(3) of
 * three-phase systems. PowSyBl uses single-phase per-unit equivalents
 * internally, so we follow the same approach here.
 *
 * <p>IIDM convention: R, X, G, B on a 2-winding transformer are referred to
 * side 2. {@link #orientToHv(TwoWindingsTransformer)} produces an
 * {@link OrientedParams} record with everything referred to the HV side and
 * the bus-breaker terminal IDs identified.
 */
public final class TransformerTransport {

    private TransformerTransport() {
        // Static utility
    }

    /** Result of a transport: HV-side complex injection and HV-side voltage. */
    public record HvPoint(double pHvMw, double qHvMvar, Complex vHvKv) { }

    /** Transformer parameters and bus IDs oriented so that side "hv" is HV. */
    public record OrientedParams(
            double r, double x, double g, double b,         // HV-referred SI
            double ratedLvKv, double ratedHvKv,
            double nomLvKv, double nomHvKv,
            String hvVoltageLevelId, String lvVoltageLevelId,
            String hvBusId, String lvBusId,                 // bus-breaker bus IDs
            double rhoTap                                   // current rho of RTC, 1.0 if none
    ) { }

    /**
     * Build an {@link OrientedParams} from a powsybl 2-winding transformer.
     */
    public static OrientedParams orientToHv(TwoWindingsTransformer tx) {
        double nom1 = tx.getTerminal1().getVoltageLevel().getNominalV();
        double nom2 = tx.getTerminal2().getVoltageLevel().getNominalV();

        boolean side1IsHv = nom1 >= nom2;

        // R, X, G, B are stored referred to side 2.
        // If side 2 == HV, no change; else multiply Z by k^2 and divide Y by k^2.
        double r, x, g, b;
        double ratedLv, ratedHv, nomLv, nomHv;
        String hvVlId, lvVlId, hvBusId, lvBusId;

        if (!side1IsHv) {
            // side 2 is HV - parameters already on HV side
            r = tx.getR();
            x = tx.getX();
            g = tx.getG();
            b = tx.getB();
            ratedLv = tx.getRatedU1();
            ratedHv = tx.getRatedU2();
            nomLv = nom1;
            nomHv = nom2;
            hvVlId = tx.getTerminal2().getVoltageLevel().getId();
            lvVlId = tx.getTerminal1().getVoltageLevel().getId();
            Bus hvBus2 = tx.getTerminal2().getBusBreakerView().getBus();
            hvBusId = (hvBus2 != null) ? hvBus2.getId() : null;
            Bus lvBus1 = tx.getTerminal1().getBusBreakerView().getBus();
            lvBusId = (lvBus1 != null) ? lvBus1.getId() : null;
        } else {
            // side 2 is LV - refer R,X,G,B from LV up to HV
            ratedLv = tx.getRatedU2();
            ratedHv = tx.getRatedU1();
            double k = ratedHv / ratedLv;
            double k2 = k * k;
            r = tx.getR() * k2;
            x = tx.getX() * k2;
            g = tx.getG() / k2;
            b = tx.getB() / k2;
            nomLv = nom2;
            nomHv = nom1;
            hvVlId = tx.getTerminal1().getVoltageLevel().getId();
            lvVlId = tx.getTerminal2().getVoltageLevel().getId();
            Bus hvBus1 = tx.getTerminal1().getBusBreakerView().getBus();
            hvBusId = (hvBus1 != null) ? hvBus1.getId() : null;
            Bus lvBus2 = tx.getTerminal2().getBusBreakerView().getBus();
            lvBusId = (lvBus2 != null) ? lvBus2.getId() : null;
        }

        // Read the current rho from the ratio tap changer if any.
        // PowSyBl exposes rho already as the dimensionless adjustment factor.
        double rhoTap = 1.0;
        RatioTapChanger rtc = tx.getRatioTapChanger();
        if (rtc != null) {
            rhoTap = rtc.getCurrentStep().getRho();
        }

        return new OrientedParams(r, x, g, b,
                ratedLv, ratedHv, nomLv, nomHv,
                hvVlId, lvVlId, hvBusId, lvBusId,
                rhoTap);
    }

    /**
     * Transport an LV-bus injection (P_inj_lv, Q_inj_lv) to the HV bus.
     *
     * @param p   {@link OrientedParams} oriented to the HV side
     * @param vLvKv      LV bus voltage magnitude (line-to-line, kV)
     * @param pInjLvMw   net active injection at the LV bus (MW),
     *                   = P_gen - P_aux_load
     * @param qInjLvMvar net reactive injection at the LV bus (MVar),
     *                   = Q_gen - Q_aux_load
     * @return HV-side injection (P_hv, Q_hv) and HV-side complex voltage
     */
    public static HvPoint transport(OrientedParams p,
                                    double vLvKv,
                                    double pInjLvMw,
                                    double qInjLvMvar) {
        return transport(p, vLvKv, pInjLvMw, qInjLvMvar, 100.0);
    }

    /**
     * Transport with a custom per-unit base. The result is independent of the
     * base value (it cancels out) but you may want to set it for numerical
     * conditioning.
     */
    public static HvPoint transport(OrientedParams p,
                                    double vLvKv,
                                    double pInjLvMw,
                                    double qInjLvMvar,
                                    double sBaseMva) {
        // Bases on the HV side
        double zbHv = p.ratedHvKv() * p.ratedHvKv() / sBaseMva;     // ohm
        double ybHv = 1.0 / zbHv;                                    // siemens

        // Per-unit transformer parameters
        Complex zPu = new Complex(p.r() / zbHv, p.x() / zbHv);
        Complex yPu = new Complex(p.g() / ybHv, p.b() / ybHv);

        // Complex ratio (real here): off-nominal part is captured by v1Pu
        double rhoPu = p.rhoTap();

        // LV bus voltage in pu (its own rated voltage as base) - reference angle 0
        Complex v1Pu = new Complex(vLvKv / p.ratedLvKv(), 0.0);

        // Current at LV side, in pu
        Complex s1Pu = new Complex(pInjLvMw / sBaseMva, qInjLvMvar / sBaseMva);
        Complex i1Pu = s1Pu.divide(v1Pu).conjugate();

        // Refer through the ideal ratio
        Complex v1pPu = v1Pu.multiply(rhoPu);
        Complex i1pPu = i1Pu.divide(rhoPu);

        // Voltage drop across series Z to reach HV bus
        Complex v2Pu = v1pPu.subtract(zPu.multiply(i1pPu));

        // Shunt current at HV bus
        Complex iShuntPu = yPu.multiply(v2Pu);

        // Current flowing from the transformer into the HV bus
        Complex i2Pu = i1pPu.subtract(iShuntPu);

        // Complex power injected at HV bus
        Complex sHvPu = v2Pu.multiply(i2Pu.conjugate());

        return new HvPoint(
                sHvPu.getReal() * sBaseMva,
                sHvPu.getImaginary() * sBaseMva,
                v2Pu.multiply(p.ratedHvKv())
        );
    }
}
