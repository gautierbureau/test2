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
    add_discrete_measurements,
    add_hvdc_ac_emulation,
    add_load_detail,
    add_measurements,
    add_observability,
    add_phase_control,
    add_properties,
    add_rated_s,
    add_ratio_tap_changers,
    add_reactive_capability_curves,
    add_reactive_limits,
    add_secondary_voltage_control,
    add_shared_voltage_control,
    add_short_circuit,
    add_shunt_voltage_control,
    add_svc_standby_automaton,
    add_svc_voltage_control,
    add_transformer_voltage_control,
    set_generation_mix,
)
from add_equipment import add_hvdc_links, add_static_var_compensators
from bus_to_node_breaker import convert
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

def test_generation_mix_assigns_by_size():
    net = pn.create_ieee14()
    # Distinct capabilities so the size ranking is unambiguous.
    net.update_generators(id=["B1-G", "B2-G", "B3-G", "B6-G", "B8-G"],
                          max_p=[500.0, 400.0, 300.0, 200.0, 100.0])
    stats = set_generation_mix(
        net, mix={"NUCLEAR": 0.4, "HYDRO": 0.3, "SOLAR": 0.3})
    assert stats["assigned"] == 5
    src = net.get_generators(all_attributes=True)["energy_source"]
    # Largest unit is base-load (nuclear), smallest is intermittent (solar).
    assert src["B1-G"] == "NUCLEAR"
    assert src["B8-G"] == "SOLAR"
    assert set(src) <= {"NUCLEAR", "HYDRO", "SOLAR"}


def test_generation_mix_only_undefined_and_reject_bad():
    net = pn.create_ieee14()
    net.update_generators(id=["B1-G"], energy_source=["WIND"])
    stats = set_generation_mix(net)
    assert stats["skipped_defined"] == 1
    assert net.get_generators(all_attributes=True).loc["B1-G", "energy_source"] == "WIND"
    with pytest.raises(ValueError):
        set_generation_mix(net, mix={"COAL": 1.0})
    with pytest.raises(ValueError):
        set_generation_mix(net, mix={"NUCLEAR": -1.0})


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
# Synthetic measurements and observability
# ---------------------------------------------------------------------------

def test_measurements_from_load_flow():
    net = pn.create_ieee14()
    stats = add_measurements(net, relative_std_dev=0.02, std_dev_floor=0.5)
    # 16 injections x 2 + 20 branches x 6 = 152 measurements.
    assert stats["measurements"] == 152
    ext = net.get_extensions("measurements")
    assert set(ext["type"]) == {"ACTIVE_POWER", "REACTIVE_POWER", "CURRENT"}
    assert set(ext["side"]) == {"", "ONE", "TWO"}
    # Value equals the load-flow flow (add_measurements already solved the
    # network); std dev honours the relative/floor rule.
    p1 = net.get_lines(attributes=["p1"]).loc["L1-2-1", "p1"]
    row = ext.loc["L1-2-1"]
    meas = row[(row["type"] == "ACTIVE_POWER") & (row["side"] == "ONE")].iloc[0]
    assert meas["value"] == pytest.approx(p1)
    assert meas["standard_deviation"] == pytest.approx(max(abs(p1) * 0.02, 0.5))
    # Idempotent under only_missing.
    assert add_measurements(net, run_loadflow=False)["measurements"] == 0


def test_observability_flags():
    net = pn.create_ieee14()
    stats = add_observability(net, std_dev=2.0)
    assert stats["injections"] == 16   # 5 generators + 11 loads
    assert stats["branches"] == 20     # 17 lines + 3 transformers
    inj = net.get_extensions("injectionObservability")
    assert bool(inj["observable"].all())
    assert (inj["p_standard_deviation"] == 2.0).all()
    brc = net.get_extensions("branchObservability")
    assert bool(brc["observable"].all())
    # Idempotent under only_missing.
    again = add_observability(net)
    assert again["injections"] == 0 and again["branches"] == 0


def test_load_detail_splits_fixed_and_variable():
    net = pn.create_ieee14()
    stats = add_load_detail(net, fixed_fraction=0.4)
    assert stats["set"] == len(net.get_loads())
    det = net.get_extensions("detail")
    loads = net.get_loads()
    lid = loads.index[0]
    p0 = loads.at[lid, "p0"]
    assert det.at[lid, "fixed_p0"] == pytest.approx(0.4 * p0)
    assert det.at[lid, "variable_p0"] == pytest.approx(0.6 * p0)
    # Fixed + variable reconstruct the original setpoint.
    assert det.at[lid, "fixed_p0"] + det.at[lid, "variable_p0"] == pytest.approx(p0)
    # Idempotent under only_missing.
    assert add_load_detail(net)["set"] == 0
    with pytest.raises(ValueError):
        add_load_detail(pn.create_ieee14(), fixed_fraction=1.5)


