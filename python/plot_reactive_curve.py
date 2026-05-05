"""
Render the reactive capability envelope of a generator as a closed SVG file.

Two operating modes are handled automatically:
  • Reactive capability curve  – when the generator has explicit
    (P, Qmin, Qmax) curve points (``create_curve_reactive_limits``).
  • Min-max limits             – when the generator has only scalar
    min_q / max_q bounds (``create_minmax_reactive_limits``); the
    envelope is a rectangle in P-Q space.

The feasible region is drawn as a closed, filled polygon: the Qmax
boundary is traced left-to-right, then the Qmin boundary right-to-left,
and the path is closed.  Axes, grid lines, tick labels and a title are
included in the output.

Usage
-----
  python plot_reactive_curve.py \\
      --network  path/to/network.xiidm \\
      --generator GEN_LV \\
      [--output   GEN_LV_curve.svg]
"""

from __future__ import annotations

import argparse
import pathlib
import xml.etree.ElementTree as ET

import numpy as np
import pypowsybl.network as pn

# ---------------------------------------------------------------------------
# SVG layout
# ---------------------------------------------------------------------------
_W = 700
_H = 500
_ML = 80     # left margin  (Y-axis labels)
_MR = 30     # right margin
_MT = 65     # top margin   (title + subtitle)
_MB = 65     # bottom margin (X-axis label + ticks)
_PW = _W - _ML - _MR   # plot width
_PH = _H - _MT - _MB   # plot height

_C_FILL   = "#b8d4f0"
_C_STROKE = "#1a5fa8"
_C_AXIS   = "#333333"
_C_GRID   = "#e0e0e0"
_C_ZERO   = "#aaaaaa"
_C_TEXT   = "#333333"
_C_BG     = "#ffffff"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _nice_ticks(vmin: float, vmax: float, n: int = 6) -> np.ndarray:
    """Return ~n nicely rounded tick values spanning [vmin, vmax]."""
    if vmax <= vmin:
        return np.array([vmin])
    raw_step = (vmax - vmin) / max(n - 1, 1)
    mag = 10.0 ** np.floor(np.log10(abs(raw_step)))
    nice = min([1, 2, 5, 10], key=lambda s: abs(s * mag - raw_step)) * mag
    t0 = np.ceil(vmin / nice) * nice
    t1 = np.floor(vmax / nice) * nice
    return np.arange(t0, t1 + nice * 0.5, nice)


def _px(p: float, q: float,
        p_range: tuple[float, float],
        q_range: tuple[float, float]) -> tuple[float, float]:
    """Map data (p, q) to SVG pixel coordinates."""
    x = _ML + (p - p_range[0]) / (p_range[1] - p_range[0]) * _PW
    y = _MT + (1.0 - (q - q_range[0]) / (q_range[1] - q_range[0])) * _PH
    return x, y


def _fmt(v: float) -> str:
    return f"{v:.0f}" if v == int(v) else f"{v:.2g}"


# ---------------------------------------------------------------------------
# Read generator limits from network
# ---------------------------------------------------------------------------

def read_generator_limits(
    network: pn.Network, generator_id: str
) -> tuple[list[float], list[float], list[float], str]:
    """Return (p_pts, qmin_pts, qmax_pts, curve_type) for *generator_id*.

    *curve_type* is either ``"Reactive Capability Curve"`` or
    ``"Min-Max Reactive Limits"``.
    """
    gens = network.get_generators()
    if generator_id not in gens.index:
        raise ValueError(f"Generator '{generator_id}' not found in network. "
                         f"Available: {list(gens.index)}")
    gen = gens.loc[generator_id]
    p_min = float(gen["min_p"])
    p_max = float(gen["max_p"])

    curve_pts = network.get_reactive_capability_curve_points()
    has_curve = (
        len(curve_pts) > 0
        and generator_id in curve_pts.index.get_level_values(0)
    )

    if has_curve:
        gc = (
            curve_pts.loc[generator_id]
            .sort_values("p")
            .reset_index(drop=True)
        )
        p_pts   = gc["p"].tolist()
        qmin_pts = gc["min_q"].tolist()
        qmax_pts = gc["max_q"].tolist()
        curve_type = "Reactive Capability Curve"
    else:
        min_q = float(gen["min_q"])
        max_q = float(gen["max_q"])
        p_pts    = [p_min, p_max]
        qmin_pts = [min_q, min_q]
        qmax_pts = [max_q, max_q]
        curve_type = "Min-Max Reactive Limits"

    return p_pts, qmin_pts, qmax_pts, curve_type


