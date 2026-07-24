"""Tests for add_remote_voltage_control.py — deporting generators for remote VC."""

import pypowsybl.loadflow as lf
import pypowsybl.network as pn

from add_remote_voltage_control import deport_generators
from bus_to_node_breaker import convert


def _nb_ieee14():
    return convert(pn.create_ieee14())


def test_deported_generator_regulates_hv_bus_remotely():
    net = _nb_ieee14()
    # IEEE-14 tops out at 135 kV, so lower the threshold for the test.
    net, stats = deport_generators(net, count=1, hv_threshold=100.0)
    assert stats["deported"] == 1
    g = net.get_generators(all_attributes=True)
    gid = next(x for x in g.index if g.at[x, "voltage_level_id"].endswith("_GSU_VL"))
    # Now sits on a new LV bus but regulates a bus in the original HV level.
    assert g.at[gid, "voltage_level_id"].endswith("_GSU_VL")
    assert not g.at[gid, "regulated_bus_id"].startswith(g.at[gid, "voltage_level_id"])
    assert g.at[gid, "voltage_regulator_on"]
    # A GSU transformer was created for it.
    assert f"{gid}_GSU_TX" in net.get_2_windings_transformers().index


def test_deportation_keeps_convergence():
    net = _nb_ieee14()
    net, _ = deport_generators(net, count=2, hv_threshold=100.0)
    res = lf.run_ac(net, lf.Parameters(
        distributed_slack=True, use_reactive_limits=True,
        voltage_init_mode=lf.VoltageInitMode.DC_VALUES))
    assert res[0].status.name == "CONVERGED"


def test_no_eligible_generators_is_a_noop():
    net = _nb_ieee14()
    # No bus at/above 400 kV in IEEE-14.
    _, stats = deport_generators(net, hv_threshold=400.0)
    assert stats["deported"] == 0
