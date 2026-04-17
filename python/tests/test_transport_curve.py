"""
Pytest suite for transport_curve.py.

Unit tests cover transport_point() and transport_capability_curve().
Integration tests cover build_equivalent_network() with AC load flow
validation, mirroring the checks done in the script's main() function.

All expected numerical values were verified by running both the Python and
Java implementations on the same test network and confirming they agree.
"""

import pathlib

import numpy as np
import pytest
import pypowsybl.loadflow as lf

from transport_curve import (
    build_test_network,
    build_equivalent_network,
    get_oriented_transformer_params,
    transport_capability_curve,
    transport_point,
)

TOLERANCE = 0.01  # MW / MVar

# ---------------------------------------------------------------------------
# Transformer parameters for the test network (TX), HV-referred.
#   rated_u1=400 kV (HV, side 1), rated_u2=20 kV (LV, side 2)
#   Stored referred to side 2; k² = (400/20)² = 400
# ---------------------------------------------------------------------------
R_HV = 0.002667 * 400   # 1.0667 Ω
X_HV = 0.080    * 400   # 32.0 Ω
G_HV = 9.0e-4   / 400   # 2.25e-6 S
B_HV = -0.027   / 400   # -6.75e-5 S
RATED_LV = 20.0
RATED_HV = 400.0


# ---------------------------------------------------------------------------
# Unit tests: transport_point
# ---------------------------------------------------------------------------

class TestTransportPoint:
    """Direct tests of the transport_point() analytic function."""

    def test_operating_point_known_values(self):
        """
        Transport the test-network operating point:
          P_gen=400 MW, Q_gen=0, P_aux=15, Q_aux=5  →  P_inj=385, Q_inj=-5
        Expected values match Java and Python cross-run output.
        """
        p_hv, q_hv, _ = transport_point(385.0, -5.0, 20.5,
                                         R_HV, X_HV, G_HV, B_HV,
                                         RATED_LV, RATED_HV)
        assert abs(p_hv - 383.680) < TOLERANCE
        assert abs(q_hv - (-44.595)) < TOLERANCE

    def test_ideal_transformer_conserves_power(self):
        """With Z=0 and Y=0 (ideal transformer) P and Q are conserved exactly."""
        p_hv, q_hv, v_hv = transport_point(300.0, 100.0, 20.0,
                                            0.0, 0.0, 0.0, 0.0,
                                            RATED_LV, RATED_HV)
        assert abs(p_hv - 300.0) < 1e-9
        assert abs(q_hv - 100.0) < 1e-9
        assert abs(abs(v_hv) - RATED_HV) < 1e-9

    def test_result_independent_of_s_base(self):
        """The result must be the same regardless of the per-unit base power."""
        args = (200.0, 80.0, 20.5, R_HV, X_HV, G_HV, B_HV, RATED_LV, RATED_HV)
        p100, q100, _ = transport_point(*args, s_base_mva=100.0)
        p600, q600, _ = transport_point(*args, s_base_mva=600.0)
        assert abs(p100 - p600) < 1e-6
        assert abs(q100 - q600) < 1e-6

    def test_resistive_losses_reduce_hv_p(self):
        """With non-zero R and positive P injection, HV P < LV P (losses)."""
        p_hv, _, _ = transport_point(400.0, 0.0, 20.0,
                                      R_HV, X_HV, G_HV, B_HV,
                                      RATED_LV, RATED_HV)
        assert p_hv < 400.0

    def test_hv_voltage_plausible(self):
        """HV voltage magnitude should be close to the rated HV voltage."""
        _, _, v_hv = transport_point(385.0, -5.0, 20.5,
                                      R_HV, X_HV, G_HV, B_HV,
                                      RATED_LV, RATED_HV)
        # Voltage should be within ±20% of rated (very loose sanity check)
        assert 0.8 * RATED_HV < abs(v_hv) < 1.2 * RATED_HV


