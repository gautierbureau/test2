"""
Convert a MATPOWER ``.m`` case (a MATLAB script) to a ``.mat`` file.

The MATPOWER toolbox and its GitHub repository ship many cases (e.g. the RTE
``case6515rte`` French VHV+HV snapshot) only as ``.m`` scripts. pypowsybl's
MATPOWER importer reads the binary ``.mat`` form, not the ``.m`` script, so this
parses the ``mpc`` struct out of the script - the ``version`` / ``baseMVA``
scalars and the ``bus`` / ``gen`` / ``branch`` / ``gencost`` matrices - and
writes an equivalent ``.mat`` that ``build_full_network.py`` (and pypowsybl) can
load with real base voltages:

    python3 matpower_m_to_mat.py case6515rte.m case6515rte.mat
    python3 build_full_network.py -i case6515rte.mat -o data/rte6515_full.xiidm.gz

Only a numeric subset of the case is carried (enough for a power-flow import);
string fields such as ``bus_name`` are ignored. Pure standard library + numpy +
scipy, so no MATLAB/Octave is required.
"""

from __future__ import annotations

import argparse
import re
import sys

import numpy as np
from scipy.io import savemat

# The numeric matrices a MATPOWER power-flow import needs. gencost is optional
# (only present when the case carries cost data) and simply skipped if absent.
_MATRICES = ("bus", "gen", "branch", "gencost")


def _scalar(src: str, field: str, default=None, is_str: bool = False):
    m = re.search(rf"mpc\.{field}\s*=\s*(.+?);", src)
    if not m:
        return default
    value = m.group(1).strip()
    return value.strip("'\"") if is_str else float(value)


def _matrix(src: str, field: str):
    """Parse ``mpc.<field> = [ ... ];`` into a 2-D float array (or None)."""
    m = re.search(rf"mpc\.{field}\s*=\s*\[(.*?)\];", src, re.DOTALL)
    if not m:
        return None
    rows = []
    for line in m.group(1).split("\n"):
        line = line.split("%")[0].strip().rstrip(";").strip()  # drop comments/terminators
        if not line:
            continue
        vals = [float(p) for p in re.split(r"[\s,]+", line) if p != ""]
        if vals:
            rows.append(vals)
    if not rows:
        return None
    # Pad short rows so ragged data (rare, from trailing optional columns) still
    # forms a rectangular array.
    width = max(len(r) for r in rows)
    for r in rows:
        r.extend([0.0] * (width - len(r)))
    return np.array(rows, dtype=float)


def convert(m_path: str, mat_path: str) -> dict:
    """Convert the MATPOWER ``.m`` case at ``m_path`` to a ``.mat`` at ``mat_path``."""
    src = open(m_path).read()
    mpc = {"version": _scalar(src, "version", "2", is_str=True),
           "baseMVA": _scalar(src, "baseMVA", 100.0)}
    shapes = {}
    for field in _MATRICES:
        arr = _matrix(src, field)
        if arr is not None:
            mpc[field] = arr
            shapes[field] = arr.shape
    if "bus" not in mpc:
        raise ValueError(f"no mpc.bus matrix found in {m_path}; is it a MATPOWER case?")
    savemat(mat_path, {"mpc": mpc}, oned_as="row")
    return shapes


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", help="input MATPOWER .m case")
    parser.add_argument("output", help="output .mat file")
    args = parser.parse_args(argv)
    shapes = convert(args.input, args.output)
    for field, shape in shapes.items():
        print(f"{field:8s}: {shape[0]} x {shape[1]}")
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
