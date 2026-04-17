package com.example.transporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the per-unit transport mathematics in {@link TransformerTransport}.
 *
 * Expected values were computed independently by the Python implementation
 * (transport_curve.py) and cross-checked analytically.
 *
 * Transformer parameters used (same as the bundled test_network.xiidm):
 *   rated_u1=400 kV (HV), rated_u2=20 kV (LV), Sn=600 MVA
 *   R_lv=0.002667 Ω, X_lv=0.08 Ω, G_lv=9e-4 S, B_lv=-0.027 S  →  referred to HV side:
 *   R_hv=1.06667 Ω, X_hv=32 Ω, G_hv=2.25e-6 S, B_hv=-6.75e-5 S
 */
class TransformerTransportTest {

    private static final double TOLERANCE_MW   = 0.01;
    private static final double TOLERANCE_KV   = 0.01;

    /** HV-referred parameters matching the test network TX transformer. */
    private static TransformerTransport.OrientedParams testNetworkParams() {
        return new TransformerTransport.OrientedParams(
                1.06667,   // r  (HV-referred, Ω)
                32.0,      // x  (HV-referred, Ω)
                2.25e-6,   // g  (HV-referred, S)
                -6.75e-5,  // b  (HV-referred, S)
                20.0,      // ratedLvKv
                400.0,     // ratedHvKv
                20.0,      // nomLvKv
                400.0,     // nomHvKv
                "VL_HV", "VL_LV",
                "BUS_HV", "BUS_LV",
                1.0        // rhoTap
        );
    }

    /**
     * Transport the generator operating point (P_gen=400 MW, Q_gen=0) minus the
     * auxiliary load (P_aux=15, Q_aux=5) at V_lv=20.5 kV.
     * Expected values match the Java and Python run outputs.
     */
    @Test
    void testTransportOperatingPoint() {
        TransformerTransport.OrientedParams op = testNetworkParams();
        // Net LV injection: P=385 MW, Q=-5 MVar
        TransformerTransport.HvPoint result = TransformerTransport.transport(op, 20.5, 385.0, -5.0);

        assertEquals(383.680, result.pHvMw(),   TOLERANCE_MW, "P_hv mismatch");
        assertEquals(-44.595, result.qHvMvar(), TOLERANCE_MW, "Q_hv mismatch");
    }

    /**
     * With zero series impedance and zero shunt admittance the transformer is
     * ideal: the HV injection must equal the LV injection exactly.
     */
    @Test
    void testTransportIdealTransformer() {
        TransformerTransport.OrientedParams ideal = new TransformerTransport.OrientedParams(
                0.0, 0.0, 0.0, 0.0, 20.0, 400.0, 20.0, 400.0,
                "VL_HV", "VL_LV", "BUS_HV", "BUS_LV", 1.0);

        TransformerTransport.HvPoint result = TransformerTransport.transport(ideal, 20.0, 300.0, 100.0);

        assertEquals(300.0, result.pHvMw(),   1e-6, "Ideal TX must conserve P");
        assertEquals(100.0, result.qHvMvar(), 1e-6, "Ideal TX must conserve Q");
        assertEquals(400.0, result.vHvKv().abs(), TOLERANCE_KV, "Ideal TX V_hv magnitude");
    }

    /**
     * With rho_tap=1 the result is independent of the per-unit base power
     * (it cancels algebraically). Verify the two-argument and five-argument
     * overloads agree.
     */
    @Test
    void testTransportResultIndependentOfSBase() {
        TransformerTransport.OrientedParams op = testNetworkParams();
        TransformerTransport.HvPoint r100 = TransformerTransport.transport(op, 20.5, 200.0, 80.0, 100.0);
        TransformerTransport.HvPoint r600 = TransformerTransport.transport(op, 20.5, 200.0, 80.0, 600.0);

        assertEquals(r100.pHvMw(),   r600.pHvMw(),   TOLERANCE_MW, "P must not depend on S_base");
        assertEquals(r100.qHvMvar(), r600.qHvMvar(), TOLERANCE_MW, "Q must not depend on S_base");
    }

    /**
     * The HV-side active power must be strictly less than the LV injection when
     * there are resistive losses (P_inj > 0).
     */
    @Test
    void testActivePowerLossesWithResistance() {
        TransformerTransport.OrientedParams op = testNetworkParams();
        double pInjLv = 400.0;
        TransformerTransport.HvPoint result = TransformerTransport.transport(op, 20.0, pInjLv, 0.0);
        assertTrue(result.pHvMw() < pInjLv, "HV P must be lower than LV injection due to losses");
    }
}