# ---------------------------------------------------------------------------
# Unit tests: transport_capability_curve
# ---------------------------------------------------------------------------

class TestTransportCapabilityCurve:
    """Tests for transport_capability_curve() on the built-in test network."""

    @pytest.fixture(scope="class")
    def net(self):
        return build_test_network()

    def test_n_samples_respected(self, net):
        df = transport_capability_curve(net, "GEN_LV", "TX", "AUX_LOAD", 20.5, n_samples=11)
        assert len(df) == 11

    def test_p_strictly_increasing(self, net):
        df = transport_capability_curve(net, "GEN_LV", "TX", "AUX_LOAD", 20.5, n_samples=25)
        diffs = df["p"].diff().dropna()
        assert (diffs > 0).all(), "P values must be strictly increasing"

    def test_qmin_le_qmax(self, net):
        df = transport_capability_curve(net, "GEN_LV", "TX", "AUX_LOAD", 20.5, n_samples=25)
        assert (df["min_q"] <= df["max_q"]).all()

    def test_first_point_known_values(self, net):
        """P_gen=0 row must match cross-validated reference values."""
        df = transport_capability_curve(net, "GEN_LV", "TX", "AUX_LOAD", 20.5, n_samples=11)
        row = df.iloc[0]
        assert abs(row["p"]     - (-15.524)) < TOLERANCE
        assert abs(row["min_q"] - (-171.645)) < TOLERANCE
        assert abs(row["max_q"] -   130.224) < TOLERANCE

    def test_last_point_known_values(self, net):
        """P_gen=500 row must match cross-validated reference values."""
        df = transport_capability_curve(net, "GEN_LV", "TX", "AUX_LOAD", 20.5, n_samples=11)
        row = df.iloc[-1]
        assert abs(row["p"]     -  483.052) < TOLERANCE
        assert abs(row["min_q"] - (-163.709)) < TOLERANCE
        assert abs(row["max_q"] -   56.822) < TOLERANCE

    def test_without_aux_load(self, net):
        """Passing aux_load_id=None must not raise and must return a valid curve."""
        df = transport_capability_curve(net, "GEN_LV", "TX", None, 20.5, n_samples=5)
        assert len(df) == 5
        assert (df["min_q"] <= df["max_q"]).all()


# ---------------------------------------------------------------------------
# Integration tests: build_equivalent_network + AC load flow validation
# ---------------------------------------------------------------------------

