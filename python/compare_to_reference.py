"""
Compare our fully-enhanced fixtures against a reference (real) network summary.

Prints a three-way table (reference | PEGASE 13k | ACTIVSg70k) for every object
type and extension, and calls out the gaps: things the reference has that our
networks don't. The reference counts are pasted in below (produced by
network_summary.py --all on a newer pypowsybl, so a couple of types are renamed
- handled by the alias map).
"""

from __future__ import annotations

import pypowsybl.network as pn

from network_summary import _element_counts, _extension_counts

# --- reference network (pasted) -------------------------------------------
REF_TYPES = {
    "ALIAS": 0, "AREA": 0, "AREA_BOUNDARIES": 0, "AREA_VOLTAGE_LEVELS": 0,
    "BATTERY": 27, "BOUNDARY_LINE": 0, "BOUNDARY_LINE_GENERATION": 0, "BUS": 7803,
    "BUSBAR_SECTION": 14073, "DC_BUS": 0, "DC_GROUND": 0, "DC_LINE": 0, "DC_NODE": 0,
    "DC_SWITCH": 0, "GENERATOR": 6900, "GROUND": 0, "HVDC_LINE": 6,
    "LCC_CONVERTER_STATION": 0, "LINE": 8598, "LINEAR_SHUNT_COMPENSATOR_SECTION": 544,
    "LOAD": 8438, "LOADING_LIMITS": 732839, "NON_LINEAR_SHUNT_COMPENSATOR_SECTION": 0,
    "PHASE_TAP_CHANGER": 52, "PHASE_TAP_CHANGER_STEP": 1705, "PROPERTIES": 137858,
    "RATIO_TAP_CHANGER": 1793, "RATIO_TAP_CHANGER_STEP": 33469,
    "REACTIVE_CAPABILITY_CURVE_POINT": 13937, "SELECTED_LOADING_LIMITS": 87713,
    "SHUNT_COMPENSATOR": 544, "STATIC_VAR_COMPENSATOR": 7, "SUBSTATION": 5321,
    "SUB_NETWORK": 0, "SWITCH": 86531, "TERMINAL": 52495,
    "THREE_WINDINGS_TRANSFORMER": 0, "TIE_LINE": 0, "TWO_WINDINGS_TRANSFORMER": 2649,
    "VOLTAGE_ANGLE_LIMITS": 0, "VOLTAGE_LEVEL": 6854, "VOLTAGE_SOURCE_CONVERTER": 0,
    "VSC_CONVERTER_STATION": 12,
}
REF_EXTS = {
    "activePowerControl": 6900, "activeSeason": 0, "branchObservability": 10892,
    "busbarSectionPosition": 14073, "cgmesMetadataModels": 0,
    "congestionManagement": 27, "coordinatedReactiveControl": 110,
    "currentLimitsPerSeason": 732839, "detail": 8140, "discreteMeasurements": 2753,
    "entsoeArea": 0, "entsoeCategory": 0, "generatorConnectionLevel": 0,
    "generatorShortCircuit": 6900, "hvdcAngleDroopActivePowerControl": 6,
    "hvdcOperatorActivePowerRange": 4, "identifiableShortCircuit": 6808,
    "injectionObservability": 25445, "linePosition": 0, "measurements": 42358,
    "position": 38124, "referencePriorities": 0, "secondaryVoltageControl": 0,
    "slackTerminal": 0, "standbyAutomaton": 7, "stateOfCharge": 20,
    "substationPosition": 0, "synchronizedGeneratorProperties": 0,
    "synchronousGeneratorProperties": 0, "threeWindingsTransformerPhaseAngleClock": 0,
    "twoWindingsTransformerPhaseAngleClock": 0, "voltagePerReactivePowerControl": 0,
    "voltageRegulation": 27,
}

# Our (older) pypowsybl uses different names for the same concept.
_TYPE_ALIASES = {
    "OPERATIONAL_LIMITS": "LOADING_LIMITS",
    "SELECTED_OPERATIONAL_LIMITS": "SELECTED_LOADING_LIMITS",
}

FIXTURES = {
    "PEGASE13k": "data/pegase13659_full.xiidm.gz",
    "ACTIVSg70k": "data/ACTIVSg70k_full.xiidm.gz",
}


def _aliased(counts: dict) -> dict:
    return {_TYPE_ALIASES.get(k, k): v for k, v in counts.items()}


def _row(name, ref, ours):
    """Format one line; '-' = concept unknown to our pypowsybl build."""
    cells = [f"{name:38s}", f"{ref:>10}"]
    marks = []
    for net in FIXTURES:
        v = ours[net].get(name)
        cells.append("         -" if v is None else f"{v:>10}")
    ref_present, us_present = ref > 0, any(ours[n].get(name, 0) for n in FIXTURES)
    if ref_present and not us_present:
        tag = "GAP"
    elif ref_present and us_present:
        tag = "ok"
    elif not ref_present and us_present:
        tag = "extra"
    else:
        tag = ""
    return "  ".join(cells) + f"  {tag}"


def _section(title, ref, ours):
    header = "  ".join([f"{title:38s}", f"{'REF':>10}"]
                       + [f"{n:>10}" for n in FIXTURES])
    print(header)
    print("-" * len(header))
    names = sorted(set(ref) | {k for n in ours for k in ours[n]})
    for name in names:
        print(_row(name, ref.get(name, 0), ours))
    print()


def main():
    types, exts = {}, {}
    for net, path in FIXTURES.items():
        n = pn.load(path)
        types[net] = _aliased(_element_counts(n))
        # element_counts skips aggregate super-types; that matches the reference.
        exts[net] = _extension_counts(n)

    print("OBJECT TYPES  (REF = reference/real network; '-' = not in our pypowsybl)\n")
    _section("type", REF_TYPES, types)
    print("EXTENSIONS\n")
    _section("extension", REF_EXTS, exts)

    # Gap summary
    print("GAPS — reference has it, both our fixtures are 0/absent:")
    for label, ref, ours in [("type", REF_TYPES, types), ("extension", REF_EXTS, exts)]:
        for name in sorted(ref):
            if ref[name] > 0 and not any(ours[n].get(name, 0) for n in FIXTURES):
                supported = any(name in ours[n] for n in FIXTURES) or label == "type"
                note = "" if supported else " (extension unknown to our pypowsybl)"
                print(f"  [{label}] {name}: ref={ref[name]}{note}")


if __name__ == "__main__":
    main()
