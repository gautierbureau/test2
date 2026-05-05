"""
List 2-winding transformers in a given HV nominal voltage range that have at
least one generator on their LV bus, and write their parameters R, X, G, B
in SI units referred to the HV side as a CSV table.

Columns
-------
transformer_id, hv_vl_id, lv_vl_id,
rated_u_hv_kv, rated_u_lv_kv, rated_s_mva,
generators  (semicolon-separated),
r_ohm, x_ohm   – series resistance/reactance (Ω, HV-referred)
g_us, b_us      – shunt conductance/susceptance (μS, HV-referred)
z_ohm           – series impedance magnitude √(R²+X²) (Ω)
z_percent       – impedance in % on transformer rating base (only when rated_s > 0)
rho_tap         – current ratio tap-changer step (1.0 if no RTC)

Usage
-----
  python list_transformers.py \\
      --network   path/to/network.xiidm \\
      --hv-voltage 400 \\
      [--output   transformers.csv]
"""

from __future__ import annotations

import argparse
import csv
import math
import pathlib
import sys

import pypowsybl.network as pn

from transport_curve import get_oriented_transformer_params


_FIELDS = [
    "transformer_id",
    "hv_vl_id",
    "lv_vl_id",
    "rated_u_hv_kv",
    "rated_u_lv_kv",
    "rated_s_mva",
    "generators",
    "r_ohm",
    "x_ohm",
    "g_us",
    "b_us",
    "z_ohm",
    "z_percent",
    "rho_tap",
]


def _discover(
    network: pn.Network,
    hv_v_min: float,
    hv_v_max: float,
) -> list[tuple[str, list[str]]]:
    """Return (tx_id, sorted_gen_ids) for each matching transformer."""
    txs = network.get_2_windings_transformers(all_attributes=True)
    vl_df = network.get_voltage_levels()
    all_gens = network.get_generators()

    results: list[tuple[str, list[str]]] = []
    seen_lv: set[str] = set()

    for tx_id, tx in txs.iterrows():
        nom1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
        nom2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])

        if hv_v_min <= nom1 <= hv_v_max:
            lv_vl_id = tx["voltage_level2_id"]
        elif hv_v_min <= nom2 <= hv_v_max:
            lv_vl_id = tx["voltage_level1_id"]
        else:
            continue

        if lv_vl_id in seen_lv:
            continue
        seen_lv.add(lv_vl_id)

        lv_gens = all_gens[all_gens["voltage_level_id"] == lv_vl_id]
        if lv_gens.empty:
            continue

        results.append((tx_id, sorted(lv_gens.index.tolist())))

    return results


def _fmt(v: float) -> str:
    return f"{v:.6g}"


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "List 2-winding transformer parameters (R, X, G, B in SI units, "
            "HV-referred) for transformers with LV-side generators."
        )
    )
    parser.add_argument(
        "--network", required=True, metavar="PATH",
        help="Input network file (XIIDM or any pypowsybl-supported format).",
    )
    parser.add_argument(
        "--hv-voltage", required=True, type=float, nargs="+", metavar="KV",
        help=(
            "HV nominal voltage in kV. "
            "One value: exact match ±1 kV (e.g. --hv-voltage 400). "
            "Two values: explicit range (e.g. --hv-voltage 380 420)."
        ),
    )
    parser.add_argument(
        "--output", metavar="PATH",
        help="CSV output file (default: stdout).",
    )
    args = parser.parse_args()

    raw_v = args.hv_voltage
    if len(raw_v) == 1:
        hv_v_min, hv_v_max = raw_v[0] - 1.0, raw_v[0] + 1.0
        hv_label = f"{raw_v[0]} kV"
    elif len(raw_v) == 2:
        hv_v_min, hv_v_max = sorted(raw_v)
        hv_label = f"{hv_v_min}–{hv_v_max} kV"
    else:
        parser.error("--hv-voltage accepts 1 value (exact) or 2 values (range)")

    network = pn.load(args.network)
    print(f"Loaded  : {args.network}", file=sys.stderr)

    pairs = _discover(network, hv_v_min, hv_v_max)
    if not pairs:
        print(f"No transformers with LV generators found at {hv_label}.", file=sys.stderr)
        return

    txs_df = network.get_2_windings_transformers(all_attributes=True)

    csv_rows = []
    for tx_id, gen_ids in pairs:
        tp = get_oriented_transformer_params(network, tx_id)
        r, x, g, b = tp["r"], tp["x"], tp["g"], tp["b"]
        z_ohm = math.sqrt(r**2 + x**2)

        # rated_s is optional in IIDM
        try:
            rated_s = float(txs_df.loc[tx_id, "rated_s"] or 0)
        except (KeyError, TypeError, ValueError):
            rated_s = 0.0

        if rated_s > 0:
            z_base = tp["rated_hv"] ** 2 / rated_s
            z_pct = _fmt(z_ohm / z_base * 100.0)
            rated_s_str = _fmt(rated_s)
        else:
            z_pct = ""
            rated_s_str = ""

        csv_rows.append({
            "transformer_id": tx_id,
            "hv_vl_id":       tp["hv_vl_id"],
            "lv_vl_id":       tp["lv_vl_id"],
            "rated_u_hv_kv":  _fmt(tp["rated_hv"]),
            "rated_u_lv_kv":  _fmt(tp["rated_lv"]),
            "rated_s_mva":    rated_s_str,
            "generators":     ";".join(gen_ids),
            "r_ohm":          _fmt(r),
            "x_ohm":          _fmt(x),
            "g_us":           _fmt(g * 1e6),
            "b_us":           _fmt(b * 1e6),
            "z_ohm":          _fmt(z_ohm),
            "z_percent":      z_pct,
            "rho_tap":        _fmt(tp["rho_tap"]),
        })

    if args.output:
        out_path = pathlib.Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        f = open(out_path, "w", newline="", encoding="utf-8")
        close_f = True
        out_label = str(out_path)
    else:
        f = sys.stdout
        close_f = False
        out_label = "stdout"

    writer = csv.DictWriter(f, fieldnames=_FIELDS)
    writer.writeheader()
    writer.writerows(csv_rows)

    if close_f:
        f.close()

    print(
        f"Written : {out_label} ({len(csv_rows)} transformer(s))",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