def test_discrete_measurements_on_tap_changers():
    net = pn.create_ieee14()
    add_ratio_tap_changers(net)
    stats = add_discrete_measurements(net)
    assert stats["measurements"] == len(net.get_ratio_tap_changers())
    dm = net.get_extensions("discreteMeasurements")
    assert set(dm["type"]) == {"TAP_POSITION"}
    assert set(dm["tap_changer"]) == {"RATIO_TAP_CHANGER"}
    # Idempotent under only_missing.
    assert add_discrete_measurements(net)["measurements"] == 0


def test_properties_tag_substations_and_voltage_levels():
    net = pn.create_ieee14()
    stats = add_properties(net, region_count=4, country="FR")
    assert stats["substations"] == len(net.get_substations())
    assert stats["voltage_levels"] == len(net.get_voltage_levels())
    props = net.get_elements_properties()
    assert set(props["key"]) == {"region", "country_code", "voltage_class"}
    assert set(props[props["key"] == "country_code"]["value"]) == {"FR"}
    # Region partitioned into at most region_count zones; voltage class sensible.
    assert len(set(props[props["key"] == "region"]["value"])) <= 4
    assert set(props[props["key"] == "voltage_class"]["value"]) <= {"EHV", "HV", "MV", "LV"}
    # Keys avoid the native substation 'country' field, so a rebuild still works.
    assert "country" not in set(props["key"])
    # Idempotent under only_missing.
    assert add_properties(net) == {"substations": 0, "voltage_levels": 0}


def test_reactive_capability_curves_replace_bands():
    net = pn.create_ieee14()
    add_rated_s(net)
    stats = add_reactive_capability_curves(net, points=3)
    assert stats["set"] == len(net.get_generators())
    gens = net.get_generators(all_attributes=True)
    assert (gens["reactive_limits_kind"] == "CURVE").all()

    pts = net.get_reactive_capability_curve_points()
    # 3 points per generator.
    assert len(pts) == 3 * len(gens)
    gid = gens.index[0]
    gp = pts.loc[gid].sort_values("p")
    # Band narrows (or stays) as P rises, and is symmetric-ish about zero.
    assert gp["max_q"].iloc[-1] <= gp["max_q"].iloc[0] + 1e-6
    assert (gp["max_q"] > 0).all() and (gp["min_q"] < 0).all()
    # Idempotent under only_missing (all are CURVE now).
    assert add_reactive_capability_curves(net)["set"] == 0
    with pytest.raises(ValueError):
        add_reactive_capability_curves(pn.create_ieee14(), points=1)


def test_phase_control_enables_regulation_and_converges():
    net = pn.create_four_substations_node_breaker_network()
    lf.run_ac(net)
    # Free the phase shifter's transformer of its competing ratio-tap regulation.
    rtc = net.get_ratio_tap_changers(all_attributes=True)
    if not rtc.empty:
        net.update_ratio_tap_changers(id=list(rtc.index[rtc["regulating"]]),
                                      regulating=[False] * int(rtc["regulating"].sum()))
    stats = add_phase_control(net)
    assert stats["phase_shifters"] >= 1
    ptc = net.get_phase_tap_changers(all_attributes=True)
    on = ptc[ptc["regulating"]]
    assert set(on["regulation_mode"]) == {"CURRENT_LIMITER"}
    # Converges with the phase-control outer loop enabled.
    res = lf.run_ac(net, lf.Parameters(phase_shifter_regulation_on=True))
    assert res[0].status.name == "CONVERGED"
    # Idempotent: already-regulating shifters are not re-enabled.
    assert add_phase_control(net)["phase_shifters"] == 0


