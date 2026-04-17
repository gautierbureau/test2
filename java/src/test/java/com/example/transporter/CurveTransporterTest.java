package com.example.transporter;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CurveTransporter} using the bundled test network.
 *
 * All expected P/Qmin/Qmax values were verified against the Python
 * implementation output (transport_curve.py with n_samples=11).
 */
class CurveTransporterTest {

    private static final double TOLERANCE = 0.01; // MW / MVar

    private Network network;
    private Generator   genLv;
    private TwoWindingsTransformer tx;
    private Load auxLoad;

    @BeforeEach
    void loadNetwork() throws Exception {
        URL url = getClass().getClassLoader().getResource("test_network.xiidm");
        assertNotNull(url, "test_network.xiidm not found on classpath");
        network = Network.read(Path.of(url.toURI()));
        genLv   = network.getGenerator("GEN_LV");
        tx      = network.getTwoWindingsTransformer("TX");
        auxLoad = network.getLoad("AUX_LOAD");
    }

    /** The returned list must have exactly nSamples entries. */
    @Test
    void testNSamples() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 11);
        assertEquals(11, pts.size());
    }

    /** P values must be strictly increasing (IIDM requirement). */
    @Test
    void testCurvePStrictlyIncreasing() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 11);
        for (int i = 1; i < pts.size(); i++) {
            assertTrue(pts.get(i).pHv() > pts.get(i - 1).pHv(),
                    "P must be strictly increasing at index " + i);
        }
    }

    /** Qmin must be <= Qmax at every sample point. */
    @Test
    void testQminLeQmax() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 25);
        for (CurveTransporter.HvCurvePoint pt : pts) {
            assertTrue(pt.minQHv() <= pt.maxQHv(),
                    "Qmin must be <= Qmax at P=" + pt.pHv());
        }
    }

    /**
     * Spot-check the first curve point (P_gen=0 MW, V_lv=20.5 kV).
     * Expected values from Python + Java cross-run.
     */
    @Test
    void testFirstCurvePoint() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 11);
        CurveTransporter.HvCurvePoint first = pts.get(0);

        assertEquals(-15.524, first.pHv(),    TOLERANCE, "First point P_hv");
        assertEquals(-171.645, first.minQHv(), TOLERANCE, "First point Qmin_hv");
        assertEquals(130.224, first.maxQHv(),  TOLERANCE, "First point Qmax_hv");
    }

    /**
     * Spot-check the last curve point (P_gen=500 MW, V_lv=20.5 kV).
     * Expected values from Python + Java cross-run.
     */
    @Test
    void testLastCurvePoint() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 11);
        CurveTransporter.HvCurvePoint last = pts.get(pts.size() - 1);

        assertEquals(483.052, last.pHv(),    TOLERANCE, "Last point P_hv");
        assertEquals(-163.709, last.minQHv(), TOLERANCE, "Last point Qmin_hv");
        assertEquals(56.822, last.maxQHv(),   TOLERANCE, "Last point Qmax_hv");
    }

    /** nSamples=2 must not throw and must produce exactly 2 points. */
    @Test
    void testMinimumNSamples() {
        List<CurveTransporter.HvCurvePoint> pts =
                CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 2);
        assertEquals(2, pts.size());
    }

    /** nSamples < 2 must be rejected. */
    @Test
    void testNSamplesBelowMinimumThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CurveTransporter.transportCurve(genLv, tx, auxLoad, 20.5, 1));
    }
}
