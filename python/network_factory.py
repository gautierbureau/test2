"""
Factory functions that build pypowsybl test networks.

Each function returns a ready-to-use ``pn.Network`` that exercises a
different combination of topology (BUS_BREAKER vs NODE_BREAKER) and number
of LV generators.  The networks are used by the transport_curve module and
by the test suite.
"""

from __future__ import annotations

import pypowsybl.network as pn


def build_test_network() -> pn.Network:
    """Build a 400 kV / 20 kV test network with one generator and aux load."""
    net = pn.create_empty("test_aux_equivalent")

    net.create_substations(id=["S_MAIN"])

    net.create_voltage_levels(
        id=["VL_HV", "VL_LV"],
        substation_id=["S_MAIN", "S_MAIN"],
        topology_kind=["BUS_BREAKER", "BUS_BREAKER"],
        nominal_v=[400.0, 20.0],
    )

    net.create_buses(
        id=["BUS_HV", "BUS_LV"],
        voltage_level_id=["VL_HV", "VL_LV"],
    )

    # External grid representation: a slack generator on HV regulating to 400 kV.
    # Big P range so it absorbs/produces whatever the transformer delivers.
    net.create_generators(
        id=["EXT_GRID"],
        voltage_level_id=["VL_HV"],
        bus_id=["BUS_HV"],
        min_p=[-5000.0],
        max_p=[5000.0],
        target_p=[0.0],
        target_v=[400.0],
        target_q=[0.0],
        voltage_regulator_on=[True],
    )
    net.create_minmax_reactive_limits(
        id=["EXT_GRID"], min_q=[-5000.0], max_q=[5000.0]
    )

    # A small load on HV so the network is non-trivial.
    net.create_loads(
        id=["HV_LOAD"],
        voltage_level_id=["VL_HV"],
        bus_id=["BUS_HV"],
        p0=[200.0],
        q0=[40.0],
    )

    # Generator on the LV side with a typical "D-shape" reactive curve.
    net.create_generators(
        id=["GEN_LV"],
        voltage_level_id=["VL_LV"],
        bus_id=["BUS_LV"],
        min_p=[0.0],
        max_p=[500.0],
        target_p=[400.0],
        target_v=[20.5],
        target_q=[0.0],
        voltage_regulator_on=[True],
    )

    p_pts = [0.0, 100.0, 300.0, 500.0]
    qmin  = [-150.0, -200.0, -180.0, -100.0]
    qmax  = [+150.0, +250.0, +220.0, +120.0]
    net.create_curve_reactive_limits(
        id=["GEN_LV"] * len(p_pts),
        p=p_pts,
        min_q=qmin,
        max_q=qmax,
    )

    # Auxiliary load of the generator on the LV side (constant PQ).
    net.create_loads(
        id=["AUX_LOAD"],
        voltage_level_id=["VL_LV"],
        bus_id=["BUS_LV"],
        p0=[15.0],
        q0=[5.0],
    )

    # 400/20 kV transformer.
    # IIDM convention: R, X, G, B are referred to side 2 (LV here).
    sn = 600.0
    zb_lv = 20.0 ** 2 / sn
    yb_lv = 1.0 / zb_lv
    net.create_2_windings_transformers(
        id=["TX"],
        voltage_level1_id=["VL_HV"],
        bus1_id=["BUS_HV"],
        voltage_level2_id=["VL_LV"],
        bus2_id=["BUS_LV"],
        rated_u1=[400.0],
        rated_u2=[20.0],
        rated_s=[sn],
        b=[-0.018 * yb_lv],
        g=[0.0006 * yb_lv],
        r=[0.004  * zb_lv],
        x=[0.12   * zb_lv],
    )

    return net