def _two_gen_voltage_level():
    """A voltage level with two generators on separate buses feeding a load."""
    n = pn.create_empty("shared")
    n.create_substations(id=["S1", "S2"])
    n.create_voltage_levels(id=["VG", "VL"], substation_id=["S1", "S2"],
                            topology_kind=["BUS_BREAKER", "BUS_BREAKER"],
                            nominal_v=[225.0, 225.0])
    n.create_buses(id=["A1", "A2"], voltage_level_id=["VG", "VG"])
    n.create_buses(id=["LB"], voltage_level_id=["VL"])
    n.create_generators(id=["GA"], voltage_level_id=["VG"], bus_id=["A1"], target_p=[60],
                        min_p=[0], max_p=[200], target_v=[230], voltage_regulator_on=[True])
    n.create_generators(id=["GB"], voltage_level_id=["VG"], bus_id=["A2"], target_p=[60],
                        min_p=[0], max_p=[200], target_v=[230], voltage_regulator_on=[True])
    n.create_loads(id=["LD"], voltage_level_id=["VL"], bus_id=["LB"], p0=[100], q0=[20])
    n.create_lines(id=["L1"], voltage_level1_id=["VG"], bus1_id=["A1"],
                   voltage_level2_id=["VL"], bus2_id=["LB"], r=[1], x=[10], g1=[0], b1=[0], g2=[0], b2=[0])
    n.create_lines(id=["L2"], voltage_level1_id=["VG"], bus1_id=["A2"],
                   voltage_level2_id=["VL"], bus2_id=["LB"], r=[1], x=[10], g1=[0], b1=[0], g2=[0], b2=[0])
    return n


def test_shared_voltage_control_groups_generators_on_one_bus():
    net = convert(_two_gen_voltage_level())
    lf.run_ac(net)
    stats = add_shared_voltage_control(net)
    assert stats["groups"] == 1 and stats["generators"] == 1
    g = net.get_generators(all_attributes=True)
    # Both generators now regulate the same bus.
    assert g.at["GA", "regulated_bus_id"] == g.at["GB", "regulated_bus_id"]
    assert lf.run_ac(net)[0].status.name == "CONVERGED"


def test_hvdc_ac_emulation_enables_and_converges():
    net = pn.create_ieee14()
    add_hvdc_links(net, count=1)   # HVDC link + angle-droop extension (disabled)
    net = convert(net)
    lf.run_ac(net)
    stats = add_hvdc_ac_emulation(net)
    assert stats["hvdc"] == 1
    ext = net.get_extensions("hvdcAngleDroopActivePowerControl")
    assert bool(ext["enabled"].all())
    assert lf.run_ac(net)[0].status.name == "CONVERGED"


def test_shunt_voltage_control_adds_switchable_regulating_shunts():
    net = convert(pn.create_ieee14())
    lf.run_ac(net)
    stats = add_shunt_voltage_control(net, count=2)
    assert stats["shunts"] == 2
    sh = net.get_shunt_compensators(all_attributes=True)
    added = sh[sh.index.str.startswith("SYN_SHVC_")]
    assert (added["max_section_count"] > 1).all()          # switchable
    assert bool(added["voltage_regulation_on"].all())      # regulating
    assert lf.run_ac(net, lf.Parameters(
        shunt_compensator_voltage_control_on=True))[0].status.name == "CONVERGED"


def test_transformer_voltage_control_keeps_converging_subset():
    net = pn.create_ieee14()
    add_ratio_tap_changers(net)  # makes all transformers voltage-regulating
    n_reg = int(net.get_ratio_tap_changers(all_attributes=True)["regulating"].sum())
    assert n_reg > 0
    lf.run_ac(net)
    stats = add_transformer_voltage_control(net, count=n_reg)
    assert stats["regulating"] >= 1
    # The kept set converges with the transformer-voltage-control loop on.
    res = lf.run_ac(net, lf.Parameters(transformer_voltage_control_on=True))
    assert res[0].status.name == "CONVERGED"
    rtc = net.get_ratio_tap_changers(all_attributes=True)
    assert int(rtc["regulating"].sum()) == stats["regulating"]


def test_svc_voltage_control_enables_and_converges():
    net = pn.create_ieee14()
    add_static_var_compensators(net, count=2)
    lf.run_ac(net)
    stats = add_svc_voltage_control(net)
    assert stats["svcs"] == 2
    svc = net.get_static_var_compensators(all_attributes=True)
    on = svc[svc["regulating"]]
    assert set(on["regulation_mode"]) == {"VOLTAGE"}
    assert len(net.get_extensions("voltagePerReactivePowerControl")) == 2
    # Converges both by default (svcVoltageMonitoring) and with the slope on.
    assert lf.run_ac(net)[0].status.name == "CONVERGED"
    assert lf.run_ac(net, lf.Parameters(
        provider_parameters={"voltagePerReactivePowerControl": "true"}))[0].status.name == "CONVERGED"
    # Idempotent: already-regulating SVCs are not touched again.
    assert add_svc_voltage_control(net)["svcs"] == 0


