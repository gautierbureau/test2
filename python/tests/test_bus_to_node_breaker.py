"""
Pytest suite for bus_to_node_breaker.py.

Checks that converting a bus-breaker network to node-breaker rebuilds the
topology as expected (one busbar section per bus, or several when requested)
and stays electrically transparent under an AC load flow.
"""

import pandas as pd
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
    # With one_busbar_per_generator off, busbar_sections_per_bus applies to every
    # bus, so a sectionalized single busbar has exactly n sections per bus.
    target = convert(source, busbar_sections_per_bus=n, one_busbar_per_generator=False)

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


def test_generator_and_feeder_section_counts_are_decoupled():
    # busbar_sections_per_bus controls only non-generator buses; a generator bus
    # gets one section per generator no matter how large that count is.
    net = _multi_unit_network()  # B1: 3 generators, B2: load only
    target = convert(net, busbar_sections_per_bus=5, one_busbar_per_generator=True)

    def sections_of(vl):
        return len(target.get_busbar_sections(all_attributes=True).query(
            "voltage_level_id == @vl"))

    assert sections_of("VL1") == 3   # one per generator, not 5
    assert sections_of("VL2") == 5   # non-generator bus honours busbars-per-bus


def _extended_ieee14():
    """IEEE-14 grown with one of every supported extra equipment type."""
    net = pn.create_ieee14()
    net.create_batteries(id=["BAT1"], voltage_level_id=["VL10"], bus_id=["B10"],
                         min_p=[-20.0], max_p=[20.0], target_p=[5.0], target_q=[0.0])
    net.create_minmax_reactive_limits(id=["BAT1"], min_q=[-10.0], max_q=[10.0])
    net.create_static_var_compensators(
        id=["SVC1"], voltage_level_id=["VL4"], bus_id=["B4"], b_min=[-0.005],
        b_max=[0.005], regulation_mode=["VOLTAGE"], regulating=[True],
        target_v=[137.4], target_q=[0.0])
    net.create_boundary_lines(id=["DL1"], voltage_level_id=["VL13"], bus_id=["B13"],
                              p0=[3.0], q0=[1.0], r=[0.5], x=[1.0], g=[0.0], b=[0.0])
    sh = pd.DataFrame.from_records(index="id", data=[
        {"id": "SHNL1", "voltage_level_id": "VL14", "bus_id": "B14",
         "model_type": "NON_LINEAR", "section_count": 1}])
    nl = pd.DataFrame.from_records(index="id", data=[
        {"id": "SHNL1", "g": 0.0, "b": 0.02}, {"id": "SHNL1", "g": 0.0, "b": 0.05}])
    net.create_shunt_compensators(sh, non_linear_model_df=nl)
    net.create_vsc_converter_stations(
        id=["VSC_A", "VSC_B"], voltage_level_id=["VL5", "VL2"], bus_id=["B5", "B2"],
        loss_factor=[1.0, 1.0], voltage_regulator_on=[False, False],
        target_q=[0.0, 0.0], target_v=[float("nan"), float("nan")])
    net.create_minmax_reactive_limits(id=["VSC_A", "VSC_B"], min_q=[-50.0, -50.0],
                                      max_q=[50.0, 50.0])
    net.create_hvdc_lines(id=["HVDC1"], converter_station1_id=["VSC_A"],
                          converter_station2_id=["VSC_B"], r=[1.0], nominal_v=[150.0],
                          max_p=[60.0], target_p=[15.0],
                          converters_mode=["SIDE_1_RECTIFIER_SIDE_2_INVERTER"])
    net.create_lcc_converter_stations(
        id=["LCC_A", "LCC_B"], voltage_level_id=["VL3", "VL6"], bus_id=["B3", "B6"],
        power_factor=[0.9, 0.9], loss_factor=[1.1, 1.1])
    net.create_hvdc_lines(id=["HVDC_LCC"], converter_station1_id=["LCC_A"],
                          converter_station2_id=["LCC_B"], r=[1.5], nominal_v=[150.0],
                          max_p=[50.0], target_p=[10.0],
                          converters_mode=["SIDE_1_RECTIFIER_SIDE_2_INVERTER"])
    net.create_boundary_lines(id=["DLT_A"], voltage_level_id=["VL10"], bus_id=["B10"],
                              p0=[0.0], q0=[0.0], r=[0.1], x=[0.4], g=[0.0], b=[0.0],
                              pairing_key=["XN_10_11"])
    net.create_boundary_lines(id=["DLT_B"], voltage_level_id=["VL11"], bus_id=["B11"],
                              p0=[0.0], q0=[0.0], r=[0.1], x=[0.4], g=[0.0], b=[0.0],
                              pairing_key=["XN_10_11"])
    net.create_tie_lines(id=["TIE1"], boundary_line1_id=["DLT_A"],
                         boundary_line2_id=["DLT_B"])
    net.create_substations(id=["S_3W"])
    net.create_voltage_levels(id=["VL_3W_HV", "VL_3W_MV", "VL_3W_LV"],
                              substation_id=["S_3W"] * 3, topology_kind=["BUS_BREAKER"] * 3,
                              nominal_v=[135.0, 33.0, 11.0])
    net.create_buses(id=["B_3W_HV", "B_3W_MV", "B_3W_LV"],
                     voltage_level_id=["VL_3W_HV", "VL_3W_MV", "VL_3W_LV"])
    net.create_lines(id=["L_5_3W"], voltage_level1_id=["VL5"], bus1_id=["B5"],
                     voltage_level2_id=["VL_3W_HV"], bus2_id=["B_3W_HV"], r=[2.0],
                     x=[6.0], g1=[0.0], b1=[0.0], g2=[0.0], b2=[0.0])
    net.create_3_windings_transformers(
        id=["T3W"], rated_u0=[135.0],
        voltage_level1_id=["VL_3W_HV"], bus1_id=["B_3W_HV"], rated_u1=[135.0],
        r1=[0.5], x1=[10.0], g1=[0.0], b1=[0.0],
        voltage_level2_id=["VL_3W_MV"], bus2_id=["B_3W_MV"], rated_u2=[33.0],
        r2=[0.5], x2=[8.0], g2=[0.0], b2=[0.0],
        voltage_level3_id=["VL_3W_LV"], bus3_id=["B_3W_LV"], rated_u3=[11.0],
        r3=[0.5], x3=[6.0], g3=[0.0], b3=[0.0])
    net.create_loads(id=["LOAD_3W_MV", "LOAD_3W_LV"],
                     voltage_level_id=["VL_3W_MV", "VL_3W_LV"],
                     bus_id=["B_3W_MV", "B_3W_LV"], p0=[20.0, 10.0], q0=[5.0, 3.0])
    net.create_substations(id=["S_COUP"])
    net.create_voltage_levels(id=["VL_COUP"], substation_id=["S_COUP"],
                              topology_kind=["BUS_BREAKER"], nominal_v=[135.0])
    net.create_buses(id=["B_COUP_A", "B_COUP_B"], voltage_level_id=["VL_COUP", "VL_COUP"])
    net.create_switches(id=["COUPLER1"], voltage_level_id=["VL_COUP"],
                        bus1_id=["B_COUP_A"], bus2_id=["B_COUP_B"], kind=["BREAKER"],
                        open=[False])
    net.create_lines(id=["L_1_COUP"], voltage_level1_id=["VL1"], bus1_id=["B1"],
                     voltage_level2_id=["VL_COUP"], bus2_id=["B_COUP_A"], r=[2.0],
                     x=[6.0], g1=[0.0], b1=[0.0], g2=[0.0], b2=[0.0])
    net.create_loads(id=["LOAD_COUP"], voltage_level_id=["VL_COUP"], bus_id=["B_COUP_B"],
                     p0=[10.0], q0=[3.0])
    return net


