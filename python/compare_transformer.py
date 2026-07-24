"""
Compare the parameters of a two-winding transformer as stored in an IIDM
network file with the transformer model parameters used in a Dynawo /
Modelica simulation.

The Modelica model follows the BaseGeneratorTransformerParams formulation
(transformer_params.mo):

    toPuUBaseLVSnRef = (UNomLV / UBaseLV)^2 * SnRef / SNom
    ZccPu            = (ZShortCircuit / 100) * toPuUBaseLVSnRef
    RPu              = (copperLosses   / 100) * toPuUBaseLVSnRef
    XPu              = sqrt(ZccPu^2 - RPu^2)
    BPu              = -iMagnetizing  / (100 * toPuUBaseLVSnRef)
    GPu              = provided directly as transformer_GPu parameter

All pu values are on base (UBaseLV, SnRef).

Usage
-----
  python compare_transformer.py \\
      --network   path/to/network.xiidm \\
      --params    path/to/params.xml    \\
      --generator TEST7GR1              \\
      [--hv-voltage 400]                \\
      [--snref 100]
"""

from __future__ import annotations

import argparse
import csv
import math
import pathlib
import sys
import xml.etree.ElementTree as ET

import pypowsybl.network as pn

from transport_curve import get_oriented_transformer_params


# ---------------------------------------------------------------------------
# IIDM helpers
# ---------------------------------------------------------------------------

def _find_transformer_for_generator(
    network: pn.Network,
    generator_id: str,
    hv_v_min: float,
    hv_v_max: float,
) -> str:
    """Return the transformer_id whose LV bus matches the generator's bus.

    Uses bus-level matching rather than voltage-level matching so that networks
    where several transformers share the same LV voltage level are handled
    correctly.
    """
    gens = network.get_generators(all_attributes=True)
    if generator_id not in gens.index:
        raise ValueError(f"Generator '{generator_id}' not found in network.")
    gen_bus = gens.loc[generator_id, "bus_breaker_bus_id"]

    txs = network.get_2_windings_transformers(all_attributes=True)
    vl_df = network.get_voltage_levels()

    for tx_id, tx in txs.iterrows():
        if gen_bus not in (tx["bus_breaker_bus1_id"], tx["bus_breaker_bus2_id"]):
            continue
        nom1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
        nom2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])
        if hv_v_min <= nom1 <= hv_v_max or hv_v_min <= nom2 <= hv_v_max:
            return tx_id

    raise ValueError(
        f"No transformer found for generator '{generator_id}' "
        f"with HV side in [{hv_v_min}, {hv_v_max}] kV."
    )


def _get_iidm_params(network: pn.Network, transformer_id: str) -> dict:
    """Return IIDM transformer R, X, G, B referred to the LV side (in Ω / S)."""
    txs = network.get_2_windings_transformers(all_attributes=True)
    tx = txs.loc[transformer_id]
    vl_df = network.get_voltage_levels()

    nom1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
    nom2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])

    # IIDM always stores R, X, G, B referred to side 2.
    if nom1 >= nom2:
        # side 1 = HV, side 2 = LV → values already LV-referred
        rated_u_hv = float(tx["rated_u1"])
        rated_u_lv = float(tx["rated_u2"])
        r_lv, x_lv = float(tx["r"]), float(tx["x"])
        g_lv, b_lv = float(tx["g"]), float(tx["b"])
    else:
        # side 2 = HV, side 1 = LV → refer values down to LV
        rated_u_hv = float(tx["rated_u2"])
        rated_u_lv = float(tx["rated_u1"])
        k2 = (rated_u_hv / rated_u_lv) ** 2
        r_lv = float(tx["r"]) / k2
        x_lv = float(tx["x"]) / k2
        g_lv = float(tx["g"]) * k2
        b_lv = float(tx["b"]) * k2

    tp = get_oriented_transformer_params(network, transformer_id)

    return dict(
        r_lv=r_lv, x_lv=x_lv, g_lv=g_lv, b_lv=b_lv,
        rated_u_hv=rated_u_hv, rated_u_lv=rated_u_lv,
        rho_tap=tp["rho_tap"],
    )


# ---------------------------------------------------------------------------
# XML helpers
# ---------------------------------------------------------------------------

