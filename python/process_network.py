"""
Discover every LV generator connected through a 2-winding transformer whose
HV side matches a target nominal voltage, transport all reactive capability
curves to the HV bus, save the resulting equivalent network, and write SVG
files for each generator.

Three SVG files are produced per generator:
  <out_dir>/<gen_id>_original.svg      – original curve on the LV side
  <out_dir>/<gen_id>_transported.svg   – transported curve on the HV side
  <out_dir>/<gen_id>_comparison.svg    – both envelopes superposed

Generator–auxiliary-load pairing is automatic:
  • If the number of loads on the LV bus equals the number of generators,
    they are paired in ascending alphabetical order.
  • Otherwise no aux-load association is made (aux_load_id = None for all).

Usage
-----
  python process_network.py \\
      --network   path/to/network.xiidm \\
      --hv-voltage 400 \\
      [--output-network equivalent.xiidm] \\
      [--output-dir     ./out] \\
      [--n-samples      25] \\
      [--loss-allocation INDEPENDENT|COMBINED_PROPORTIONAL]
"""

from __future__ import annotations

import argparse
import pathlib
from dataclasses import dataclass, field

import pypowsybl.network as pn

from plot_reactive_curve import (
    build_svg,
    build_svg_overlay,
    read_generator_limits,
)
from transport_curve import (
    GeneratorSpec,
    LossAllocation,
    build_equivalent_network_multi,
)

# IIDM schema version used when saving the equivalent network.
_IIDM_VERSION = "1.15"

# Default color palette for the two-curve comparison overlay.
_PALETTE = [
    {"fill_color": "#fddcb5", "stroke_color": "#c0622a"},  # original  (orange)
    {"fill_color": "#b8d4f0", "stroke_color": "#1a5fa8"},  # transported (blue)
]


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------

@dataclass
class TransformerGroup:
    """One 2-winding transformer together with the specs for its LV generators."""
    transformer_id: str
    hv_vl_id: str
    lv_vl_id: str
    specs: list[GeneratorSpec] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------

def discover_lv_generators(
    network: pn.Network,
    hv_nominal_v: float,
    tolerance: float = 1.0,
) -> list[TransformerGroup]:
    """Return one TransformerGroup per transformer whose HV side nominal
    voltage is within *tolerance* kV of *hv_nominal_v* and that has at
    least one generator on its LV bus.

    Loads on the LV bus are paired with generators (sorted alphabetically)
    when their counts match; otherwise ``aux_load_id`` is set to ``None``
    for every generator.
    """
    txs     = network.get_2_windings_transformers(all_attributes=True)
    vl_df   = network.get_voltage_levels()
    all_gens  = network.get_generators()
    all_loads = network.get_loads()

    groups: list[TransformerGroup] = []
    seen_lv: set[str] = set()

    for tx_id, tx in txs.iterrows():
        nom1 = float(vl_df.loc[tx["voltage_level1_id"], "nominal_v"])
        nom2 = float(vl_df.loc[tx["voltage_level2_id"], "nominal_v"])

        if abs(nom1 - hv_nominal_v) <= tolerance:
            hv_vl_id, lv_vl_id = tx["voltage_level1_id"], tx["voltage_level2_id"]
        elif abs(nom2 - hv_nominal_v) <= tolerance:
            hv_vl_id, lv_vl_id = tx["voltage_level2_id"], tx["voltage_level1_id"]
        else:
            continue

        if lv_vl_id in seen_lv:
            continue
        seen_lv.add(lv_vl_id)

        lv_gens  = all_gens [all_gens ["voltage_level_id"] == lv_vl_id]
        lv_loads = all_loads[all_loads["voltage_level_id"] == lv_vl_id]

        if lv_gens.empty:
            continue

        gen_ids  = sorted(lv_gens.index.tolist())
        load_ids = sorted(lv_loads.index.tolist())

        if len(load_ids) == len(gen_ids):
            pairs: list[tuple[str, str | None]] = list(zip(gen_ids, load_ids))
        else:
            if load_ids:
                print(
                    f"[warn] {tx_id}: {len(gen_ids)} generator(s) but "
                    f"{len(load_ids)} load(s) on LV bus '{lv_vl_id}'. "
                    "No aux-load association will be made."
                )
            pairs = [(g, None) for g in gen_ids]

        specs = [
            GeneratorSpec(
                generator_id=gen_id,
                aux_load_id=load_id,
                new_generator_id=f"HV_{gen_id}",
            )
            for gen_id, load_id in pairs
        ]
        groups.append(TransformerGroup(tx_id, hv_vl_id, lv_vl_id, specs))

    return groups


# ---------------------------------------------------------------------------
# SVG helpers
# ---------------------------------------------------------------------------

def _write_original_svg(
    original_network: pn.Network,
    gen_id: str,
    out_dir: pathlib.Path,
) -> pathlib.Path:
    """Write the original (LV-side) curve SVG using the network's own data."""
    p_pts, qmin_pts, qmax_pts, curve_type = read_generator_limits(
        original_network, gen_id
    )
    tree = build_svg(
        gen_id, p_pts, qmin_pts, qmax_pts,
        f"{curve_type} — LV side",
    )
    out = out_dir / f"{gen_id}_original.svg"
    tree.write(str(out), encoding="unicode", xml_declaration=False)
    return out


def _write_transported_svg(
    gen_id: str,
    p_hv: list[float],
    qmin_hv: list[float],
    qmax_hv: list[float],
    out_dir: pathlib.Path,
) -> pathlib.Path:
    """Write the transported (HV-side) curve SVG."""
    hv_id = f"HV_{gen_id}"
    tree = build_svg(
        hv_id, p_hv, qmin_hv, qmax_hv,
        "Transported Reactive Capability Curve — HV side",
    )
    out = out_dir / f"{gen_id}_transported.svg"
    tree.write(str(out), encoding="unicode", xml_declaration=False)
    return out