def build_two_generators_network() -> pn.Network:
    """Mirror of Java's NodeBreakerNetworkFactory.createTwoGenerators().

    HV is NODE_BREAKER, LV is BUS_BREAKER. Two generators (GEN_LV_A, GEN_LV_B)
    with distinct aux loads (AUX_LOAD_A, AUX_LOAD_B) share the same LV bus
    behind the single transformer TX. HV_LOAD is 600/80 to balance the larger
    LV injection.
    """
    net = pn.create_empty("test_two_gens")
    net.create_substations(id=["S_MAIN"])

    net.create_voltage_levels(
        id=["VL_HV"], substation_id=["S_MAIN"],
        topology_kind=["NODE_BREAKER"], nominal_v=[400.0],
    )
    net.create_busbar_sections(id=["BBS_HV"], voltage_level_id=["VL_HV"], node=[0])

    net.create_switches(id=["DISC_EXT"], voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[1], open=[False])
    net.create_switches(id=["BRK_EXT"], voltage_level_id=["VL_HV"],
                        kind=["BREAKER"], node1=[1], node2=[2], open=[False])
    net.create_generators(
        id=["EXT_GRID"], voltage_level_id=["VL_HV"], node=[2],
        min_p=[-5000.0], max_p=[5000.0], target_p=[0.0],
        target_v=[400.0], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_minmax_reactive_limits(id=["EXT_GRID"], min_q=[-5000.0], max_q=[5000.0])

    net.create_switches(id=["DISC_LOAD"], voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[3], open=[False])
    net.create_switches(id=["BRK_LOAD"], voltage_level_id=["VL_HV"],
                        kind=["BREAKER"], node1=[3], node2=[4], open=[False])
    net.create_loads(id=["HV_LOAD"], voltage_level_id=["VL_HV"], node=[4],
                     p0=[600.0], q0=[80.0])

    net.create_switches(id=["DISC_TX"], voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[5], open=[False])
    net.create_switches(id=["BRK_TX"], voltage_level_id=["VL_HV"],
                        kind=["BREAKER"], node1=[5], node2=[6], open=[False])

    net.create_voltage_levels(
        id=["VL_LV"], substation_id=["S_MAIN"],
        topology_kind=["BUS_BREAKER"], nominal_v=[20.0],
    )
    net.create_buses(id=["BUS_LV"], voltage_level_id=["VL_LV"])

    net.create_generators(
        id=["GEN_LV_A"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
        min_p=[0.0], max_p=[500.0], target_p=[400.0],
        target_v=[20.5], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_curve_reactive_limits(
        id=["GEN_LV_A"] * 4, p=[0.0, 100.0, 300.0, 500.0],
        min_q=[-150.0, -200.0, -180.0, -100.0],
        max_q=[ 150.0,  250.0,  220.0,  120.0],
    )
    net.create_loads(id=["AUX_LOAD_A"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
                     p0=[15.0], q0=[5.0])

    net.create_generators(
        id=["GEN_LV_B"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
        min_p=[0.0], max_p=[400.0], target_p=[200.0],
        target_v=[20.5], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_curve_reactive_limits(
        id=["GEN_LV_B"] * 3, p=[0.0, 200.0, 400.0],
        min_q=[-120.0, -150.0, -80.0],
        max_q=[ 120.0,  180.0, 100.0],
    )
    net.create_loads(id=["AUX_LOAD_B"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
                     p0=[8.0], q0=[3.0])

    sn = 600.0
    zb_lv = 20.0 ** 2 / sn
    yb_lv = 1.0 / zb_lv
    net.create_2_windings_transformers(
        id=["TX"],
        voltage_level1_id=["VL_HV"], node1=[6],
        voltage_level2_id=["VL_LV"], bus2_id=["BUS_LV"],
        rated_u1=[400.0], rated_u2=[20.0], rated_s=[sn],
        r=[0.004 * zb_lv], x=[0.12 * zb_lv],
        g=[0.0006 * yb_lv], b=[-0.018 * yb_lv],
    )
    return net


def build_node_breaker_network() -> pn.Network:
    """
    Electrically identical to build_test_network() but with VL_HV in
    NODE_BREAKER topology.

    VL_HV (400 kV, NODE_BREAKER):
      node 0  BusbarSection BBS_HV
      DISC_EXT  0-1  |  BRK_EXT  1-2  |  EXT_GRID at 2
      DISC_LOAD 0-3  |  BRK_LOAD 3-4  |  HV_LOAD  at 4
      DISC_TX   0-5  |  BRK_TX   5-6  |  TX (HV terminal) at 6

    VL_LV (20 kV, BUS_BREAKER):
      bus BUS_LV  —  GEN_LV, AUX_LOAD, TX (LV terminal)
    """
    net = pn.create_empty("test_nb")
    net.create_substations(id=["S_MAIN"])

    net.create_voltage_levels(
        id=["VL_HV"], substation_id=["S_MAIN"],
        topology_kind=["NODE_BREAKER"], nominal_v=[400.0],
    )
    net.create_busbar_sections(id=["BBS_HV"], voltage_level_id=["VL_HV"], node=[0])

    net.create_switches(id=["DISC_EXT"],  voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[1], open=[False])
    net.create_switches(id=["BRK_EXT"],   voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[1], node2=[2], open=[False])
    net.create_generators(
        id=["EXT_GRID"], voltage_level_id=["VL_HV"], node=[2],
        min_p=[-5000.0], max_p=[5000.0], target_p=[0.0],
        target_v=[400.0], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_minmax_reactive_limits(id=["EXT_GRID"], min_q=[-5000.0], max_q=[5000.0])

    net.create_switches(id=["DISC_LOAD"], voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[3], open=[False])
    net.create_switches(id=["BRK_LOAD"],  voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[3], node2=[4], open=[False])
    net.create_loads(id=["HV_LOAD"], voltage_level_id=["VL_HV"], node=[4],
                     p0=[200.0], q0=[40.0])

    net.create_switches(id=["DISC_TX"],   voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[5], open=[False])
    net.create_switches(id=["BRK_TX"],    voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[5], node2=[6], open=[False])

    net.create_voltage_levels(
        id=["VL_LV"], substation_id=["S_MAIN"],
        topology_kind=["BUS_BREAKER"], nominal_v=[20.0],
    )
    net.create_buses(id=["BUS_LV"], voltage_level_id=["VL_LV"])

    net.create_generators(
        id=["GEN_LV"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
        min_p=[0.0], max_p=[500.0], target_p=[400.0],
        target_v=[20.5], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_curve_reactive_limits(
        id=["GEN_LV"] * 4, p=[0.0, 100.0, 300.0, 500.0],
        min_q=[-150.0, -200.0, -180.0, -100.0],
        max_q=[ 150.0,  250.0,  220.0,  120.0],
    )

    net.create_loads(id=["AUX_LOAD"], voltage_level_id=["VL_LV"], bus_id=["BUS_LV"],
                     p0=[15.0], q0=[5.0])

    sn = 600.0
    zb_lv = 20.0 ** 2 / sn
    yb_lv = 1.0 / zb_lv
    net.create_2_windings_transformers(
        id=["TX"],
        voltage_level1_id=["VL_HV"], node1=[6],
        voltage_level2_id=["VL_LV"], bus2_id=["BUS_LV"],
        rated_u1=[400.0], rated_u2=[20.0], rated_s=[sn],
        r=[0.004 * zb_lv], x=[0.12  * zb_lv],
        g=[0.0006 * yb_lv], b=[-0.018 * yb_lv],
    )

    return net


def build_node_breaker_network_full() -> pn.Network:
    """
    Same as build_node_breaker_network() but with VL_LV also in NODE_BREAKER.

    VL_LV (20 kV, NODE_BREAKER):
      node 0  BusbarSection BBS_LV
      DISC_GEN_LV  0-1  |  BRK_GEN_LV  1-2  |  GEN_LV   at 2
      DISC_AUX_LV  0-3  |  BRK_AUX_LV  3-4  |  AUX_LOAD at 4
      DISC_TX_LV   0-5  |  BRK_TX_LV   5-6  |  TX (LV terminal) at 6
    """
    net = pn.create_empty("test_nb_full")
    net.create_substations(id=["S_MAIN"])

    # HV — identical to build_node_breaker_network()
    net.create_voltage_levels(
        id=["VL_HV"], substation_id=["S_MAIN"],
        topology_kind=["NODE_BREAKER"], nominal_v=[400.0],
    )
    net.create_busbar_sections(id=["BBS_HV"], voltage_level_id=["VL_HV"], node=[0])

    net.create_switches(id=["DISC_EXT"],  voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[1], open=[False])
    net.create_switches(id=["BRK_EXT"],   voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[1], node2=[2], open=[False])
    net.create_generators(
        id=["EXT_GRID"], voltage_level_id=["VL_HV"], node=[2],
        min_p=[-5000.0], max_p=[5000.0], target_p=[0.0],
        target_v=[400.0], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_minmax_reactive_limits(id=["EXT_GRID"], min_q=[-5000.0], max_q=[5000.0])

    net.create_switches(id=["DISC_LOAD"], voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[3], open=[False])
    net.create_switches(id=["BRK_LOAD"],  voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[3], node2=[4], open=[False])
    net.create_loads(id=["HV_LOAD"], voltage_level_id=["VL_HV"], node=[4],
                     p0=[200.0], q0=[40.0])

    net.create_switches(id=["DISC_TX"],   voltage_level_id=["VL_HV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[5], open=[False])
    net.create_switches(id=["BRK_TX"],    voltage_level_id=["VL_HV"],
                        kind=["BREAKER"],      node1=[5], node2=[6], open=[False])

    # LV — node breaker
    net.create_voltage_levels(
        id=["VL_LV"], substation_id=["S_MAIN"],
        topology_kind=["NODE_BREAKER"], nominal_v=[20.0],
    )
    net.create_busbar_sections(id=["BBS_LV"], voltage_level_id=["VL_LV"], node=[0])

    net.create_switches(id=["DISC_GEN_LV"], voltage_level_id=["VL_LV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[1], open=[False])
    net.create_switches(id=["BRK_GEN_LV"],  voltage_level_id=["VL_LV"],
                        kind=["BREAKER"],      node1=[1], node2=[2], open=[False])
    net.create_generators(
        id=["GEN_LV"], voltage_level_id=["VL_LV"], node=[2],
        min_p=[0.0], max_p=[500.0], target_p=[400.0],
        target_v=[20.5], target_q=[0.0], voltage_regulator_on=[True],
    )
    net.create_curve_reactive_limits(
        id=["GEN_LV"] * 4, p=[0.0, 100.0, 300.0, 500.0],
        min_q=[-150.0, -200.0, -180.0, -100.0],
        max_q=[ 150.0,  250.0,  220.0,  120.0],
    )

    net.create_switches(id=["DISC_AUX_LV"], voltage_level_id=["VL_LV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[3], open=[False])
    net.create_switches(id=["BRK_AUX_LV"],  voltage_level_id=["VL_LV"],
                        kind=["BREAKER"],      node1=[3], node2=[4], open=[False])
    net.create_loads(id=["AUX_LOAD"], voltage_level_id=["VL_LV"], node=[4],
                     p0=[15.0], q0=[5.0])

    net.create_switches(id=["DISC_TX_LV"],  voltage_level_id=["VL_LV"],
                        kind=["DISCONNECTOR"], node1=[0], node2=[5], open=[False])
    net.create_switches(id=["BRK_TX_LV"],   voltage_level_id=["VL_LV"],
                        kind=["BREAKER"],      node1=[5], node2=[6], open=[False])

    sn = 600.0
    zb_lv = 20.0 ** 2 / sn
    yb_lv = 1.0 / zb_lv
    net.create_2_windings_transformers(
        id=["TX"],
        voltage_level1_id=["VL_HV"], node1=[6],
        voltage_level2_id=["VL_LV"], node2=[6],
        rated_u1=[400.0], rated_u2=[20.0], rated_s=[sn],
        r=[0.004 * zb_lv], x=[0.12  * zb_lv],
        g=[0.0006 * yb_lv], b=[-0.018 * yb_lv],
    )

    return net
