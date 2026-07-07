"""
Pytest suite for add_current_limits.py.

Checks that load-flow-based current-limit sets are sized per side, cover every
branch type, round-trip through XIIDM, and leave the base case within its own
permanent limits.
"""

import math

import pypowsybl.loadflow as lf
import pypowsybl.network as pn
import pytest

from add_current_limits import (
    DEFAULT_PERMANENT_MARGIN,
    add_current_limits,
    loading_report,
)
from tests.test_bus_to_node_breaker import _extended_ieee14


def _permanent_limits(net, group_name="LOADFLOW_BASED"):
    """(element_id, side) -> permanent CURRENT limit value for a group."""
    ol = net.get_operational_limits(show_inactive_sets=True)
    idx = ol.index
    mask = ((idx.get_level_values("type") == "CURRENT")
            & (idx.get_level_values("acceptable_duration") == -1)
            & (idx.get_level_values("group_name") == group_name))
    perm = ol[mask]
    return {(e, s): row["value"]
            for (e, s, _t, _d, _g), row in perm.iterrows()}


def test_limits_created_on_every_branch_side():
    net = pn.create_ieee14()
    stats = add_current_limits(net)

    # 17 lines (2 sides) + 3 transformers (2 sides) = 40 sides.
    assert stats["sides"] == 40
    assert stats["branches"] == 20
    assert stats["permanent_limits"] == 40
    assert stats["temporary_limits"] == 40 * 3
    assert stats["skipped_no_flow"] == 0

    ol = net.get_operational_limits(show_inactive_sets=True)
    assert (ol.index.get_level_values("type") == "CURRENT").all()
    # One permanent (-1) + three temporary tiers per side.
    durations = set(ol.index.get_level_values("acceptable_duration"))
    assert durations == {-1, 1200, 600, 60}


def test_sizing_is_per_side_and_scaled():
    net = pn.create_ieee14()
    lf.run_ac(net)
    txs = net.get_2_windings_transformers(attributes=["i1", "i2"])
    add_current_limits(net, run_loadflow=False)

    perm = _permanent_limits(net)
    for tid, row in txs.iterrows():
        # Each side's permanent limit tracks its own current, not the other's.
        assert perm[(tid, "ONE")] == pytest.approx(row["i1"] * DEFAULT_PERMANENT_MARGIN)
        assert perm[(tid, "TWO")] == pytest.approx(row["i2"] * DEFAULT_PERMANENT_MARGIN)
    # The two transformer sides really do differ (voltage ratio).
    assert perm[("T4-7-1", "ONE")] != pytest.approx(perm[("T4-7-1", "TWO")])


def test_temporary_tiers_ladder():
    net = pn.create_ieee14()
    add_current_limits(net, permanent_margin=1.2,
                       temporary_tiers=[(900, 1.15), (90, 1.30)])
    ol = net.get_operational_limits(show_inactive_sets=True)
    one = ol.xs(("L1-2-1", "ONE"), level=("element_id", "side"))
    by_dur = {dur: (row["name"], row["value"])
              for (_type, dur, _grp), row in one.iterrows()}
    permanent = by_dur[-1][1]
    assert by_dur[900] == ("IT15min", pytest.approx(permanent * 1.15))   # 900 s = 15 min
    assert by_dur[90] == ("IT90s", pytest.approx(permanent * 1.30))      # 90 s, not a whole minute


def test_group_selected_by_default_and_optional():
    net = pn.create_ieee14()
    add_current_limits(net, group_name="MY_SET")
    sel = net.get_lines(attributes=["selected_limits_group_1", "selected_limits_group_2"])
    assert set(sel["selected_limits_group_1"]) == {"MY_SET"}
    assert set(sel["selected_limits_group_2"]) == {"MY_SET"}

    net2 = pn.create_ieee14()
    add_current_limits(net2, group_name="MY_SET", select=False)
    sel2 = net2.get_lines(attributes=["selected_limits_group_1"])
    assert not any(sel2["selected_limits_group_1"] == "MY_SET")
    # The group still exists, just not active.
    assert not net2.get_operational_limits(show_inactive_sets=True).empty


def test_base_case_within_permanent_limits():
    net = pn.create_ieee14()
    add_current_limits(net, permanent_margin=1.25)
    report = loading_report(net, run_loadflow=False)
    assert report["sides"] == 40
    assert report["overloaded"] == 0
    # Base case sits at exactly 1 / permanent_margin of the permanent limit.
    assert report["max_loading"] == pytest.approx(1 / 1.25, abs=1e-6)


