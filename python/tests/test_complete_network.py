"""
Pytest suite for complete_network.py.

Checks that missing generator reactive limits and ratio tap changers are filled
from a load flow, sized sensibly, and leave the base case unchanged (a neutral
tap changer is electrically transparent).
"""

import math

import pypowsybl.loadflow as lf
import pypowsybl.network as pn
import pytest

from complete_network import (
    add_active_power_control,
    add_rated_s,
    add_ratio_tap_changers,
    add_reactive_limits,
    set_generator_energy_source,
)
from tests.test_bus_to_node_breaker import _extended_ieee14


# ---------------------------------------------------------------------------
# Reactive limits
# ---------------------------------------------------------------------------

def test_reactive_limits_fill_placeholder_only():
    net = pn.create_ieee14()
    # IEEE-14 ships B1-G with a placeholder +/-1.8e308 band; the rest are finite.
    stats = add_reactive_limits(net)
    assert stats["filled"] == 1
    assert stats["skipped_existing"] == 4

    g = net.get_generators(all_attributes=True)
    assert abs(g.loc["B1-G", "min_q"]) < 1e4
    assert g.loc["B1-G", "min_q"] == pytest.approx(-g.loc["B1-G", "max_q"])
    # An untouched generator keeps its original finite band.
    assert g.loc["B2-G", "min_q"] == pytest.approx(-40.0)


def test_reactive_sizing_from_rated_s():
    net = pn.create_empty("s")
    net.create_substations(id=["S"])
    net.create_voltage_levels(id=["VL"], substation_id=["S"],
                              topology_kind=["BUS_BREAKER"], nominal_v=[100.0])
    net.create_buses(id=["B"], voltage_level_id=["VL"])
    net.create_generators(id=["G"], voltage_level_id=["VL"], bus_id=["B"],
                          min_p=[0.0], max_p=[80.0], target_p=[60.0],
                          target_v=[100.0], voltage_regulator_on=[True],
                          rated_s=[100.0])
    net.create_loads(id=["L"], voltage_level_id=["VL"], bus_id=["B"], p0=[60.0], q0=[0.0])

    add_reactive_limits(net, only_missing=False, run_loadflow=False)
    g = net.get_generators(all_attributes=True)
    # Q = sqrt(ratedS^2 - maxP^2) = sqrt(100^2 - 80^2) = 60.
    assert g.loc["G", "max_q"] == pytest.approx(60.0)


def test_reactive_sizing_power_factor_fallback():
    net = pn.create_ieee14()  # no rated_s -> power-factor fallback on max_p
    add_reactive_limits(net, power_factor=0.9, only_missing=False)
    g = net.get_generators(all_attributes=True)
    q = g.loc["B2-G", "max_q"]
    expected = abs(g.loc["B2-G", "max_p"]) * math.tan(math.acos(0.9))
    assert q == pytest.approx(expected)


def test_reactive_unity_power_factor_leaves_unsized():
    net = pn.create_ieee14()  # no rated_s -> power-factor branch
    stats = add_reactive_limits(net, power_factor=1.0, only_missing=False)
    # Unity power factor means zero reactive band; leave those generators unsized
    # rather than write a degenerate [0, 0] limit.
    assert stats["filled"] == 0
    assert stats["skipped_no_size"] == stats["generators"]


def test_reactive_rejects_bad_power_factor():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_reactive_limits(net, power_factor=0.0)
    with pytest.raises(ValueError):
        add_reactive_limits(net, power_factor=1.5)


# ---------------------------------------------------------------------------
# Ratio tap changers
# ---------------------------------------------------------------------------

def test_ratio_tap_changers_added_and_transparent():
    net = pn.create_ieee14()
    lf.run_ac(net)
    v_before = net.get_buses()["v_mag"].copy()

    stats = add_ratio_tap_changers(net, run_loadflow=False)
    assert stats["added"] == net.get_2_windings_transformers().shape[0]
    assert len(net.get_ratio_tap_changers()) == stats["added"]

    # Neutral tap, rho == 1 -> the base case is electrically unchanged.
    lf.run_ac(net)
    dv = (net.get_buses()["v_mag"] - v_before).abs().max()
    assert dv < 1e-6


def test_ratio_tap_changer_structure():
    net = pn.create_ieee14()
    add_ratio_tap_changers(net, steps_per_side=8, step_increment=0.0125)
    rtc = net.get_ratio_tap_changers(all_attributes=True)
    row = rtc.iloc[0]
    assert row["low_tap"] == 0
    assert row["tap"] == 8               # neutral
    assert bool(row["regulating"]) is True
    assert row["regulated_side"] == "TWO"
    assert row["target_v"] > 0

    steps = net.get_ratio_tap_changer_steps().loc[rtc.index[0]]
    assert len(steps) == 17              # 2 * 8 + 1
    assert steps["rho"].min() == pytest.approx(0.9)
    assert steps["rho"].max() == pytest.approx(1.1)
    # Neutral step is exactly rho = 1.
    assert steps["rho"].iloc[8] == pytest.approx(1.0)


def test_ratio_tap_changers_skip_existing():
    net = pn.create_ieee14()
    first = add_ratio_tap_changers(net)
    assert first["added"] == 3
    again = add_ratio_tap_changers(net, only_missing=True)
    assert again["added"] == 0
    assert again["skipped_existing"] == 3