def _parse_xml_sets(params_path: str, generator_id: str) -> list[dict]:
    """Return all Dynawo parameter sets in *params_path* that match *generator_id*.

    For each set the transformer_* parameters are extracted.  Sets are returned
    with DynaSwing entries first, DynaWaltz entries second.
    """
    tree = ET.parse(params_path)
    root = tree.getroot()

    ns_uri = root.tag.split("}")[0][1:] if root.tag.startswith("{") else ""
    tag_set = f"{{{ns_uri}}}set" if ns_uri else "set"
    tag_par = f"{{{ns_uri}}}par" if ns_uri else "par"

    raw_sets: list[dict] = []
    for elem in root.iter(tag_set):
        sid = elem.get("id", "")
        if generator_id not in sid:
            continue

        pars: dict[str, str] = {
            p.get("name", ""): p.get("value", "")
            for p in elem.iter(tag_par)
        }

        def _f(key: str) -> float | None:
            v = pars.get(key)
            return float(v) if v is not None else None

        raw_sets.append(dict(
            set_id=sid,
            SNom=_f("transformer_SNom"),
            UNomHV=_f("transformer_UNomHV"),
            UNomLV=_f("transformer_UNomLV"),
            UBaseHV=_f("transformer_UBaseHV"),
            UBaseLV=_f("transformer_UBaseLV"),
            ZShortCircuit=_f("transformer_ZShortCircuit"),
            copperLosses=_f("transformer_copperLosses"),
            iMagnetizing=_f("transformer_iMagnetizing"),
            GPu=_f("transformer_GPu"),
        ))

    if not raw_sets:
        raise ValueError(
            f"No parameter sets found for generator '{generator_id}' in {params_path}."
        )

    raw_sets.sort(key=lambda s: (0 if "DynaSwing" in s["set_id"] else 1))
    return raw_sets


# ---------------------------------------------------------------------------
# Conversion helpers
# ---------------------------------------------------------------------------

def _compute_modelica_pu(xml_set: dict, snref: float) -> dict:
    """Apply BaseGeneratorTransformerParams formulas to get RPu, XPu, BPu, GPu."""
    UNomLV = xml_set["UNomLV"]
    UBaseLV = xml_set["UBaseLV"]
    SNom = xml_set["SNom"]
    ZSC = xml_set["ZShortCircuit"]
    copper = xml_set["copperLosses"]
    iMag = xml_set["iMagnetizing"]
    GPu = xml_set["GPu"] if xml_set["GPu"] is not None else 0.0

    toPu = (UNomLV / UBaseLV) ** 2 * snref / SNom
    ZccPu = (ZSC / 100) * toPu
    RPu = (copper / 100) * toPu
    XPu = math.sqrt(max(ZccPu ** 2 - RPu ** 2, 0.0))
    BPu = -iMag / (100 * toPu)

    return dict(R=RPu, X=XPu, B=BPu, G=GPu, toPu=toPu, ZccPu=ZccPu)