def _write_comparison_svg(
    gen_id: str,
    p_orig: list[float],
    qmin_orig: list[float],
    qmax_orig: list[float],
    p_hv: list[float],
    qmin_hv: list[float],
    qmax_hv: list[float],
    out_dir: pathlib.Path,
) -> pathlib.Path:
    """Write an overlay SVG with both the original and transported envelopes."""
    tree = build_svg_overlay(
        [
            {
                "label": f"{gen_id}  (LV — original)",
                "p_pts": p_orig, "qmin_pts": qmin_orig, "qmax_pts": qmax_orig,
                **_PALETTE[0],
            },
            {
                "label": f"HV_{gen_id}  (HV — transported)",
                "p_pts": p_hv,   "qmin_pts": qmin_hv,   "qmax_pts": qmax_hv,
                **_PALETTE[1],
            },
        ],
        title=f"Reactive Capability Comparison — {gen_id}",
    )
    out = out_dir / f"{gen_id}_comparison.svg"
    tree.write(str(out), encoding="unicode", xml_declaration=False)
    return out


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Transport LV generator reactive curves through transformers at a "
            "given HV voltage level and write SVG comparisons."
        )
    )
    parser.add_argument(
        "--network", required=True, metavar="PATH",
        help="Input network file (XIIDM or any pypowsybl-supported format).",
    )
    parser.add_argument(
        "--hv-voltage", required=True, type=float, metavar="KV",
        help="Target HV nominal voltage in kV (e.g. 400).",
    )
    parser.add_argument(
        "--output-network", metavar="PATH",
        help=(
            "Output network file with transported generators. "
            "Defaults to <input>_equivalent.xiidm in the same directory."
        ),
    )
    parser.add_argument(
        "--output-dir", metavar="DIR", default=".",
        help="Directory for SVG output files (default: current directory).",
    )
    parser.add_argument(
        "--n-samples", type=int, default=25, metavar="N",
        help="Number of P samples for curve discretisation (default: 25).",
    )
    parser.add_argument(
        "--loss-allocation",
        choices=["INDEPENDENT", "COMBINED_PROPORTIONAL"],
        default="INDEPENDENT",
        help="Transformer loss allocation policy (default: INDEPENDENT).",
    )
    args = parser.parse_args()

    net_path = pathlib.Path(args.network)
    out_dir  = pathlib.Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    loss_alloc = LossAllocation(args.loss_allocation)

    # ---- Load network
    original_network = pn.load(str(net_path))
    print(f"Loaded  : {net_path}")

    # ---- Discover LV generator groups
    groups = discover_lv_generators(original_network, args.hv_voltage)
    if not groups:
        print(
            f"No generators found behind transformers at "
            f"{args.hv_voltage} kV nominal voltage."
        )
        return

    total = sum(len(g.specs) for g in groups)
    print(
        f"Found   : {total} generator(s) in {len(groups)} "
        f"transformer group(s) at {args.hv_voltage} kV"
    )
    for grp in groups:
        print(
            f"  TX={grp.transformer_id}  LV={grp.lv_vl_id}  "
            + "  ".join(
                f"{s.generator_id}"
                + (f" (aux={s.aux_load_id})" if s.aux_load_id else "")
                for s in grp.specs
            )
        )

    # ---- Transport each group and accumulate curves
    # Keep the original network untouched; chain equivalent networks for
    # successive transformer groups.
    current_net = original_network
    # Maps original generator ID → (p_orig, qmin_orig, qmax_orig, p_hv, qmin_hv, qmax_hv)
    curves_by_gen: dict[str, tuple] = {}

    for grp in groups:
        eq_net, curves_list = build_equivalent_network_multi(
            current_net,
            grp.specs,
            grp.transformer_id,
            n_samples=args.n_samples,
            loss_allocation=loss_alloc,
        )
        current_net = eq_net

        for spec, df in zip(grp.specs, curves_list):
            curves_by_gen[spec.generator_id] = (
                df["p_orig"].tolist(),
                df["q_min_orig"].tolist(),
                df["q_max_orig"].tolist(),
                df["p"].tolist(),
                df["min_q"].tolist(),
                df["max_q"].tolist(),
            )

    # ---- Save equivalent network
    if args.output_network:
        eq_path = pathlib.Path(args.output_network)
    else:
        eq_path = net_path.with_name(net_path.stem + "_equivalent.xiidm")

    current_net.save(
        str(eq_path),
        format="XIIDM",
        parameters={"iidm.export.xml.version": _IIDM_VERSION},
    )
    print(f"\nNetwork : {eq_path}")

    # ---- Write SVGs
    print(f"SVGs    : {out_dir}/")
    for gen_id, (p_orig, qmin_orig, qmax_orig, p_hv, qmin_hv, qmax_hv) in curves_by_gen.items():

        svg_orig = _write_original_svg(original_network, gen_id, out_dir)
        svg_hv   = _write_transported_svg(gen_id, p_hv, qmin_hv, qmax_hv, out_dir)
        svg_cmp  = _write_comparison_svg(
            gen_id,
            p_orig, qmin_orig, qmax_orig,
            p_hv,   qmin_hv,   qmax_hv,
            out_dir,
        )
        print(f"  {svg_orig.name}")
        print(f"  {svg_hv.name}")
        print(f"  {svg_cmp.name}")

    print(f"\nDone    : {len(curves_by_gen)} generator(s) processed.")


if __name__ == "__main__":
    main()