def test_extended_network_all_types():
    source = _extended_ieee14()
    target = convert(source)

    # Every added type survives with its count preserved.
    for getter in ("get_batteries", "get_static_var_compensators", "get_dangling_lines",
                   "get_vsc_converter_stations", "get_lcc_converter_stations",
                   "get_hvdc_lines", "get_tie_lines", "get_3_windings_transformers"):
        assert len(getattr(target, getter)()) == len(getattr(source, getter)()), getter
    assert set(target.get_voltage_levels(all_attributes=True)["topology_kind"]) == {"NODE_BREAKER"}
    assert "NON_LINEAR" in set(target.get_shunt_compensators()["model_type"])

    result = validate(source, target)
    assert result["source_converged"] and result["target_converged"]
    assert result["max_dv_kv"] < 1e-2
    assert result["max_dangle_deg"] < 1e-1


def test_preserves_operational_limits_and_extensions():
    net = pn.create_ieee14()
    # A named current-limit group (permanent + temporary) on both sides of a line,
    # selected as the active group.
    rows = []
    for side in ("ONE", "TWO"):
        rows.append({"element_id": "L1-2-1", "side": side, "name": "permanent_limit",
                     "type": "CURRENT", "value": 1000.0, "acceptable_duration": -1,
                     "group_name": "SET_A"})
        rows.append({"element_id": "L1-2-1", "side": side, "name": "IT20min",
                     "type": "CURRENT", "value": 1100.0, "acceptable_duration": 1200,
                     "group_name": "SET_A"})
    net.create_operational_limits(pd.DataFrame(rows).set_index("element_id"))
    net.update_lines(id=["L1-2-1"], selected_limits_group_1=["SET_A"],
                     selected_limits_group_2=["SET_A"])
    # An id-keyed extension the rebuild used to drop.
    net.create_extensions("activePowerControl", id=["B1-G"], participate=[True],
                          droop=[4.0], participation_factor=[100.0])

    target = convert(net)

    ol = target.get_operational_limits(show_inactive_sets=True)
    assert not ol.empty
    groups = set(ol.index.get_level_values("group_name"))
    assert "SET_A" in groups
    sel = target.get_lines(attributes=["selected_limits_group_1", "selected_limits_group_2"])
    assert sel.loc["L1-2-1", "selected_limits_group_1"] == "SET_A"
    assert sel.loc["L1-2-1", "selected_limits_group_2"] == "SET_A"

    apc = target.get_extensions("activePowerControl")
    assert "B1-G" in apc.index
    assert apc.loc["B1-G", "participation_factor"] == 100.0


def test_rejects_node_breaker_input():
    # A network that is already node-breaker cannot be converted.
    node_breaker = convert(pn.create_ieee14())
    with pytest.raises(NotImplementedError):
        convert(node_breaker)
