"""
Summarise an IIDM network: how many of each object type it holds and how many
of each extension. Handy for comparing a synthetic network against a real one.

Object types are every ``pypowsybl`` ``ElementType``; extensions are discovered
dynamically from the running pypowsybl install, so the report tracks whatever
the library supports without a hard-coded list.
"""

from __future__ import annotations

import argparse
import sys

import pypowsybl.network as pn

_BUILTINS = {
    "ieee14": pn.create_ieee14,
    "ieee118": pn.create_ieee118,
    "ieee300": pn.create_ieee300,
}

# Aggregate super-types / alternate views that are unions of the concrete rows;
# counting them alongside the leaves double-counts, so they're left out.
_AGGREGATE_TYPES = {"IDENTIFIABLE", "BRANCH", "INJECTION", "BUS_FROM_BUS_BREAKER_VIEW"}


def _element_counts(network: pn.Network) -> dict:
    """{element type name -> row count} for every queryable concrete ElementType."""
    counts = {}
    for name in sorted(n for n in dir(pn.ElementType) if n.isupper()):
        if name in _AGGREGATE_TYPES:
            continue
        try:
            counts[name] = len(network.get_elements(getattr(pn.ElementType, name)))
        except Exception:
            # Abstract / context-only types (ALIAS, TERMINAL, ...) aren't listable.
            pass
    return counts


def _extension_counts(network: pn.Network) -> dict:
    """{extension name -> element count} across every extension pypowsybl knows."""
    counts = {}
    for name in pn.get_extensions_names():
        try:
            counts[name] = len(network.get_extensions(name))
        except Exception:
            counts[name] = 0
    return counts


def _print_section(title: str, counts: dict, only_present: bool) -> None:
    rows = {k: v for k, v in counts.items() if v or not only_present}
    width = max((len(k) for k in rows), default=0)
    total = sum(counts.values())
    print(f"{title} ({len(rows)} of {len(counts)} present, {total} total)")
    for name in sorted(rows):
        print(f"  {name.ljust(width)}  {rows[name]}")
    print()


def _main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("-i", "--input", help="input network file")
    src.add_argument("--builtin", choices=sorted(_BUILTINS),
                     help="use a bundled network instead of --input")
    parser.add_argument("--all", action="store_true",
                        help="also list types/extensions with a count of zero")
    args = parser.parse_args(argv)

    network = _BUILTINS[args.builtin]() if args.builtin else pn.load(args.input)
    print(f"Network '{network.id}'\n")
    _print_section("Object types", _element_counts(network), only_present=not args.all)
    _print_section("Extensions", _extension_counts(network), only_present=not args.all)
    return 0


if __name__ == "__main__":
    sys.exit(_main())