def test_ratio_tap_changer_regulates_to_base_voltage():
    net = pn.create_ieee14()
    lf.run_ac(net)
    v_before = net.get_buses()["v_mag"].copy()
    add_ratio_tap_changers(net, run_loadflow=False)
    # With transformer voltage control on, the regulator holds its base-case
    # setpoint, so the tap stays at neutral and voltages barely move.
    lf.run_ac(net, lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                                 transformer_voltage_control_on=True))
    dv = (net.get_buses()["v_mag"] - v_before).abs().max()
    assert dv < 1e-3
    assert set(net.get_ratio_tap_changers()["tap"]) == {8}


def test_ratio_tap_changers_reject_bad_config():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_ratio_tap_changers(net, steps_per_side=0)
    with pytest.raises(ValueError):
        add_ratio_tap_changers(net, step_increment=0.0)


# ---------------------------------------------------------------------------
# Energy source + active power control
# ---------------------------------------------------------------------------

def test_energy_source_set_on_undefined_only():
    net = pn.create_ieee14()  # all generators start as OTHER
    stats = set_generator_energy_source(net, energy_source="THERMAL")
    assert stats["set"] == 5
    assert set(net.get_generators(all_attributes=True)["energy_source"]) == {"THERMAL"}

    # A generator with a real source is left alone.
    net.update_generators(id=["B2-G"], energy_source=["HYDRO"])
    again = set_generator_energy_source(net, energy_source="THERMAL")
    assert again["set"] == 0
    assert again["skipped_defined"] == 5
    assert net.get_generators(all_attributes=True).loc["B2-G", "energy_source"] == "HYDRO"


def test_energy_source_rejects_bad_value():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        set_generator_energy_source(net, energy_source="COAL")


def test_active_power_control_added_with_participation():
    net = pn.create_ieee14()
    stats = add_active_power_control(net, droop=5.0)
    assert stats["added"] == 5

    ext = net.get_extensions("activePowerControl")
    assert len(ext) == 5
    assert bool(ext["participate"].all())
    assert (ext["droop"] == 5.0).all()
    # Participation factor tracks the generator's active-power capability.
    gens = net.get_generators(all_attributes=True)
    for gid in ext.index:
        expected = gens.loc[gid, "max_p"] if gens.loc[gid, "max_p"] > 0 else 1.0
        assert ext.loc[gid, "participation_factor"] == pytest.approx(expected)

    # Idempotent: a second call adds nothing.
    again = add_active_power_control(net)
    assert again["added"] == 0
    assert again["skipped_existing"] == 5


def test_active_power_control_rejects_bad_droop():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_active_power_control(net, droop=0.0)


# ---------------------------------------------------------------------------
# Apparent power ratings (rated_s)
# ---------------------------------------------------------------------------

def test_rated_s_generator_from_power_factor():
    net = pn.create_empty("s")
    net.create_substations(id=["S"])
    net.create_voltage_levels(id=["VL"], substation_id=["S"],
                              topology_kind=["BUS_BREAKER"], nominal_v=[100.0])
    net.create_buses(id=["B"], voltage_level_id=["VL"])
    net.create_generators(id=["G"], voltage_level_id=["VL"], bus_id=["B"],
                          min_p=[0.0], max_p=[80.0], target_p=[60.0],
                          target_v=[100.0], voltage_regulator_on=[True])
    net.create_loads(id=["L"], voltage_level_id=["VL"], bus_id=["B"], p0=[60.0], q0=[0.0])

    add_rated_s(net, generator_power_factor=0.8, only_missing=False, run_loadflow=False)
    g = net.get_generators(all_attributes=True)
    # rated_s = maxP / power_factor = 80 / 0.8 = 100.
    assert g.loc["G", "rated_s"] == pytest.approx(100.0)


def test_rated_s_transformer_from_flow():
    net = pn.create_ieee14()
    add_rated_s(net, transformer_loading=0.6)
    txs = net.get_2_windings_transformers(all_attributes=True)
    for tid, t in txs.iterrows():
        s_base = max(math.hypot(t["p1"], t["q1"]), math.hypot(t["p2"], t["q2"]))
        # The base case loads the transformer at transformer_loading of rated_s.
        assert t["rated_s"] == pytest.approx(s_base / 0.6)
        assert s_base / t["rated_s"] == pytest.approx(0.6)


def test_rated_s_only_missing():
    net = pn.create_ieee14()
    net.update_generators(id=["B1-G"], rated_s=[500.0])
    stats = add_rated_s(net)
    assert stats["generators_skipped_existing"] == 1
    # The pre-set rating is left untouched.
    assert net.get_generators(all_attributes=True).loc["B1-G", "rated_s"] == 500.0


def test_rated_s_rejects_bad_config():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_rated_s(net, generator_power_factor=0.0)
    with pytest.raises(ValueError):
        add_rated_s(net, transformer_loading=0.0)
    with pytest.raises(ValueError):
        add_rated_s(net, transformer_loading=1.5)


# ---------------------------------------------------------------------------
# Combined + round trip
# ---------------------------------------------------------------------------

def test_both_completions_roundtrip(tmp_path):
    net = _extended_ieee14()
    add_reactive_limits(net)
    add_ratio_tap_changers(net)
    rtc_count = len(net.get_ratio_tap_changers())
    assert rtc_count > 0

    out = tmp_path / "completed.xiidm"
    net.save(str(out), format="XIIDM")
    reloaded = pn.load(str(out))
    assert len(reloaded.get_ratio_tap_changers()) == rtc_count
    # Base case still converges with the added equipment.
    result = lf.run_ac(reloaded)
    assert result[0].status.name == "CONVERGED"
