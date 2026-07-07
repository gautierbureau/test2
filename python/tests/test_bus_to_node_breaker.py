"""
Pytest suite for bus_to_node_breaker.py.

Checks that converting a bus-breaker network to node-breaker rebuilds the
topology as expected (one busbar section per bus, or several when requested)
and stays electrically transparent under an AC load flow.
"""

import pypowsybl.network as pn
import pytest

from bus_to_node_breaker import convert, validate


def _n_buses(net):
    return sum(len(net.get_bus_breaker_topology(vl).buses)
               for vl in net.get_voltage_levels().index)


def _multi_unit_network():
    """One bus (B1) with three generators, feeding a load bus over a line."""
    net = pn.create_empty("multi-unit")
    net.create_substations(id=["S1", "S2"])
    net.create_voltage_levels(
        id=["VL1", "VL2"], substation_id=["S1", "S2"],
        topology_kind=["BUS_BREAKER", "BUS_BREAKER"], nominal_v=[100.0, 100.0])
    net.create_buses(id=["B1", "B2"], voltage_level_id=["VL1", "VL2"])
    net.create_generators(
        id=["GA", "GB", "GC"], voltage_level_id=["VL1"] * 3, bus_id=["B1"] * 3,
        min_p=[0.0] * 3, max_p=[200.0] * 3, target_p=[50.0] * 3,
        target_v=[100.0] * 3, voltage_regulator_on=[True] * 3)
    net.create_minmax_reactive_limits(
        id=["GA", "GB", "GC"], min_q=[-100.0] * 3, max_q=[100.0] * 3)
    net.create_loads(id=["LD", "LD2"], voltage_level_id=["VL1", "VL2"],
                     bus_id=["B1", "B2"], p0=[30.0, 100.0], q0=[10.0, 20.0])
    net.create_lines(
        id=["L12"], voltage_level1_id=["VL1"], bus1_id=["B1"],
        voltage_level2_id=["VL2"], bus2_id=["B2"],
        r=[1.0], x=[5.0], g1=[0.0], b1=[0.0], g2=[0.0], b2=[0.0])
    return net


@pytest.mark.parametrize("factory", [pn.create_ieee14, pn.create_ieee118, pn.create_ieee300])
def test_conversion_is_electrically_transparent(factory):
    source = factory()
    target = convert(source)

    # Fully node-breaker, one busbar section per configured bus.
    kinds = set(target.get_voltage_levels(all_attributes=True)["topology_kind"])
    assert kinds == {"NODE_BREAKER"}
    assert len(target.get_busbar_sections()) == _n_buses(source)

    result = validate(source, target)
    assert result["source_converged"]
    assert result["target_converged"]
    assert result["max_dv_kv"] < 1e-2
    assert result["max_dangle_deg"] < 1e-1


@pytest.mark.parametrize("n", [2, 3])
def test_sectionalized_busbars(n):
    source = pn.create_ieee14()
    target = convert(source, busbar_sections_per_bus=n)

    buses = _n_buses(source)
    assert len(target.get_busbar_sections()) == buses * n

    # Each voltage level stays a single electrical bus (couplers are closed).
    for vl in target.get_voltage_levels().index:
        assert len(target.get_bus_breaker_topology(vl).buses) >= 1
    result = validate(source, target)
    assert result["target_converged"]
    assert result["max_dv_kv"] < 1e-2


def test_one_busbar_per_generator():
    net = _multi_unit_network()

    # Default policy: the 3-generator bus is split into three sections.
    target = convert(net)
    assert len(target.get_busbar_sections()) == 4  # 3 for B1 + 1 for B2

    gens = target.get_generators(all_attributes=True)
    assert gens.loc["GA", "node"] != gens.loc["GB", "node"] != gens.loc["GC", "node"]

    # Policy off collapses B1 back to a single busbar section.
    flat = convert(net, busbar_sections_per_bus=1, one_busbar_per_generator=False)
    assert len(flat.get_busbar_sections()) == 2

    assert validate(net, target)["max_dv_kv"] < 1e-2


def test_rejects_unsupported_type():
    net = pn.create_ieee14()
    net.create_batteries(
        id=["BAT"], voltage_level_id=["VL2"], bus_id=["B2"],
        max_p=[10.0], min_p=[-10.0], target_p=[1.0], target_q=[0.0])
    with pytest.raises(NotImplementedError):
        convert(net)
