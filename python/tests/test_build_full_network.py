"""
Pytest suite for build_full_network.py.

Runs the whole enhancement pipeline on the bundled IEEE cases (the fixtures in
``python/data/ieee*_full.xiidm.gz`` are built by exactly these calls) and checks
the result is a converged node-breaker network carrying the object types and
extensions the pipeline is supposed to add.
"""

import pypowsybl.loadflow as lf
import pypowsybl.network as pn
import pytest

from build_full_network import _BUILTINS, build_full

# Object types every enhanced fixture must carry, whatever the source case.
_REQUIRED_TYPES = (
    "BUSBAR_SECTION",           # node-breaker rebuild
    "SWITCH",
    "BATTERY",                  # synthetic equipment
    "STATIC_VAR_COMPENSATOR",
    "HVDC_LINE",
    "VSC_CONVERTER_STATION",
    "RATIO_TAP_CHANGER",
    "REACTIVE_CAPABILITY_CURVE_POINT",
    "SELECTED_LOADING_LIMITS",
    "PROPERTIES",
)

# Extensions every enhanced fixture must carry.
_REQUIRED_EXTENSIONS = (
    "activePowerControl",
    "branchObservability",
    "busbarSectionPosition",
    "detail",
    "discreteMeasurements",
    "generatorShortCircuit",
    "hvdcAngleDroopActivePowerControl",
    "identifiableShortCircuit",
    "injectionObservability",
    "measurements",
    "position",
    "standbyAutomaton",
    "voltageRegulation",
)


def test_builtins_cover_the_ieee_cases():
    assert sorted(_BUILTINS) == ["ieee118", "ieee14", "ieee300", "ieee57"]


@pytest.mark.parametrize("builtin", sorted(_BUILTINS))
def test_build_full_on_builtin_ieee_case(builtin, tmp_path):
    out = tmp_path / f"{builtin}_full.xiidm.gz"
    net = build_full(None, str(out), builtin=builtin)

    assert out.exists()

    # Node-breaker: one busbar section per source bus, feeders behind switches.
    source_buses = len(_BUILTINS[builtin]().get_buses())
    assert len(net.get_busbar_sections()) >= source_buses
    assert len(net.get_switches()) > 0
    assert (net.get_voltage_levels(all_attributes=True)["topology_kind"] == "NODE_BREAKER").all()

    for name in _REQUIRED_TYPES:
        assert len(net.get_elements(getattr(pn.ElementType, name))) > 0, name
    for name in _REQUIRED_EXTENSIONS:
        assert len(net.get_extensions(name)) > 0, name

    # The fixture is written in a converged state (DC-based voltage init).
    params = lf.Parameters(distributed_slack=True, use_reactive_limits=True,
                           voltage_init_mode=lf.VoltageInitMode.DC_VALUES)
    assert lf.run_ac(net, params)[0].status.name == "CONVERGED"


def test_bus_breaker_mode_skips_the_rebuild(tmp_path):
    net = build_full(None, str(tmp_path / "ieee14_bb.xiidm.gz"),
                     node_breaker=False, builtin="ieee14")
    assert len(net.get_busbar_sections()) == 0
    assert (net.get_voltage_levels(all_attributes=True)["topology_kind"] == "BUS_BREAKER").all()
    # The bus-breaker completions still ran.
    assert len(net.get_extensions("measurements")) > 0
