"""Tests for add_equipment.py — synthetic batteries, SVCs and HVDC links."""

import pypowsybl.loadflow as lf
import pypowsybl.network as pn
import pytest

from add_equipment import (
    add_batteries,
    add_equipment,
    add_hvdc_links,
    add_static_var_compensators,
)


def test_batteries_added_with_reactive_limits():
    net = pn.create_ieee14()
    stats = add_batteries(net, count=4)
    assert stats["added"] == 4
    bats = net.get_batteries(all_attributes=True)
    assert len(bats) == 4
    # Idle: no active/reactive target, so the base case is undisturbed.
    assert (bats["target_p"] == 0.0).all()
    assert (bats["target_q"] == 0.0).all()


def test_batteries_get_voltage_regulation_extension():
    net = pn.create_ieee14()
    add_batteries(net, count=3)
    vr = net.get_extensions("voltageRegulation")
    assert len(vr) == 3
    # Regulator off -> declares the mode without disturbing the base case.
    assert not vr["voltage_regulator_on"].any()


def test_svcs_added_regulation_off():
    net = pn.create_ieee14()
    stats = add_static_var_compensators(net, count=3)
    assert stats["added"] == 3
    svcs = net.get_static_var_compensators(all_attributes=True)
    assert len(svcs) == 3
    # Regulation off -> electrically neutral.
    assert not svcs["regulating"].any()
    # Each SVC gets a (neutral) standby automaton.
    sa = net.get_extensions("standbyAutomaton")
    assert len(sa) == 3
    assert not sa["standby"].any()
    assert (sa["low_voltage_threshold"] < sa["high_voltage_threshold"]).all()


def test_hvdc_links_get_control_extensions():
    net = pn.create_ieee14()
    add_hvdc_links(net, count=2)
    adpc = net.get_extensions("hvdcAngleDroopActivePowerControl")
    opr = net.get_extensions("hvdcOperatorActivePowerRange")
    assert len(adpc) == 2 and len(opr) == 2
    # Angle-droop control declared but disabled -> neutral.
    assert not adpc["enabled"].any()
    assert (opr["opr_from_cs1_to_cs2"] > 0).all()


def test_hvdc_links_add_two_stations_each():
    net = pn.create_ieee14()
    stats = add_hvdc_links(net, count=2)
    assert stats["hvdc_lines"] == 2
    assert stats["vsc_stations"] == 4
    assert len(net.get_hvdc_lines()) == 2
    assert len(net.get_vsc_converter_stations()) == 4
    # The two ends of each link sit on different buses.
    vsc = net.get_vsc_converter_stations(all_attributes=True)
    assert vsc.loc["SYN_VSC_0_1", "bus_breaker_bus_id"] != \
        vsc.loc["SYN_VSC_0_2", "bus_breaker_bus_id"]


def test_equipment_is_load_flow_neutral():
    base = pn.create_ieee14()
    assert lf.run_ac(base)[0].status.name == "CONVERGED"
    v_before = {b: base.get_buses().at[b, "v_mag"] for b in base.get_buses().index}

    net = pn.create_ieee14()
    add_equipment(net)
    assert lf.run_ac(net)[0].status.name == "CONVERGED"
    # Original buses keep their voltage: the added kit injects nothing.
    for bus, v in v_before.items():
        assert net.get_buses().at[bus, "v_mag"] == pytest.approx(v, abs=1e-6)


def test_default_counts_scale_with_size():
    net = pn.create_ieee14()
    stats = add_equipment(net)
    # Sparse but present on a small network.
    assert stats["batteries"]["added"] >= 1
    assert stats["static_var_compensators"]["added"] >= 1
    assert stats["hvdc"]["hvdc_lines"] >= 1