class TestEquivalentNetworkIntegration:
    """End-to-end tests that mirror the validation in transport_curve.main()."""

    _LF_PARAMS = lf.Parameters(
        voltage_init_mode=lf.VoltageInitMode.UNIFORM_VALUES,
        transformer_voltage_control_on=False,
        use_reactive_limits=True,
        distributed_slack=False,
    )

    def test_lv_equipment_removed_from_equivalent(self):
        original = build_test_network()
        eq, _ = build_equivalent_network(original, "GEN_LV", "TX", "AUX_LOAD",
                                          new_gen_id="GEN_HV_EQ", n_samples=11)
        import pypowsybl.network as pn
        gens = eq.get_generators()
        loads = eq.get_loads()
        txs  = eq.get_2_windings_transformers()
        assert "GEN_LV"  not in gens.index,  "Original generator must be removed"
        assert "AUX_LOAD" not in loads.index, "Auxiliary load must be removed"
        assert "TX"       not in txs.index,   "Transformer must be removed"
        assert "GEN_HV_EQ" in gens.index,     "Equivalent generator must exist"

    def test_equivalent_load_flow_converges(self):
        original = build_test_network()
        eq, _ = build_equivalent_network(original, "GEN_LV", "TX", "AUX_LOAD",
                                          new_gen_id="GEN_HV_EQ", n_samples=11)
        res = lf.run_ac(eq, parameters=self._LF_PARAMS)
        assert res[0].status_text == "Converged", "Equivalent network LF must converge"

    def test_hv_injection_matches_original(self):
        """
        Run AC LF on both networks at the same operating point and verify the
        HV-bus injection agrees within 0.1 MW / 0.1 MVar (matches Python's
        dP=0.000, dQ=0.000 result reported by main()).
        """
        original = build_test_network()

        # Run original LF first to get the actual post-LF generator output
        res_orig = lf.run_ac(original, parameters=self._LF_PARAMS)
        assert res_orig[0].status_text == "Converged"

        gen_after = original.get_generators().loc["GEN_LV"]
        p_actual = -float(gen_after["p"])
        q_actual = -float(gen_after["q"])
        bus_v    = original.get_buses().loc[
            original.get_generators().loc["GEN_LV", "bus_id"], "v_mag"
        ]

        # Build equivalent and set operating point from the post-LF state
        eq, _ = build_equivalent_network(original, "GEN_LV", "TX", "AUX_LOAD",
                                          new_gen_id="GEN_HV_EQ", n_samples=11)

        tp    = get_oriented_transformer_params(original, "TX")
        p_aux = float(original.get_loads().loc["AUX_LOAD", "p0"])
        q_aux = float(original.get_loads().loc["AUX_LOAD", "q0"])
        p_op_hv, q_op_hv, _ = transport_point(
            p_actual - p_aux, q_actual - q_aux, bus_v,
            tp["r"], tp["x"], tp["g"], tp["b"],
            tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
        )
        eq.update_generators(id="GEN_HV_EQ", target_p=p_op_hv, target_q=q_op_hv)

        res_eq = lf.run_ac(eq, parameters=self._LF_PARAMS)
        assert res_eq[0].status_text == "Converged"

        # HV-side injection from the original (negative of transformer HV terminal flow)
        tx_orig = original.get_2_windings_transformers().loc["TX"]
        p_orig_hv = -float(tx_orig["p1"])   # side 1 = HV for TX
        q_orig_hv = -float(tx_orig["q1"])

        gen_eq = eq.get_generators().loc["GEN_HV_EQ"]
        p_eq_hv = -float(gen_eq["p"])
        q_eq_hv = -float(gen_eq["q"])

        assert abs(p_eq_hv - p_orig_hv) < 0.1, f"dP={p_eq_hv - p_orig_hv:.4f} MW"
        assert abs(q_eq_hv - q_orig_hv) < 0.1, f"dQ={q_eq_hv - q_orig_hv:.4f} MVar"

    def test_export_test_network_creates_file(self, tmp_path):
        """export_test_network() must write a readable XIIDM file."""
        from transport_curve import export_test_network
        import pypowsybl.network as pn

        out = tmp_path / "exported.xiidm"
        export_test_network(out)
        assert out.exists() and out.stat().st_size > 0

        reloaded = pn.load(str(out))
        assert "GEN_LV" in reloaded.get_generators().index

    def test_resource_file_matches_python_network(self):
        """
        The committed resource file test_network.xiidm must produce the same
        transported curve as building the network from scratch in Python.
        """
        resource = (pathlib.Path(__file__).parent.parent.parent /
                    "java" / "src" / "main" / "resources" / "test_network.xiidm")
        if not resource.exists():
            pytest.skip("Resource file not found — run export first")

        import pypowsybl.network as pn
        net_from_file = pn.load(str(resource))
        net_from_code = build_test_network()

        df_file = transport_capability_curve(net_from_file, "GEN_LV", "TX", "AUX_LOAD", 20.5, 11)
        df_code = transport_capability_curve(net_from_code, "GEN_LV", "TX", "AUX_LOAD", 20.5, 11)

        import pandas as pd
        pd.testing.assert_frame_equal(
            df_file[["p", "min_q", "max_q"]].reset_index(drop=True),
            df_code[["p", "min_q", "max_q"]].reset_index(drop=True),
            atol=TOLERANCE, check_exact=False,
        )
