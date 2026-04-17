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
    GeneratorSpec,
    LossAllocation,
    build_test_network,
    build_node_breaker_network,
    build_node_breaker_network_full,
    build_two_generators_network,
    build_equivalent_network,
    build_equivalent_network_multi,
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


# ---------------------------------------------------------------------------
# Integration tests: node-breaker topology (HV and full)
# ---------------------------------------------------------------------------

class TestNodeBreakerEquivalent:
    """
    Mirror of EquivalentBuilderNodeBreakerTest.java.

    Uses build_node_breaker_network()  (HV NB, LV BB) and
         build_node_breaker_network_full() (both NB).
    Curve values and LF results must match the bus-breaker reference because
    the electrical content is identical.
    """

    _LF_PARAMS = lf.Parameters(
        voltage_init_mode=lf.VoltageInitMode.UNIFORM_VALUES,
        transformer_voltage_control_on=False,
        use_reactive_limits=True,
        distributed_slack=False,
    )

    def test_network_topology_kind(self):
        """VL_HV must be NODE_BREAKER and contain a busbar section."""
        net = build_node_breaker_network()
        vl_df = net.get_voltage_levels(all_attributes=True)
        assert vl_df.loc["VL_HV", "topology_kind"] == "NODE_BREAKER"

        topo = net.get_node_breaker_topology("VL_HV")
        bbs = topo.nodes[topo.nodes["connectable_type"] == "BUSBAR_SECTION"]
        assert len(bbs) > 0, "VL_HV must contain a busbar section"

    def test_original_equipment_present(self):
        """All original LV equipment must be present before the build."""
        net = build_node_breaker_network()
        assert "GEN_LV"  in net.get_generators().index
        assert "AUX_LOAD" in net.get_loads().index
        assert "TX"       in net.get_2_windings_transformers().index

    def test_equivalent_structure(self):
        """
        After building the equivalent:
          - LV equipment is removed
          - New HV generator exists inside VL_HV
          - Curve has 11 points and strictly increasing P
        """
        net = build_node_breaker_network()
        eq, curve = build_equivalent_network(
            net, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11
        )

        assert "GEN_LV"  not in eq.get_generators().index,          "Original generator must be removed"
        assert "AUX_LOAD" not in eq.get_loads().index,               "Auxiliary load must be removed"
        assert "TX"       not in eq.get_2_windings_transformers().index, "Transformer must be removed"

        gens = eq.get_generators(all_attributes=True)
        assert "GEN_HV_EQ" in gens.index, "Equivalent generator must exist"
        assert gens.loc["GEN_HV_EQ", "voltage_level_id"] == "VL_HV", \
            "Generator must be in VL_HV"

        assert len(curve) == 11, "Curve must have 11 points"
        diffs = curve["p"].diff().dropna()
        assert (diffs > 0).all(), "Curve P must be strictly increasing"

    def test_node_breaker_switches(self):
        """
        RemoveFeederBay cleans up the TX bay (−2 switches); the new generator
        bay adds 2.  Net switch count in VL_HV is unchanged.
        """
        net = build_node_breaker_network()
        before = len(net.get_node_breaker_topology("VL_HV").switches)

        eq, _ = build_equivalent_network(
            net, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11
        )

        sw_after = eq.get_node_breaker_topology("VL_HV").switches
        assert "DISC_TX" not in sw_after.index, "TX HV disconnector must be removed"
        assert "BRK_TX"  not in sw_after.index, "TX HV breaker must be removed"
        assert len(sw_after) == before, "Net switch count in VL_HV must be unchanged"

        # Equivalent generator must be reachable via a calculated bus
        gens = eq.get_generators(all_attributes=True)
        assert gens.loc["GEN_HV_EQ", "connected"], \
            "Equivalent generator must be connected"

    def test_lv_feeder_bays_removed(self):
        """
        When VL_LV is also NODE_BREAKER, all 6 LV feeder bay switches must
        be removed (2 per feeder × 3 feeders: GEN_LV, AUX_LOAD, TX LV side).
        """
        net = build_node_breaker_network_full()
        assert len(net.get_node_breaker_topology("VL_LV").switches) == 6, \
            "VL_LV must start with 6 switches"

        eq, _ = build_equivalent_network(
            net, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11
        )

        sw_lv = eq.get_node_breaker_topology("VL_LV").switches
        assert len(sw_lv) == 0, "All LV feeder bay switches must be removed"
        for sw_id in ["DISC_GEN_LV", "BRK_GEN_LV",
                      "DISC_AUX_LV", "BRK_AUX_LV",
                      "DISC_TX_LV",  "BRK_TX_LV"]:
            assert sw_id not in sw_lv.index, f"{sw_id} must be removed"

    def test_curve_values(self):
        """
        Transported curve values must match the bus-breaker reference
        (same electrical data).
        """
        net = build_node_breaker_network()
        _, curve = build_equivalent_network(
            net, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11
        )

        first = curve.iloc[0]
        assert abs(first["p"]     - (-15.524))  < TOLERANCE, "First P_hv"
        assert abs(first["min_q"] - (-171.645)) < TOLERANCE, "First Qmin_hv"
        assert abs(first["max_q"] -   130.224)  < TOLERANCE, "First Qmax_hv"

        last = curve.iloc[-1]
        assert abs(last["p"]     -  483.052)   < TOLERANCE, "Last P_hv"
        assert abs(last["min_q"] - (-163.709)) < TOLERANCE, "Last Qmin_hv"
        assert abs(last["max_q"] -   56.822)   < TOLERANCE, "Last Qmax_hv"

    def test_load_flow_converges(self):
        """
        The equivalent node-breaker network must converge under AC load flow,
        and the equivalent generator P injection must match the transported
        operating point within 0.5 MW.
        """
        net = build_node_breaker_network()
        eq, _ = build_equivalent_network(
            net, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11
        )

        res = lf.run_ac(eq, parameters=self._LF_PARAMS)
        assert res[0].status_text == "Converged", \
            "Load flow must converge on the equivalent node-breaker network"

        gen = eq.get_generators().loc["GEN_HV_EQ"]
        inj_p = -float(gen["p"])   # generator convention: -p = injected
        assert abs(inj_p - 383.680) < 0.5, f"HV generator P injection after LF: {inj_p:.3f}"