def _iidm_to_pu(iidm: dict, u_base_lv: float, snref: float) -> dict:
    z_base = u_base_lv ** 2 / snref
    return dict(
        R=iidm["r_lv"] / z_base,
        X=iidm["x_lv"] / z_base,
        G=iidm["g_lv"] * z_base,
        B=iidm["b_lv"] * z_base,
    )


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def _print_table(
    generator_id: str,
    tx_id: str,
    iidm: dict,
    xml_sets: list[dict],
    snref: float,
) -> None:
    u_base_lv = next(
        (s["UBaseLV"] for s in xml_sets if s["UBaseLV"] is not None),
        iidm["rated_u_lv"],
    )
    z_base = u_base_lv ** 2 / snref
    iidm_pu = _iidm_to_pu(iidm, u_base_lv, snref)
    mo_results = [(_compute_modelica_pu(s, snref), s["set_id"]) for s in xml_sets]

    print(f"\nTransformer : {tx_id}   Generator : {generator_id}")
    print(f"IIDM rated  : {iidm['rated_u_hv']:.1f} kV / {iidm['rated_u_lv']:.1f} kV"
          f"   rho_tap : {iidm['rho_tap']:.4f}")
    print(f"pu base     : UBaseLV={u_base_lv:.1f} kV, SnRef={snref:.1f} MVA"
          f"  →  z_base={z_base:.4f} Ω")

    # Build short column labels (strip generator suffix for readability)
    def _short(sid: str) -> str:
        for prefix in ("DynaSwing_", "DynaWaltz_"):
            if sid.startswith(prefix):
                return sid[:len(prefix) - 1]  # "DynaSwing" / "DynaWaltz"
        return sid

    col_labels = [_short(sid) for _, sid in mo_results]

    # Column widths
    w_si  = 16   # "±0.001692 Ω  "
    w_pu  = 11   # "±0.000294  "
    w_mo  = 11
    w_d   = 11

    def _si(v: float, unit: str) -> str:
        return f"{v:+.6f} {unit}"

    def _pu(v: float) -> str:
        return f"{v:+.9f}"

    rows = [
        ("R", "r_lv", "Ω", "R"),
        ("X", "x_lv", "Ω", "X"),
        ("G", "g_lv", "S", "G"),
        ("B", "b_lv", "S", "B"),
    ]

    # Dynamic separator
    n_extra = len(mo_results)
    total_w = 4 + w_si + 3 + w_pu + n_extra * (3 + w_mo + 3 + w_d) + 2
    sep = "-" * total_w

    # Header
    hdr = f"{'':2s} | {'IIDM (SI, LV)':>{w_si}} | {'IIDM (pu)':>{w_pu}}"
    for lbl in col_labels:
        hdr += f" | {lbl:>{w_mo}} | {'Δ (pu)':>{w_d}}"
    print()
    print(sep)
    print(hdr)
    print(sep)

    for label, si_key, unit, pu_key in rows:
        si_str = _si(iidm[si_key], unit)
        row = f"{label:>2s} | {si_str:>{w_si}} | {_pu(iidm_pu[pu_key]):>{w_pu}}"
        for mo, _ in mo_results:
            delta = mo[pu_key] - iidm_pu[pu_key]
            row += f" | {_pu(mo[pu_key]):>{w_mo}} | {delta:>+{w_d}.6f}"
        print(row)

    print(sep)

    print()
    for mo, sid in mo_results:
        print(f"  {sid}: toPuUBaseLVSnRef={mo['toPu']:.6f}  ZccPu={mo['ZccPu']:.6f}")


# ---------------------------------------------------------------------------
# CSV output
# ---------------------------------------------------------------------------

_PARAMS = [
    ("R", "r_lv", "Ω", "R"),
    ("X", "x_lv", "Ω", "X"),
    ("G", "g_lv", "S", "G"),
    ("B", "b_lv", "S", "B"),
]


def _build_csv_data(
    generator_id: str,
    tx_id: str,
    iidm: dict,
    xml_sets: list[dict],
    snref: float,
    metadata: bool,
) -> dict[str, dict]:
    """Return {parameter_label: row_dict} for this generator."""
    u_base_lv = next(
        (s["UBaseLV"] for s in xml_sets if s["UBaseLV"] is not None),
        iidm["rated_u_lv"],
    )
    iidm_pu = _iidm_to_pu(iidm, u_base_lv, snref)
    mo_results = [(_compute_modelica_pu(s, snref), s["set_id"]) for s in xml_sets]

    def _short(sid: str) -> str:
        for prefix in ("DynaSwing_", "DynaWaltz_"):
            if sid.startswith(prefix):
                return sid[: len(prefix) - 1]
        return sid

    z_base = u_base_lv ** 2 / snref
    result = {}
    for label, si_key, unit, pu_key in _PARAMS:
        si_factor = z_base if unit == "Ω" else 1.0 / z_base
        row: dict = {
            "generator_id": generator_id,
            "transformer_id": tx_id,
            "unit": unit,
            "iidm_si": iidm[si_key],
        }
        for mo, sid in mo_results:
            row[f"{_short(sid)}_si"] = mo[pu_key] * si_factor
        row["iidm_pu"] = iidm_pu[pu_key]
        for mo, sid in mo_results:
            col = _short(sid)
            row[f"{col}_pu"] = mo[pu_key]
            row[f"{col}_delta_pu"] = mo[pu_key] - iidm_pu[pu_key]
        if metadata:
            row["rated_u_hv_kv"] = iidm["rated_u_hv"]
            row["rated_u_lv_kv"] = iidm["rated_u_lv"]
            row["u_base_lv_kv"] = u_base_lv
            row["snref_mva"] = snref
        result[label] = row
    return result


def _write_csv(
    all_data: dict[str, list[dict]],
    base_path: str,
) -> None:
    """Write one CSV file per parameter using base_path as prefix."""
    stem = pathlib.Path(base_path).with_suffix("").as_posix()
    for param, rows in all_data.items():
        if not rows:
            continue
        path = pathlib.Path(f"{stem}_{param}.csv")
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"Written : {path} ({len(rows)} row(s))", file=sys.stderr)