def test_svc_standby_automaton_enables_and_converges():
    net = pn.create_ieee14()
    add_static_var_compensators(net, count=2)
    lf.run_ac(net)
    add_svc_voltage_control(net)
    stats = add_svc_standby_automaton(net)
    assert stats["standby"] >= 1
    ext = net.get_extensions("standbyAutomaton")
    on = ext[ext["standby"]]
    assert len(on) == stats["standby"]
    # Standby b0 is neutral and the thresholds bracket the solved voltage.
    assert set(on["b0"]) == {0.0}
    svc = net.get_static_var_compensators(all_attributes=True)
    buses = net.get_buses()
    for sid in on.index:
        v = buses.at[svc.at[sid, "bus_id"], "v_mag"]
        assert on.at[sid, "low_voltage_threshold"] < v < on.at[sid, "high_voltage_threshold"]
    assert lf.run_ac(net)[0].status.name == "CONVERGED"


def test_svc_standby_automaton_no_svcs():
    net = pn.create_ieee14()
    assert add_svc_standby_automaton(net)["standby"] == 0


def test_secondary_voltage_control_two_zones_and_converges():
    net = pn.create_ieee14()
    lf.run_ac(net)
    stats = add_secondary_voltage_control(net, zones=2, units_per_zone=2)
    assert stats["zones"] == 2
    assert stats["units"] == 4
    zt = net.get_extensions("secondaryVoltageControl", "zones")
    ut = net.get_extensions("secondaryVoltageControl", "units")
    assert len(zt) == 2
    assert len(ut) == 4
    # Each zone controls a distinct pilot bus, target = its solved voltage.
    buses = net.get_buses()
    for name in zt.index:
        pilot = zt.at[name, "bus_ids"]
        assert math.isclose(zt.at[name, "target_v"], buses.at[pilot, "v_mag"], rel_tol=1e-6)
    assert bool(ut["participate"].all())
    # Converges both with the outer loop off and on.
    assert lf.run_ac(net)[0].status.name == "CONVERGED"
    assert lf.run_ac(net, lf.Parameters(
        provider_parameters={"secondaryVoltageControl": "true"}))[0].status.name == "CONVERGED"


def test_secondary_voltage_control_node_breaker_uses_busbar_pilot():
    # In node-breaker the pilot point must reference a busbar section, not a
    # bus-view bus id (the latter trips a NullPointerException in OpenLoadFlow).
    nb = convert(_extended_ieee14())
    lf.run_ac(nb)
    stats = add_secondary_voltage_control(nb, zones=2, units_per_zone=2)
    assert stats["zones"] == 2
    zt = nb.get_extensions("secondaryVoltageControl", "zones")
    bbs = set(nb.get_busbar_sections().index)
    assert set(zt["bus_ids"]).issubset(bbs)
    assert lf.run_ac(nb, lf.Parameters(
        provider_parameters={"secondaryVoltageControl": "true"}))[0].status.name == "CONVERGED"


def test_secondary_voltage_control_insufficient_generators():
    net = pn.create_ieee14()
    lf.run_ac(net)
    # Asking for more units per zone than there are regulating generators yields none.
    assert add_secondary_voltage_control(net, zones=1, units_per_zone=99)["zones"] == 0


def test_short_circuit_on_generators_and_voltage_levels():
    net = pn.create_ieee14()
    add_rated_s(net)
    stats = add_short_circuit(net)
    assert stats["generators"] == len(net.get_generators())
    assert stats["voltage_levels"] == len(net.get_voltage_levels())

    gsc = net.get_extensions("generatorShortCircuit")
    # Reactances are positive ohms, subtransient below transient.
    assert (gsc["direct_trans_x"] > 0).all()
    assert (gsc["direct_sub_trans_x"] < gsc["direct_trans_x"]).all()

    isc = net.get_extensions("identifiableShortCircuit")
    assert set(isc["equipment_type"]) == {"VOLTAGE_LEVEL"}
    assert (isc["ip_min"] < isc["ip_max"]).all()
    # Idempotent under only_missing.
    assert add_short_circuit(net) == {"generators": 0, "voltage_levels": 0}


def test_measurements_reject_bad_std():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_measurements(net, relative_std_dev=-0.1)


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
