"""
Transport a generator's reactive capability curve from the LV side of a
transformer to the HV side, analytically (no load flow), and build an
equivalent network with a single generator on the HV bus that produces the
same (P, Q) injection toward the rest of the network as the original
transformer did.

Topology assumed on the LV side (radial):

      [external HV grid] --- 400 kV bus --- [TX 400/20] --- 20 kV bus
                                                              |
                                                       +------+------+
                                                       |             |
                                                  [generator]    [aux load]

The script:
  1. Builds a small test network in pypowsybl.
  2. Reads the original generator's reactive capability curve.
  3. For each P sample, transports (Pmin_lv_inj, Qmin_lv_inj) and
     (Pmin_lv_inj, Qmax_lv_inj) analytically through the transformer
     pi-model to the HV bus, giving (P_hv, Qmin_hv) and (P_hv, Qmax_hv).
  4. Builds the equivalent network: a clone of the original where the
     generator + load + transformer are replaced by a single generator on
     the HV bus carrying the transported curve.
  5. Validates by running an AC load flow on both networks at the matching
     operating point and comparing the HV-side flow.

Requires: pypowsybl >= 1.5
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pypowsybl as pp
import pypowsybl.loadflow as lf
import pypowsybl.network as pn


# ---------------------------------------------------------------------------
#  1.  Build a small test network
# ---------------------------------------------------------------------------
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

    # A small load on HV so the network is non-trivial. Removing it changes
    # nothing for our derivation; it is just there to give the load flow
    # something to balance.
    net.create_loads(
        id=["HV_LOAD"],
        voltage_level_id=["VL_HV"],
        bus_id=["BUS_HV"],
        p0=[200.0],
        q0=[40.0],
    )

    # Generator on the LV side. Curve has a typical "D-shape": Q range
    # narrows as P approaches Pmax, and is restricted at low P too.
    net.create_generators(
        id=["GEN_LV"],
        voltage_level_id=["VL_LV"],
        bus_id=["BUS_LV"],
        min_p=[0.0],
        max_p=[500.0],
        target_p=[400.0],
        target_v=[20.5],            # AVR setpoint at the LV bus
        target_q=[0.0],
        voltage_regulator_on=[True],
    )

    # Reactive capability curve points for the generator.
    p_pts = [0.0, 100.0, 300.0, 500.0]
    qmin = [-150.0, -200.0, -180.0, -100.0]
    qmax = [+150.0, +250.0, +220.0, +120.0]
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
    # IIDM convention: R, X, G, B are referred to side 2.
    # In our setup, voltage_level1 = HV (400 kV), voltage_level2 = LV (20 kV),
    # so we must give R, X, G, B referred to the 20 kV side.
    # Choose realistic values: ~0.4 % R, ~12 % X on the transformer base
    # (assume Sn = 600 MVA), modest magnetising shunt on its own base.
    sn = 600.0
    zb_lv = 20.0 ** 2 / sn           # base impedance referred to LV side
    yb_lv = 1.0 / zb_lv
    r_pu = 0.004
    x_pu = 0.12
    g_pu = 0.0006                    # ~0.06% core conductance
    b_pu = -0.018                    # ~1.8% magnetising susceptance
    r = r_pu * zb_lv                 # ohm
    x = x_pu * zb_lv                 # ohm
    g = g_pu * yb_lv                 # siemens
    b = b_pu * yb_lv                 # siemens

    net.create_2_windings_transformers(
        id=["TX"],
        voltage_level1_id=["VL_HV"],
        bus1_id=["BUS_HV"],
        voltage_level2_id=["VL_LV"],
        bus2_id=["BUS_LV"],
        rated_u1=[400.0],
        rated_u2=[20.0],
        rated_s=[sn],
        b=[b],
        g=[g],
        r=[r],
        x=[x],
    )

    return net


# ---------------------------------------------------------------------------
#  2.  Analytical transport of a single (P, Q) point
# ---------------------------------------------------------------------------
def transport_point(
    p_inj_lv_mw: float,
    q_inj_lv_mvar: float,
    v_lv_kv: float,
    r_ohm: float,
    x_ohm: float,
    g_s: float,
    b_s: float,
    rated_u_lv_kv: float,
    rated_u_hv_kv: float,
    rho_tap: float = 1.0,
    s_base_mva: float = 100.0,
) -> tuple[float, float, complex]:
    """
    Transport an LV-bus injection (P_inj_lv, Q_inj_lv) through a 2-winding
    transformer to the HV bus, returning the HV-side injection (P_hv, Q_hv).

    Computation done in per-unit on the HV side to avoid any sqrt(3) /
    line-to-line vs line-to-neutral confusion. Powsybl uses single-phase
    per-unit equivalents internally; the same approach is used here.

    Convention:
      * P_inj_lv, Q_inj_lv : net injection at the LV bus
        (= P_gen - P_aux_load, Q_gen - Q_aux_load), in MW / MVar.
      * v_lv_kv             : LV bus voltage magnitude (line-to-line, kV).
      * R, X, G, B           : transformer parameters in SI units,
        referred to the HV side. The caller must refer them to HV first
        if powsybl stores them on the LV side.
      * rated_u_lv_kv, rated_u_hv_kv : transformer rated voltages (kV).
      * rho_tap : dimensionless ratio of the current ratio-tap-changer
        step (1.0 if no tap changer).
      * s_base_mva : per-unit base power (any value works; the result
        is independent of this choice).

    Returns:
      (P_hv_mw, Q_hv_mvar, V_hv_complex_kv)
    """
    # ---- Bases on the HV side
    zb_hv = rated_u_hv_kv ** 2 / s_base_mva          # ohm
    yb_hv = 1.0 / zb_hv                              # siemens

    # ---- Per-unit transformer parameters
    z_pu = complex(r_ohm, x_ohm) / zb_hv
    y_pu = complex(g_s, b_s) / yb_hv

    # ---- Complex ratio of the ideal transformer
    # Defined as V_hv_side_of_ideal / V_lv_side_of_ideal (in physical units),
    # which becomes rho_tap in per-unit because both sides per-unitize against
    # their own rated voltage. The off-nominal part of the ratio (when
    # rated_u differs from nominal_v) is captured by v1_pu below.
    rho_pu = rho_tap

    # ---- LV bus voltage in pu (on its own rated voltage as base)
    v1_pu = complex(v_lv_kv / rated_u_lv_kv, 0.0)

    # ---- Current entering the transformer at the LV side, in pu
    s1_pu = complex(p_inj_lv_mw, q_inj_lv_mvar) / s_base_mva
    i1_pu = (s1_pu / v1_pu).conjugate()

    # ---- Refer through the ideal transformer ratio
    v1p_pu = v1_pu * rho_pu
    i1p_pu = i1_pu / rho_pu

    # ---- Voltage drop across series impedance to reach HV bus
    v2_pu = v1p_pu - z_pu * i1p_pu

    # ---- Shunt current at HV bus
    i_shunt_pu = y_pu * v2_pu

    # ---- Current flowing from the transformer into the HV bus
    i2_pu = i1p_pu - i_shunt_pu

    # ---- Complex power injected at the HV bus
    s_hv_pu = v2_pu * i2_pu.conjugate()

    p_hv_mw = s_hv_pu.real * s_base_mva
    q_hv_mvar = s_hv_pu.imag * s_base_mva
    v_hv_kv = v2_pu * rated_u_hv_kv
    return p_hv_mw, q_hv_mvar, v_hv_kv


def get_oriented_transformer_params(network: pn.Network, transformer_id: str):
    """
    Return transformer parameters with R, X, G, B referred to the HV side.

    Returns dict with keys:
      r, x, g, b           (HV-referred SI units)
      rated_lv, rated_hv   (kV)
      hv_vl_id, lv_vl_id   (voltage level IDs)
      hv_bus_id, lv_bus_id (bus-breaker bus IDs)
      nom_hv, nom_lv       (nominal voltages, kV)
      rho_tap              (current ratio-tap-changer step ratio)
    """
    txs = network.get_2_windings_transformers(all_attributes=True)
    tx = txs.loc[transformer_id]
    vl_df = network.get_voltage_levels()
    nom_v1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
    nom_v2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])

    if nom_v1 >= nom_v2:
        hv_vl_id = tx["voltage_level1_id"]
        hv_bus_id = tx["bus_breaker_bus1_id"]
        lv_vl_id = tx["voltage_level2_id"]
        lv_bus_id = tx["bus_breaker_bus2_id"]
        nom_hv, nom_lv = nom_v1, nom_v2
    else:
        hv_vl_id = tx["voltage_level2_id"]
        hv_bus_id = tx["bus_breaker_bus2_id"]
        lv_vl_id = tx["voltage_level1_id"]
        lv_bus_id = tx["bus_breaker_bus1_id"]
        nom_hv, nom_lv = nom_v2, nom_v1

    # IIDM: R, X, G, B are referred to side 2.
    if nom_v2 >= nom_v1:
        # side 2 = HV: parameters already on HV side
        rated_lv = float(tx["rated_u1"])
        rated_hv = float(tx["rated_u2"])
        r = float(tx["r"])
        x = float(tx["x"])
        g = float(tx["g"])
        b = float(tx["b"])
    else:
        # side 2 = LV: refer R,X,G,B up to HV
        rated_lv = float(tx["rated_u2"])
        rated_hv = float(tx["rated_u1"])
        k2 = (rated_hv / rated_lv) ** 2
        r = float(tx["r"]) * k2
        x = float(tx["x"]) * k2
        g = float(tx["g"]) / k2
        b = float(tx["b"]) / k2

    rho_tap = 1.0
    try:
        rtc = network.get_ratio_tap_changers()
        if transformer_id in rtc.index:
            tap_pos = int(rtc.loc[transformer_id, "tap"])
            steps = network.get_ratio_tap_changer_steps()
            row = steps.loc[(transformer_id, tap_pos)]
            rho_tap = float(row["rho"])
    except (KeyError, AttributeError):
        pass

    return dict(
        r=r, x=x, g=g, b=b,
        rated_lv=rated_lv, rated_hv=rated_hv,
        hv_vl_id=hv_vl_id, lv_vl_id=lv_vl_id,
        hv_bus_id=hv_bus_id, lv_bus_id=lv_bus_id,
        nom_hv=nom_hv, nom_lv=nom_lv,
        rho_tap=rho_tap,
    )


# ---------------------------------------------------------------------------
#  3.  Build the equivalent (transported) reactive capability curve
# ---------------------------------------------------------------------------
def transport_capability_curve(
    network: pn.Network,
    generator_id: str,
    transformer_id: str,
    aux_load_id: str | None,
    v_lv_kv: float,
    n_samples: int = 25,
) -> pd.DataFrame:
    """
    Build the equivalent reactive capability curve as seen from the HV side.

    Returns a DataFrame with columns: p, min_q, max_q (all in MW / MVar at
    the HV bus, generator convention: positive P/Q = injected into bus).
    """
    # ---- Read original generator data
    gens = network.get_generators()
    gen = gens.loc[generator_id]
    p_min, p_max = float(gen["min_p"]), float(gen["max_p"])

    # ---- Sample the original curve at n_samples values of P
    curve_pts = network.get_reactive_capability_curve_points()
    if generator_id in curve_pts.index.get_level_values(0):
        gen_curve = (
            curve_pts.loc[generator_id]
            .sort_values("p")
            .reset_index(drop=True)
        )
        p_orig = gen_curve["p"].to_numpy()
        qmin_orig_pts = gen_curve["min_q"].to_numpy()
        qmax_orig_pts = gen_curve["max_q"].to_numpy()
        p_samples = np.linspace(p_min, p_max, n_samples)
        qmin_orig = np.interp(p_samples, p_orig, qmin_orig_pts)
        qmax_orig = np.interp(p_samples, p_orig, qmax_orig_pts)
    else:
        # Rectangular limits
        p_samples = np.linspace(p_min, p_max, n_samples)
        qmin_orig = np.full(n_samples, float(gen["min_q"]))
        qmax_orig = np.full(n_samples, float(gen["max_q"]))

    # ---- Read transformer parameters (oriented to HV)
    tp = get_oriented_transformer_params(network, transformer_id)

    # ---- Auxiliary load (constant PQ assumption)
    p_aux, q_aux = 0.0, 0.0
    if aux_load_id is not None:
        loads = network.get_loads()
        if aux_load_id in loads.index:
            p_aux = float(loads.loc[aux_load_id, "p0"])
            q_aux = float(loads.loc[aux_load_id, "q0"])

    # ---- Sweep
    rows = []
    for p_gen, qmn, qmx in zip(p_samples, qmin_orig, qmax_orig):
        # LV-bus net injection (loads consume positive p/q)
        p_inj_lv = p_gen - p_aux
        q_inj_lv_lo = qmn - q_aux
        q_inj_lv_hi = qmx - q_aux

        p_hv_lo, q_hv_lo, _ = transport_point(
            p_inj_lv, q_inj_lv_lo, v_lv_kv,
            tp["r"], tp["x"], tp["g"], tp["b"],
            tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
        )
        p_hv_hi, q_hv_hi, _ = transport_point(
            p_inj_lv, q_inj_lv_hi, v_lv_kv,
            tp["r"], tp["x"], tp["g"], tp["b"],
            tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
        )

        rows.append({
            "p_orig": p_gen,
            "q_min_orig": qmn,
            "q_max_orig": qmx,
            "p": 0.5 * (p_hv_lo + p_hv_hi),    # very close, average
            "min_q": min(q_hv_lo, q_hv_hi),
            "max_q": max(q_hv_lo, q_hv_hi),
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
#  4.  Build the equivalent network
# ---------------------------------------------------------------------------
def build_equivalent_network(
    original: pn.Network,
    generator_id: str,
    transformer_id: str,
    aux_load_id: str,
    new_gen_id: str = "GEN_HV_EQ",
    n_samples: int = 25,
) -> tuple[pn.Network, pd.DataFrame]:
    """
    Returns a (network, curve_df) pair where the equivalent generator on the
    HV bus carries the transported curve.
    """
    # Read the LV regulating setpoint as the assumed LV voltage
    gen = original.get_generators().loc[generator_id]
    v_lv = float(gen["target_v"])

    curve = transport_capability_curve(
        original, generator_id, transformer_id, aux_load_id,
        v_lv_kv=v_lv, n_samples=n_samples,
    )

    # Oriented transformer parameters (HV-referred)
    tp = get_oriented_transformer_params(original, transformer_id)

    # Clone the network, then strip the LV-side equipment
    eq = pn.load_from_string("equivalent.xiidm", original.save_to_string())
    eq.remove_elements([generator_id, aux_load_id, transformer_id])

    # Active-power range from the transported curve
    p_min_eq = float(curve["p"].min())
    p_max_eq = float(curve["p"].max())

    # Operating point: transport the original generator's target (P, Q)
    p_target_orig = float(gen["target_p"])
    q_target_orig = float(gen["target_q"])
    p_aux = float(original.get_loads().loc[aux_load_id, "p0"])
    q_aux = float(original.get_loads().loc[aux_load_id, "q0"])
    p_op_hv, q_op_hv, _ = transport_point(
        p_target_orig - p_aux, q_target_orig - q_aux, v_lv,
        tp["r"], tp["x"], tp["g"], tp["b"],
        tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
    )

    eq.create_generators(
        id=[new_gen_id],
        voltage_level_id=[tp["hv_vl_id"]],
        bus_id=[tp["hv_bus_id"]],
        min_p=[p_min_eq],
        max_p=[p_max_eq],
        target_p=[p_op_hv],
        target_q=[q_op_hv],
        target_v=[tp["nom_hv"]],
        voltage_regulator_on=[False],   # behave as PQ to match original injection
    )

    # Curve must be sorted by P with strictly-increasing P
    curve_sorted = curve.sort_values("p").drop_duplicates(subset="p", keep="first")
    eq.create_curve_reactive_limits(
        id=[new_gen_id] * len(curve_sorted),
        p=curve_sorted["p"].tolist(),
        min_q=curve_sorted["min_q"].tolist(),
        max_q=curve_sorted["max_q"].tolist(),
    )

    return eq, curve


# ---------------------------------------------------------------------------
#  5.  Validate by comparing flow at HV bus on both networks
# ---------------------------------------------------------------------------
def hv_flow_into_grid(network: pn.Network, transformer_id: str | None,
                      eq_gen_id: str | None) -> tuple[float, float]:
    """
    Return (P, Q) injected at the HV bus by either the transformer (HV side
    flow leaving the transformer toward the grid) on the original network,
    or by the equivalent generator on the equivalent network.
    """
    if transformer_id is not None:
        tx = network.get_2_windings_transformers().loc[transformer_id]
        vl_df = network.get_voltage_levels()
        nom_v1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
        nom_v2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])
        # Flow ENTERING the transformer at the HV side (powsybl receiver convention):
        if nom_v1 >= nom_v2:
            p_in, q_in = float(tx["p1"]), float(tx["q1"])
        else:
            p_in, q_in = float(tx["p2"]), float(tx["q2"])
        # Injection toward the rest of the grid = -flow entering the transformer
        return -p_in, -q_in
    else:
        gen = network.get_generators().loc[eq_gen_id]
        # In powsybl, on a generator p,q are receiver-convention as well:
        # p = -P_injected. So injection into the bus = -p, -q.
        return -float(gen["p"]), -float(gen["q"])


# ---------------------------------------------------------------------------
#  Main
# ---------------------------------------------------------------------------
def main() -> None:
    np.set_printoptions(precision=3, suppress=True)
    pd.set_option("display.precision", 3)
    pd.set_option("display.width", 140)

    # ---- 1. original
    original = build_test_network()

    print("=" * 78)
    print("ORIGINAL NETWORK")
    print("=" * 78)
    print("\nGenerators:")
    print(original.get_generators()[
        ["voltage_level_id", "min_p", "max_p", "target_p", "target_v",
         "voltage_regulator_on"]
    ])
    print("\nLoads:")
    print(original.get_loads()[["voltage_level_id", "p0", "q0"]])
    print("\nTransformer (R, X, G, B referred to side 2):")
    print(original.get_2_windings_transformers()[
        ["voltage_level1_id", "voltage_level2_id",
         "rated_u1", "rated_u2", "r", "x", "g", "b"]
    ])
    print("\nOriginal capability curve points (LV side):")
    print(original.get_reactive_capability_curve_points().loc["GEN_LV"])

    # ---- 2. transport
    print("\n" + "=" * 78)
    print("TRANSPORTED CAPABILITY CURVE (analytical, evaluated at V_lv = 20.5 kV)")
    print("=" * 78)
    eq_net, curve = build_equivalent_network(
        original,
        generator_id="GEN_LV",
        transformer_id="TX",
        aux_load_id="AUX_LOAD",
        new_gen_id="GEN_HV_EQ",
        n_samples=11,
    )
    print(curve.round(3).to_string(index=False))

    # ---- 3. validate via two AC load flows
    print("\n" + "=" * 78)
    print("VALIDATION (AC load flow on both networks)")
    print("=" * 78)

    # In the equivalent network the generator behaves as PQ, with target_p
    # and target_q chosen as the analytical transport of the original
    # operating point. We expect the HV-bus injection to match the HV-side
    # flow of the transformer on the original network.

    params = lf.Parameters(
        voltage_init_mode=lf.VoltageInitMode.UNIFORM_VALUES,
        transformer_voltage_control_on=False,
        use_reactive_limits=True,
        distributed_slack=False,        # let EXT_GRID absorb the imbalance only
    )

    res_orig = lf.run_ac(original, parameters=params)
    print(f"Original load flow status:    {res_orig[0].status_text}")

    # Read the actual operating point of the original generator after LF
    gen_after = original.get_generators().loc["GEN_LV"]
    p_actual = -float(gen_after["p"])      # generator convention: positive = injection
    q_actual = -float(gen_after["q"])
    bus_v = original.get_buses().loc[
        original.get_generators().loc["GEN_LV", "bus_id"], "v_mag"
    ]
    print(f"  GEN_LV actually produced: P = {p_actual:.3f} MW, "
          f"Q = {q_actual:.3f} MVar at V_lv = {bus_v:.3f} kV")

    # Rebuild the equivalent generator's operating point from the ACTUAL
    # post-LF state of the original, so the two LFs are comparable.
    tp = get_oriented_transformer_params(original, "TX")
    p_aux = float(original.get_loads().loc["AUX_LOAD", "p0"])
    q_aux = float(original.get_loads().loc["AUX_LOAD", "q0"])
    p_op_hv, q_op_hv, _ = transport_point(
        p_actual - p_aux, q_actual - q_aux, bus_v,
        tp["r"], tp["x"], tp["g"], tp["b"],
        tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
    )
    eq_net.update_generators(id="GEN_HV_EQ",
                             target_p=p_op_hv, target_q=q_op_hv)

    res_eq = lf.run_ac(eq_net, parameters=params)
    print(f"Equivalent load flow status:  {res_eq[0].status_text}")

    p_orig, q_orig = hv_flow_into_grid(original, "TX", None)
    p_eq, q_eq = hv_flow_into_grid(eq_net, None, "GEN_HV_EQ")

    print("\n  HV-bus injection toward the grid:")
    print(f"    Original   :  P = {p_orig:8.3f} MW   Q = {q_orig:8.3f} MVar")
    print(f"    Equivalent :  P = {p_eq:8.3f} MW   Q = {q_eq:8.3f} MVar")
    print(f"    Difference :  dP = {p_eq - p_orig:8.3f} MW   "
          f"dQ = {q_eq - q_orig:8.3f} MVar")

    # ---- 4. show the operating point
    print("\nOriginal generator GEN_LV after LF:")
    print(original.get_generators().loc["GEN_LV", ["p", "q", "target_p", "target_q"]])
    print("\nEquivalent generator GEN_HV_EQ after LF:")
    print(eq_net.get_generators().loc["GEN_HV_EQ", ["p", "q", "target_p", "target_q"]])

    # ---- 5. verify a non-operating point on the boundary
    print("\n" + "=" * 78)
    print("CURVE BOUNDARY CHECK at P_orig = 300 MW, Q_orig = +220 MVar (Qmax)")
    print("=" * 78)

    target_p_orig = 300.0
    target_q_orig = 220.0
    p_aux = float(original.get_loads().loc["AUX_LOAD", "p0"])
    q_aux = float(original.get_loads().loc["AUX_LOAD", "q0"])

    tp = get_oriented_transformer_params(original, "TX")
    p_hv_a, q_hv_a, v_hv = transport_point(
        target_p_orig - p_aux, target_q_orig - q_aux, 20.5,
        tp["r"], tp["x"], tp["g"], tp["b"],
        tp["rated_lv"], tp["rated_hv"], tp["rho_tap"],
    )

    # Now run a load flow on the original with the generator forced to PQ
    # at this point, and compare.
    original.update_generators(id="GEN_LV", voltage_regulator_on=False,
                               target_p=target_p_orig, target_q=target_q_orig)
    res = lf.run_ac(original, parameters=params)
    p_orig_b, q_orig_b = hv_flow_into_grid(original, "TX", None)
    print(f"  Analytical transport :  P_hv = {p_hv_a:8.3f} MW   "
          f"Q_hv = {q_hv_a:8.3f} MVar  |V_hv| = {abs(v_hv):.3f} kV")
    print(f"  Load-flow truth      :  P_hv = {p_orig_b:8.3f} MW   "
          f"Q_hv = {q_orig_b:8.3f} MVar")
    print(f"  Difference           :  dP   = {p_hv_a - p_orig_b:8.3f} MW   "
          f"dQ   = {q_hv_a - q_orig_b:8.3f} MVar")
    print("\n(Differences come from the load flow not landing exactly at "
          "V_lv = 20.5 kV; if the AVR holds 20.5 kV, agreement is exact.)")


if __name__ == "__main__":
    main()
