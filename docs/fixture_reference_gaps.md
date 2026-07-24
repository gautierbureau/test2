# Fixture vs reference-network gap tracker

Tracks how close our fully-enhanced fixtures (`python/data/*.xiidm.gz`, built by
`python/build_full_pegase.py`) come to a real reference network, by object-type
and extension counts. Use it to decide what to build next and to record progress.

- **Reference**: a real node-breaker network summary (7,803 buses / 6,900
  generators), produced with `network_summary.py --all` on a newer/custom
  powsybl toolchain. Counts pasted below.
- **PEGASE**: `python/data/pegase13659_full.xiidm.gz` (13,659 buses).
- Built on **pypowsybl 1.16.1** (limits surface as the `LOADING_LIMITS` element
  type, matching the reference).

Regenerate and re-compare:

```
cd python
python3 build_full_pegase.py -i case13659pegase.xiidm -o data/pegase13659_full.xiidm.gz
python3 network_summary.py --all -i data/pegase13659_full.xiidm.gz
```

`status` legend: `ok` = both non-zero (comparable) · `GAP` = reference has it, we
don't · `—` = not registered in our pypowsybl (unreachable) · blank = zero in both.

## Object types

| type | REF | PEGASE | status |
|---|--:|--:|---|
| BUS | 7,803 | 13,659 | ok |
| BUSBAR_SECTION | 14,073 | 13,659 | ok |
| GENERATOR | 6,900 | 4,092 | ok |
| LINE | 8,598 | 14,738 | ok |
| LINEAR_SHUNT_COMPENSATOR_SECTION | 544 | 8,754 | ok |
| LOAD | 8,438 | 5,544 | ok |
| LOADING_LIMITS | 732,839 | 312,104 | ok |
| PHASE_TAP_CHANGER | 52 | 74 | ok |
| PHASE_TAP_CHANGER_STEP | 1,705 | 74 | ok |
| RATIO_TAP_CHANGER | 1,793 | 5,655 | ok |
| RATIO_TAP_CHANGER_STEP | 33,469 | 96,135 | ok |
| SELECTED_LOADING_LIMITS | 87,713 | 312,104 | ok |
| SHUNT_COMPENSATOR | 544 | 8,754 | ok |
| SUBSTATION | 5,321 | 8,354 | ok |
| SWITCH | 86,531 | 118,648 | ok |
| TERMINAL | 52,495 | 72,983 | ok |
| TWO_WINDINGS_TRANSFORMER | 2,649 | 5,729 | ok |
| VOLTAGE_LEVEL | 6,854 | 8,354 | ok |
| BATTERY | 27 | 47 | ok |
| HVDC_LINE | 6 | 11 | ok |
| STATIC_VAR_COMPENSATOR | 7 | 12 | ok |
| VSC_CONVERTER_STATION | 12 | 22 | ok |
| PROPERTIES | 137,858 | 25,062 | ok |
| REACTIVE_CAPABILITY_CURVE_POINT | 13,937 | 12,276 | ok |

Zero in both (omitted from work): ALIAS, AREA, AREA_BOUNDARIES,
AREA_VOLTAGE_LEVELS, BOUNDARY_LINE, BOUNDARY_LINE_GENERATION, DC_BUS, DC_GROUND,
DC_LINE, DC_NODE, DC_SWITCH, GROUND, LCC_CONVERTER_STATION,
NON_LINEAR_SHUNT_COMPENSATOR_SECTION, SUB_NETWORK, THREE_WINDINGS_TRANSFORMER,
TIE_LINE, VOLTAGE_ANGLE_LIMITS, VOLTAGE_SOURCE_CONVERTER.

## Extensions