def test_all_branch_types_covered_and_roundtrip(tmp_path):
    net = _extended_ieee14()
    stats = add_current_limits(net)
    assert set(stats["by_type"]) >= {
        "line", "2_windings_transformer", "3_windings_transformer", "boundary_line"}

    out = tmp_path / "with_limits.xiidm"
    net.save(str(out), format="XIIDM")
    reloaded = pn.load(str(out))
    before = len(net.get_operational_limits(show_inactive_sets=True))
    after = len(reloaded.get_operational_limits(show_inactive_sets=True))
    assert before == after > 0
    # Selection survives the round trip and stays valid.
    report = loading_report(reloaded)
    assert report["overloaded"] == 0


def test_skips_sides_without_flow():
    net = pn.create_ieee14()
    # Add a line that stays de-energised: its current is NaN after the flow.
    net.create_substations(id=["S_ISO"])
    net.create_voltage_levels(id=["VL_ISO"], substation_id=["S_ISO"],
                              topology_kind=["BUS_BREAKER"], nominal_v=[135.0])
    net.create_buses(id=["B_ISO"], voltage_level_id=["VL_ISO"])
    net.create_lines(id=["DEAD"], voltage_level1_id=["VL1"], bus1_id=["B1"],
                     voltage_level2_id=["VL_ISO"], bus2_id=["B_ISO"],
                     r=[1.0], x=[5.0], g1=[0.0], b1=[0.0], g2=[0.0], b2=[0.0])
    stats = add_current_limits(net)
    assert stats["skipped_no_flow"] >= 1
    # The dead line gets no permanent limit.
    perm = _permanent_limits(net)
    assert ("DEAD", "ONE") not in perm


def test_only_missing_preserves_existing_sets():
    net = pn.create_ieee14()
    add_current_limits(net, group_name="FIRST")
    stats = add_current_limits(net, group_name="SECOND", only_missing=True)
    # Every branch already had a set, so nothing new is added.
    assert stats["branches"] == 0
    assert stats["skipped_existing"] == 20
    groups = set(net.get_operational_limits(show_inactive_sets=True)
                 .index.get_level_values("group_name"))
    assert groups == {"FIRST"}


def test_partial_flow_branch_selects_only_live_sides():
    # A three-winding transformer with one de-energised leg: that leg carries
    # NaN, so no limit is created there and the group must not be selected on it
    # (pypowsybl rejects selecting a group that has no limit on a side).
    net = _extended_ieee14()
    net.update_3_windings_transformers(id=["T3W"], connected3=[False])
    stats = add_current_limits(net)  # must not raise
    assert stats["skipped_no_flow"] >= 1

    sel = net.get_3_windings_transformers(
        attributes=["selected_limits_group_1", "selected_limits_group_2",
                    "selected_limits_group_3"]).loc["T3W"]
    assert sel["selected_limits_group_1"] == "LOADFLOW_BASED"
    assert sel["selected_limits_group_2"] == "LOADFLOW_BASED"
    assert sel["selected_limits_group_3"] != "LOADFLOW_BASED"  # dead leg untouched
    # Still round-trips and stays within limits.
    assert loading_report(net, run_loadflow=False)["overloaded"] == 0


def test_rejects_bad_config():
    net = pn.create_ieee14()
    with pytest.raises(ValueError):
        add_current_limits(net, permanent_margin=0.0)
    with pytest.raises(ValueError):
        add_current_limits(net, permanent_margin=0.9)  # <= 1: limit below flow
    with pytest.raises(ValueError):
        add_current_limits(net, temporary_tiers=[(600, 0.9)])  # margin <= 1
    with pytest.raises(ValueError):
        add_current_limits(net, temporary_tiers=[(600, 1.1), (600, 1.2)])  # dup


@pytest.mark.parametrize("factory", [pn.create_ieee14, pn.create_ieee118, pn.create_ieee300])
def test_scales_to_ieee_cases(factory):
    net = factory()
    stats = add_current_limits(net)
    assert stats["sides"] > 0
    report = loading_report(net, run_loadflow=False)
    assert report["overloaded"] == 0
    assert report["max_loading"] == pytest.approx(1 / DEFAULT_PERMANENT_MARGIN, abs=1e-6)