# ---------------------------------------------------------------------------
# Multi-generator tests: mirror of Java's EquivalentBuilderMultiTest
# ---------------------------------------------------------------------------

class TestEquivalentBuilderMulti:
    """Integration tests for build_equivalent_network_multi with two generators."""

    _LF_PARAMS = lf.Parameters(
        voltage_init_mode=lf.VoltageInitMode.UNIFORM_VALUES,
        transformer_voltage_control_on=False,
        use_reactive_limits=True,
        distributed_slack=False,
    )

    _SPECS_AB = [
        GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
        GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV_B"),
    ]

    def test_original_equipment_present(self):
        net = build_two_generators_network()
        assert "GEN_LV_A" in net.get_generators().index
        assert "GEN_LV_B" in net.get_generators().index
        assert "AUX_LOAD_A" in net.get_loads().index
        assert "AUX_LOAD_B" in net.get_loads().index
        assert "TX" in net.get_2_windings_transformers().index

    def test_equivalent_structure(self):
        net = build_two_generators_network()
        eq, curves = build_equivalent_network_multi(net, self._SPECS_AB, "TX", n_samples=11)

        assert "GEN_LV_A"   not in eq.get_generators().index
        assert "GEN_LV_B"   not in eq.get_generators().index
        assert "AUX_LOAD_A" not in eq.get_loads().index
        assert "AUX_LOAD_B" not in eq.get_loads().index
        assert "TX"         not in eq.get_2_windings_transformers().index

        gens = eq.get_generators(all_attributes=True)
        assert "EQ_GEN_HV_A" in gens.index
        assert "EQ_GEN_HV_B" in gens.index
        assert gens.loc["EQ_GEN_HV_A", "voltage_level_id"] == "VL_HV"
        assert gens.loc["EQ_GEN_HV_B", "voltage_level_id"] == "VL_HV"

        assert len(curves) == 2
        for curve in curves:
            assert len(curve) == 11
            diffs = curve["p"].diff().dropna()
            assert (diffs > 0).all(), "Curve must be strictly increasing in P"

    def test_generator_a_curve_matches_single_gen_reference(self):
        """GEN_LV_A uses the same inputs as GEN_LV in the single-gen test network,
        so its transported curve in INDEPENDENT mode must match those reference
        values exactly."""
        net = build_two_generators_network()
        _, curves = build_equivalent_network_multi(net, self._SPECS_AB, "TX", n_samples=11)

        first = curves[0].iloc[0]
        assert abs(first["p"]     - (-15.524))  < TOLERANCE
        assert abs(first["min_q"] - (-171.645)) < TOLERANCE
        assert abs(first["max_q"] -   130.224)  < TOLERANCE

        last = curves[0].iloc[-1]
        assert abs(last["p"]     -  483.052)   < TOLERANCE
        assert abs(last["min_q"] - (-163.709)) < TOLERANCE
        assert abs(last["max_q"] -   56.822)   < TOLERANCE

    def test_generator_b_curve_is_distinct(self):
        net = build_two_generators_network()
        _, curves = build_equivalent_network_multi(net, self._SPECS_AB, "TX", n_samples=11)
        curve_b = curves[1]

        p_max_b = float(curve_b["p"].iloc[-1])
        assert p_max_b < 400.0, f"GEN_LV_B last HV P should reflect 400 MW max, got {p_max_b}"
        assert p_max_b > 380.0, f"GEN_LV_B last HV P should be ~400 - 8 - losses, got {p_max_b}"

        p_min_b = float(curve_b["p"].iloc[0])
        assert p_min_b < 0.0, f"With P=0 and aux=8 MW, HV P must be negative, got {p_min_b}"
        assert p_min_b > -15.0, f"HV P for P=0 should be close to -8 MW minus losses, got {p_min_b}"

    def test_two_feeder_bays_created(self):
        """TX bay removed (-2), two new gen bays added (+4): net +2 switches in VL_HV."""
        net = build_two_generators_network()
        before = len(net.get_node_breaker_topology("VL_HV").switches)

        eq, _ = build_equivalent_network_multi(net, self._SPECS_AB, "TX", n_samples=11)

        sw_after = eq.get_node_breaker_topology("VL_HV").switches
        assert len(sw_after) == before + 2
        assert "DISC_TX" not in sw_after.index
        assert "BRK_TX"  not in sw_after.index

        gens = eq.get_generators(all_attributes=True)
        assert gens.loc["EQ_GEN_HV_A", "connected"]
        assert gens.loc["EQ_GEN_HV_B", "connected"]

    def test_load_flow_converges(self):
        """Both HV equivalents connected: LF converges, sum of P injections
        matches the transported LV net."""
        net = build_two_generators_network()
        eq, _ = build_equivalent_network_multi(net, self._SPECS_AB, "TX", n_samples=11)

        res = lf.run_ac(eq, parameters=self._LF_PARAMS)
        assert res[0].status_text == "Converged"

        gens = eq.get_generators()
        inj_p = -float(gens.loc["EQ_GEN_HV_A", "p"]) + -float(gens.loc["EQ_GEN_HV_B", "p"])
        # 400 + 200 - 15 - 8 = 577 MW of LV net -> ~567-576 MW at HV
        assert 560.0 < inj_p < 580.0, f"Sum of HV P injections: {inj_p:.3f}"

    def test_single_spec_is_equivalent_to_legacy_build(self):
        """A single-element specs list must behave identically to build_equivalent_network."""
        a = build_node_breaker_network()
        b = build_node_breaker_network()

        _, legacy_curve = build_equivalent_network(
            a, "GEN_LV", "TX", "AUX_LOAD", new_gen_id="GEN_HV_EQ", n_samples=11,
        )
        _, multi_curves = build_equivalent_network_multi(
            b, [GeneratorSpec("GEN_LV", "AUX_LOAD", "GEN_HV_EQ")], "TX", n_samples=11,
        )

        import pandas as pd
        pd.testing.assert_frame_equal(
            legacy_curve[["p", "min_q", "max_q"]].reset_index(drop=True),
            multi_curves[0][["p", "min_q", "max_q"]].reset_index(drop=True),
            atol=1e-9, check_exact=False,
        )

    def test_duplicate_aux_load_id_rejected(self):
        net = build_two_generators_network()
        specs = [
            GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_A"),
            GeneratorSpec("GEN_LV_B", "AUX_LOAD_A", "EQ_GEN_HV_B"),
        ]
        with pytest.raises(ValueError):
            build_equivalent_network_multi(net, specs, "TX", n_samples=11)

    def test_duplicate_new_id_rejected(self):
        net = build_two_generators_network()
        specs = [
            GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV"),
            GeneratorSpec("GEN_LV_B", "AUX_LOAD_B", "EQ_GEN_HV"),
        ]
        with pytest.raises(ValueError):
            build_equivalent_network_multi(net, specs, "TX", n_samples=11)

    def test_duplicate_generator_id_rejected(self):
        net = build_two_generators_network()
        specs = [
            GeneratorSpec("GEN_LV_A", "AUX_LOAD_A", "EQ_GEN_HV_1"),
            GeneratorSpec("GEN_LV_A", "AUX_LOAD_B", "EQ_GEN_HV_2"),
        ]
        with pytest.raises(ValueError):
            build_equivalent_network_multi(net, specs, "TX", n_samples=11)

    def test_empty_specs_rejected(self):
        net = build_two_generators_network()
        with pytest.raises(ValueError):
            build_equivalent_network_multi(net, [], "TX", n_samples=11)

    def test_single_spec_independent_equals_combined(self):
        """Single-generator: COMBINED_PROPORTIONAL must match INDEPENDENT (weight=1)."""
        a = build_node_breaker_network()
        b = build_node_breaker_network()
        specs = [GeneratorSpec("GEN_LV", "AUX_LOAD", "GEN_HV_EQ")]

        _, c_ind = build_equivalent_network_multi(
            a, specs, "TX", n_samples=11,
            loss_allocation=LossAllocation.INDEPENDENT,
        )
        _, c_com = build_equivalent_network_multi(
            b, specs, "TX", n_samples=11,
            loss_allocation=LossAllocation.COMBINED_PROPORTIONAL,
        )

        import pandas as pd
        pd.testing.assert_frame_equal(
            c_ind[0][["p", "min_q", "max_q"]].reset_index(drop=True),
            c_com[0][["p", "min_q", "max_q"]].reset_index(drop=True),
            atol=1e-9, check_exact=False,
        )

    def test_two_gens_independent_vs_combined_differ(self):
        """Two sizeable generators: policies must produce measurably different curves."""
        a = build_two_generators_network()
        b = build_two_generators_network()

        _, c_ind = build_equivalent_network_multi(
            a, self._SPECS_AB, "TX", n_samples=11,
            loss_allocation=LossAllocation.INDEPENDENT,
        )
        _, c_com = build_equivalent_network_multi(
            b, self._SPECS_AB, "TX", n_samples=11,
            loss_allocation=LossAllocation.COMBINED_PROPORTIONAL,
        )

        found = False
        for g in range(2):
            d = (c_ind[g][["p", "min_q", "max_q"]].reset_index(drop=True) -
                 c_com[g][["p", "min_q", "max_q"]].reset_index(drop=True)).abs()
            if (d > 1e-3).any().any():
                found = True
                break
        assert found, "COMBINED_PROPORTIONAL must differ from INDEPENDENT for two sizeable generators"

    def test_combined_sum_equals_exact_combined_transport(self):
        """Invariant: sum of per-gen HV P at sample i == average of exact combined
        transports at the Qlo and Qhi extremes of that sample."""
        net = build_two_generators_network()

        tp = get_oriented_transformer_params(net, "TX")
        gen_a = net.get_generators().loc["GEN_LV_A"]
        gen_b = net.get_generators().loc["GEN_LV_B"]
        v_lv = float(gen_a["target_v"])
        p_min_a, p_max_a = float(gen_a["min_p"]), float(gen_a["max_p"])
        p_min_b, p_max_b = float(gen_b["min_p"]), float(gen_b["max_p"])

        cpts = net.get_reactive_capability_curve_points()
        ca = cpts.loc["GEN_LV_A"].sort_values("p").reset_index(drop=True)
        cb = cpts.loc["GEN_LV_B"].sort_values("p").reset_index(drop=True)

        loads = net.get_loads()
        p_aux_a = float(loads.loc["AUX_LOAD_A", "p0"])
        q_aux_a = float(loads.loc["AUX_LOAD_A", "q0"])
        p_aux_b = float(loads.loc["AUX_LOAD_B", "p0"])
        q_aux_b = float(loads.loc["AUX_LOAD_B", "q0"])

        _, curves = build_equivalent_network_multi(
            net, self._SPECS_AB, "TX", n_samples=11,
            loss_allocation=LossAllocation.COMBINED_PROPORTIONAL,
        )
        ca_curve = curves[0].sort_values("p").reset_index(drop=True)
        cb_curve = curves[1].sort_values("p").reset_index(drop=True)

        for i in range(11):
            t = i / 10.0
            p_a = p_min_a + t * (p_max_a - p_min_a)
            p_b = p_min_b + t * (p_max_b - p_min_b)
            q_lo_a = float(np.interp(p_a, ca["p"], ca["min_q"]))
            q_lo_b = float(np.interp(p_b, cb["p"], cb["min_q"]))
            q_hi_a = float(np.interp(p_a, ca["p"], ca["max_q"]))
            q_hi_b = float(np.interp(p_b, cb["p"], cb["max_q"]))

            p_lv   = (p_a   - p_aux_a) + (p_b   - p_aux_b)
            q_lv_lo = (q_lo_a - q_aux_a) + (q_lo_b - q_aux_b)
            q_lv_hi = (q_hi_a - q_aux_a) + (q_hi_b - q_aux_b)

            p_hv_lo, _, _ = transport_point(
                p_lv, q_lv_lo, v_lv,
                tp["r"], tp["x"], tp["g"], tp["b"],
                tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
            )
            p_hv_hi, _, _ = transport_point(
                p_lv, q_lv_hi, v_lv,
                tp["r"], tp["x"], tp["g"], tp["b"],
                tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
            )

            p_sum = float(ca_curve["p"].iloc[i] + cb_curve["p"].iloc[i])
            p_ref = 0.5 * (p_hv_lo + p_hv_hi)
            assert abs(p_sum - p_ref) < 1e-6, \
                f"Sum of HV P_i must equal the exact combined transport at sample {i}"

    def test_combined_load_flow_converges(self):
        net = build_two_generators_network()
        eq, _ = build_equivalent_network_multi(
            net, self._SPECS_AB, "TX", n_samples=11,
            loss_allocation=LossAllocation.COMBINED_PROPORTIONAL,
        )
        res = lf.run_ac(eq, parameters=self._LF_PARAMS)
        assert res[0].status_text == "Converged"

        gens = eq.get_generators()
        inj_p = -float(gens.loc["EQ_GEN_HV_A", "p"]) + -float(gens.loc["EQ_GEN_HV_B", "p"])
        assert 560.0 < inj_p < 580.0, f"Sum of HV P injections: {inj_p:.3f}"