| extension | REF | PEGASE | status |
|---|--:|--:|---|
| activePowerControl | 6,900 | 4,092 | ok |
| branchObservability | 10,892 | 20,467 | ok |
| busbarSectionPosition | 14,073 | 13,659 | ok |
| detail | 8,140 | 5,544 | ok |
| discreteMeasurements | 2,753 | 5,729 | ok |
| injectionObservability | 25,445 | 9,636 | ok |
| measurements | 42,358 | 142,074 | ok |
| position | 38,124 | 59,324 | ok |
| voltageRegulation | 27 | 47 | ok |
| generatorShortCircuit | 6,900 | 4,092 | ok |
| hvdcAngleDroopActivePowerControl | 6 | 11 | ok |
| hvdcOperatorActivePowerRange | 4 | 11 | ok |
| identifiableShortCircuit | 6,808 | 8,354 | ok |
| standbyAutomaton | 7 | 12 | ok |
| coordinatedReactiveControl | 110 | 0 | GAP |
| congestionManagement | 27 | — | — |
| currentLimitsPerSeason | 732,839 | — | — |
| stateOfCharge | 20 | — | — |
| activeSeason | 0 | — | — |

Zero in both (omitted): cgmesMetadataModels, entsoeArea, entsoeCategory,
generatorConnectionLevel, linePosition, referencePriorities,
secondaryVoltageControl, slackTerminal, substationPosition,
synchronizedGeneratorProperties, synchronousGeneratorProperties,
threeWindingsTransformerPhaseAngleClock, twoWindingsTransformerPhaseAngleClock,
voltagePerReactivePowerControl.

## Gap tracker (what to build next)

### A. Cheap wins — data we can derive/carry

- [x] **`busbarSectionPosition`** (ext, 14,073) — done: the node-breaker
  converter now tags every rebuilt busbar section (one busbar, sections 1..k per
  voltage level).
- [x] **`voltageRegulation`** (ext, 27) — done: attached (regulator off) to each
  synthetic battery in `add_equipment.py`.
- [x] **`detail`** (ext, 8,140) — done: `complete_network.add_load_detail` splits
  each load's P/Q into a fixed (40 %) and variable (60 %) part.
- [x] **`discreteMeasurements`** (ext, 2,753) — done:
  `complete_network.add_discrete_measurements` adds a tap-position measurement
  per ratio/phase tap changer.
- [x] **`PROPERTIES`** (137,858) — done: `complete_network.add_properties` tags
  every substation with `region` + `country_code` and every voltage level with
  `voltage_class` (PEGASE 25,062; ACTIVSg70k 178,639, incl. the 4,200 carried
  from its PSS/E source).

### B. Needs equipment synthesis

- [x] **`BATTERY`** (27), **`STATIC_VAR_COMPENSATOR`** (7), **`HVDC_LINE`** (6) +
  **`VSC_CONVERTER_STATION`** (12) — done in `add_equipment.py`: sparse,
  electrically-neutral synthetic devices injected before the load flow and
  carried across the node-breaker conversion (PEGASE now 47/12/11/22,
  ACTIVSg70k 235/61/52/104). Their control extensions are also set:
  `hvdcAngleDroopActivePowerControl` + `hvdcOperatorActivePowerRange` per HVDC
  link and `standbyAutomaton` per SVC. (`stateOfCharge` on batteries is not
  registered in pypowsybl — see section C.)
- [x] **`REACTIVE_CAPABILITY_CURVE_POINT`** (13,937) — done:
  `complete_network.add_reactive_capability_curves` gives every generator a
  3-point P-dependent capability curve (armature circle, floored, never narrower
  than the existing band). PEGASE 12,276; ACTIVSg70k 31,170.
- [x] **`generatorShortCircuit`** / **`identifiableShortCircuit`** (6,900 / 6,808)
  — done: `complete_network.add_short_circuit` sets transient/subtransient
  reactances on generators and min/max fault current on voltage levels (PEGASE
  4,092 / 8,354; ACTIVSg70k 10,390 / 65,263).
- [ ] **`coordinatedReactiveControl`** (110) — set on a subset of generators in a
  coordinated voltage-control zone.

### C. Unreachable from pypowsybl (`—`)

Not registered even in pypowsybl 1.16.1 — cannot be created from Python; they
come from the reference's own (RTE/CGMES-style) toolchain. Out of scope unless
the powsybl stack changes.

- `currentLimitsPerSeason` (732,839) — seasonal limits. Our equivalent is
  multiple named limit groups via `add_current_limits(seasons=...)`, not this
  extension.
- `congestionManagement` (27), `stateOfCharge` (20), `activeSeason`.