# ---------------------------------------------------------------------------
# Default generator discovery
# ---------------------------------------------------------------------------

def _discover_all(
    network: pn.Network, hv_v_min: float, hv_v_max: float
) -> list[tuple[str, str]]:
    """Return (transformer_id, generator_id) pairs for every transformer whose
    HV nominal voltage falls in [hv_v_min, hv_v_max] and that has at least one
    generator on its LV bus.

    Uses bus-level matching so networks where multiple transformers share the
    same LV voltage level are handled correctly (unlike discover_lv_generators
    in process_network.py which groups by voltage level).
    """
    txs = network.get_2_windings_transformers(all_attributes=True)
    vl_df = network.get_voltage_levels()
    all_gens = network.get_generators(all_attributes=True)

    pairs: list[tuple[str, str]] = []
    for tx_id, tx in txs.iterrows():
        nom1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
        nom2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])

        if hv_v_min <= nom1 <= hv_v_max:
            lv_bus = tx["bus_breaker_bus2_id"]
        elif hv_v_min <= nom2 <= hv_v_max:
            lv_bus = tx["bus_breaker_bus1_id"]
        else:
            continue

        lv_gens = all_gens[all_gens["bus_breaker_bus_id"] == lv_bus]
        for gen_id in sorted(lv_gens.index.tolist()):
            pairs.append((tx_id, gen_id))

    return pairs


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Compare IIDM transformer parameters with Dynawo "
            "BaseGeneratorTransformerParams values."
        )
    )
    parser.add_argument("--network", required=True, metavar="PATH",
                        help="IIDM network file.")
    parser.add_argument("--params", required=True, metavar="PATH",
                        help="Dynawo XML parameter file.")
    parser.add_argument("--generator", metavar="ID", default=None,
                        help="Generator ID (default: first found).")
    parser.add_argument("--hv-voltage", type=float, default=400.0, metavar="KV",
                        help="HV nominal voltage in kV (default 400, ±1 kV window).")
    parser.add_argument("--snref", type=float, default=100.0, metavar="MVA",
                        help="System reference apparent power in MVA (default 100).")
    parser.add_argument("--output", metavar="PATH", default=None,
                        help="Base path for CSV output; writes one file per parameter "
                             "(e.g. --output comparison → comparison_R.csv, comparison_X.csv, …). "
                             "Default: print text table to stdout.")
    parser.add_argument("--dynawaltz", action="store_true", default=False,
                        help="Include DynaWaltz parameter sets (default: DynaSwing only).")
    parser.add_argument("--metadata", action="store_true", default=False,
                        help="Append rated voltages, UBaseLV and SnRef columns to CSV output.")
    args = parser.parse_args()

    hv_v_min = args.hv_voltage - 1.0
    hv_v_max = args.hv_voltage + 1.0

    network = pn.load(args.network)
    print(f"Loaded network : {args.network}", file=sys.stderr)

    if args.generator is not None:
        pairs = [(
            _find_transformer_for_generator(network, args.generator, hv_v_min, hv_v_max),
            args.generator,
        )]
    else:
        pairs = _discover_all(network, hv_v_min, hv_v_max)
        if not pairs:
            print("No transformers with LV generators found.", file=sys.stderr)
            sys.exit(1)
        print(f"Found {len(pairs)} generator(s).", file=sys.stderr)

    all_csv_data: dict[str, list[dict]] = {label: [] for label, *_ in _PARAMS}
    for tx_id, generator_id in pairs:
        print(f"  {generator_id}  →  {tx_id}", file=sys.stderr)
        iidm = _get_iidm_params(network, tx_id)
        xml_sets = _parse_xml_sets(args.params, generator_id)
        if not args.dynawaltz:
            xml_sets = [s for s in xml_sets if "DynaWaltz" not in s["set_id"]]
        if args.output is not None:
            for label, row in _build_csv_data(
                generator_id, tx_id, iidm, xml_sets, args.snref, args.metadata
            ).items():
                all_csv_data[label].append(row)
        else:
            _print_table(generator_id, tx_id, iidm, xml_sets, args.snref)

    if args.output is not None:
        _write_csv(all_csv_data, args.output)


if __name__ == "__main__":
    main()