# ---------------------------------------------------------------------------
# SVG generation
# ---------------------------------------------------------------------------

def build_svg(
    generator_id: str,
    p_pts: list[float],
    qmin_pts: list[float],
    qmax_pts: list[float],
    curve_type: str,
) -> ET.ElementTree:
    """Build and return an SVG ElementTree for the reactive capability envelope."""

    # ---- data ranges with padding
    p_lo, p_hi = min(p_pts), max(p_pts)
    q_lo, q_hi = min(qmin_pts), max(qmax_pts)
    p_pad = max((p_hi - p_lo) * 0.05, 1.0)
    q_pad = max((q_hi - q_lo) * 0.08, 1.0)
    p_range = (p_lo - p_pad, p_hi + p_pad)
    q_range = (q_lo - q_pad, q_hi + q_pad)

    def to_px(p, q):
        return _px(p, q, p_range, q_range)

    root = ET.Element("svg", {
        "xmlns": "http://www.w3.org/2000/svg",
        "width": str(_W),
        "height": str(_H),
        "viewBox": f"0 0 {_W} {_H}",
    })

    # background
    ET.SubElement(root, "rect", {
        "x": "0", "y": "0", "width": str(_W), "height": str(_H),
        "fill": _C_BG,
    })

    # ---- grid lines
    p_ticks = _nice_ticks(p_range[0], p_range[1])
    q_ticks = _nice_ticks(q_range[0], q_range[1])

    for pt in p_ticks:
        x, _ = to_px(pt, q_range[0])
        ET.SubElement(root, "line", {
            "x1": f"{x:.1f}", "y1": str(_MT),
            "x2": f"{x:.1f}", "y2": str(_MT + _PH),
            "stroke": _C_GRID, "stroke-width": "1",
        })
    for qt in q_ticks:
        _, y = to_px(p_range[0], qt)
        ET.SubElement(root, "line", {
            "x1": str(_ML), "y1": f"{y:.1f}",
            "x2": str(_ML + _PW), "y2": f"{y:.1f}",
            "stroke": _C_GRID, "stroke-width": "1",
        })

    # zero-value reference lines
    if p_range[0] < 0 < p_range[1]:
        x0, _ = to_px(0.0, q_range[0])
        ET.SubElement(root, "line", {
            "x1": f"{x0:.1f}", "y1": str(_MT),
            "x2": f"{x0:.1f}", "y2": str(_MT + _PH),
            "stroke": _C_ZERO, "stroke-width": "1",
            "stroke-dasharray": "4,3",
        })
    if q_range[0] < 0 < q_range[1]:
        _, y0 = to_px(p_range[0], 0.0)
        ET.SubElement(root, "line", {
            "x1": str(_ML), "y1": f"{y0:.1f}",
            "x2": str(_ML + _PW), "y2": f"{y0:.1f}",
            "stroke": _C_ZERO, "stroke-width": "1",
            "stroke-dasharray": "4,3",
        })

    # ---- closed capability envelope
    # path: Qmax left→right, then Qmin right→left, closed
    pts_max = [to_px(p, q) for p, q in zip(p_pts, qmax_pts)]
    pts_min = [to_px(p, q) for p, q in zip(reversed(p_pts), reversed(qmin_pts))]
    all_pts = pts_max + pts_min
    d = (f"M {all_pts[0][0]:.2f},{all_pts[0][1]:.2f} "
         + " ".join(f"L {x:.2f},{y:.2f}" for x, y in all_pts[1:])
         + " Z")
    ET.SubElement(root, "path", {
        "d": d,
        "fill": _C_FILL, "fill-opacity": "0.55",
        "stroke": _C_STROKE, "stroke-width": "2",
        "stroke-linejoin": "round",
    })

    # ---- plot area border (drawn on top of fill)
    ET.SubElement(root, "rect", {
        "x": str(_ML), "y": str(_MT),
        "width": str(_PW), "height": str(_PH),
        "fill": "none", "stroke": _C_AXIS, "stroke-width": "1",
    })

    # ---- X-axis tick marks and labels
    y_bot = _MT + _PH
    for pt in p_ticks:
        if p_range[0] <= pt <= p_range[1]:
            x, _ = to_px(pt, q_range[0])
            ET.SubElement(root, "line", {
                "x1": f"{x:.1f}", "y1": str(y_bot),
                "x2": f"{x:.1f}", "y2": str(y_bot + 5),
                "stroke": _C_AXIS, "stroke-width": "1.5",
            })
            t = ET.SubElement(root, "text", {
                "x": f"{x:.1f}", "y": str(y_bot + 18),
                "text-anchor": "middle",
                "font-family": "sans-serif", "font-size": "12",
                "fill": _C_TEXT,
            })
            t.text = _fmt(pt)

    # ---- Y-axis tick marks and labels
    for qt in q_ticks:
        if q_range[0] <= qt <= q_range[1]:
            _, y = to_px(p_range[0], qt)
            ET.SubElement(root, "line", {
                "x1": str(_ML - 5), "y1": f"{y:.1f}",
                "x2": str(_ML),     "y2": f"{y:.1f}",
                "stroke": _C_AXIS, "stroke-width": "1.5",
            })
            t = ET.SubElement(root, "text", {
                "x": str(_ML - 8), "y": f"{y + 4:.1f}",
                "text-anchor": "end",
                "font-family": "sans-serif", "font-size": "12",
                "fill": _C_TEXT,
            })
            t.text = _fmt(qt)

    # ---- X-axis label
    t = ET.SubElement(root, "text", {
        "x": str(_ML + _PW // 2),
        "y": str(_H - 12),
        "text-anchor": "middle",
        "font-family": "sans-serif", "font-size": "13",
        "fill": _C_TEXT,
    })
    t.text = "Active power P (MW)"

    # ---- Y-axis label (rotated)
    cy = _MT + _PH // 2
    t = ET.SubElement(root, "text", {
        "transform": f"translate(16,{cy}) rotate(-90)",
        "text-anchor": "middle",
        "font-family": "sans-serif", "font-size": "13",
        "fill": _C_TEXT,
    })
    t.text = "Reactive power Q (MVar)"

    # ---- title
    t = ET.SubElement(root, "text", {
        "x": str(_W // 2), "y": "24",
        "text-anchor": "middle",
        "font-family": "sans-serif", "font-size": "15", "font-weight": "bold",
        "fill": _C_TEXT,
    })
    t.text = f"Reactive Capability — {generator_id}"

    # ---- subtitle (curve type)
    t = ET.SubElement(root, "text", {
        "x": str(_W // 2), "y": "42",
        "text-anchor": "middle",
        "font-family": "sans-serif", "font-size": "11",
        "fill": "#666666",
    })
    t.text = curve_type

    # ---- curve-point markers (small circles on the original data points)
    for p, qmn, qmx in zip(p_pts, qmin_pts, qmax_pts):
        for q in (qmn, qmx):
            cx, cy_pt = to_px(p, q)
            ET.SubElement(root, "circle", {
                "cx": f"{cx:.2f}", "cy": f"{cy_pt:.2f}", "r": "3",
                "fill": _C_STROKE,
            })

    return ET.ElementTree(root)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Plot a generator's reactive capability curve as an SVG file."
    )
    parser.add_argument(
        "--network", required=True, metavar="PATH",
        help="Network file path (XIIDM or any pypowsybl-supported format).",
    )
    parser.add_argument(
        "--generator", required=True, metavar="ID",
        help="Generator ID whose reactive limits are to be plotted.",
    )
    parser.add_argument(
        "--output", metavar="PATH",
        help="Output SVG path (default: <generator_id>_reactive_curve.svg).",
    )
    args = parser.parse_args()

    net = pn.load(args.network)
    p_pts, qmin_pts, qmax_pts, curve_type = read_generator_limits(net, args.generator)

    tree = build_svg(args.generator, p_pts, qmin_pts, qmax_pts, curve_type)

    out = args.output or f"{args.generator}_reactive_curve.svg"
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    tree.write(out, encoding="unicode", xml_declaration=False)
    print(f"Saved → {out}  ({curve_type}, {len(p_pts)} P-point(s))")


if __name__ == "__main__":
    main()
