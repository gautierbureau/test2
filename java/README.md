# curve-transporter

Build an **equivalent network** where a generator behind a transformer (with
its auxiliary load on the LV side) is replaced by a single equivalent
generator on the HV bus. The original generator's reactive capability curve
is **transported analytically** through the transformer pi-model so that the
equivalent generator's (P, Q) injection at the HV bus is exactly what the
transformer was delivering.

This is the Java/Maven port of the validated Python prototype.

## Topology assumed

```
[external HV grid] --- HV bus --- [TX HV/LV] --- LV bus
                                                  |
                                           +------+------+
                                           |             |
                                       [generator]   [aux load]
```

After running the tool the LV bus, the transformer, the LV generator and
the auxiliary load are gone. A new equivalent generator sits on the HV bus
with a transported reactive capability curve and a (P, Q) target equal to
what the transformer was injecting at the HV bus before.

## Method

Per-unit analytical transport, on the HV side:

```
i1 = conj(s1 / v_lv)             (LV-side current, pu)
v1' = v_lv * rho                 (refer through ideal ratio)
i1' = i1 / rho
v_hv = v1' - z * i1'             (drop across series impedance)
i_shunt = y * v_hv               (HV-side magnetising shunt)
i_hv = i1' - i_shunt
s_hv = v_hv * conj(i_hv)         (injection toward HV grid)
```

Where `s1 = (P_gen - P_aux) + j(Q_gen - Q_aux)`, `R/X/G/B` are the IIDM
side-2 transformer parameters referred to the HV side, and `v_lv` is the
generator's regulating setpoint (`targetV`).

The reactive capability curve is sampled at `n` values of `P_gen`. For each
P, the (P, Qmin) and (P, Qmax) extremes are transported, yielding `Qmin_hv`
and `Qmax_hv` at the resulting `P_hv`.

For a derivation of the sqrt(3) issue and why per-unit avoids it, see the
header of `TransformerTransport.java`.

## Build

Requires JDK 17+ and Maven 3.8+.

```bash
mvn clean package
```

Produces:
- `target/curve-transporter-1.0.0.jar` (slim)
- `target/curve-transporter-1.0.0-shaded.jar` (fat, runnable)

## Run

```bash
java -jar target/curve-transporter-1.0.0-shaded.jar \
    --input    path/to/network.xiidm \
    --output   path/to/equivalent.xiidm \
    --generator   GEN_LV \
    --transformer TX \
    --aux-load    AUX_LOAD \
    --new-id      EQ_GEN_HV \
    --samples     25 \
    --validate
```

`--validate` runs an AC load flow on the equivalent network with
distributed slack disabled (so the equivalent generator stays at its target)
and reports the actual (P, Q) injection and HV voltage.

Help:

```bash
java -jar target/curve-transporter-1.0.0-shaded.jar --help
```

## Project layout

```
curve-transporter/
├── pom.xml
└── src/main/java/com/example/transporter/
    ├── TransformerTransport.java       per-unit transport math + orientation
    ├── CurveTransporter.java           sweep across the original P range
    ├── EquivalentBuilder.java          mutate the network in place
    ├── Main.java                       picocli CLI entry point
    ├── BusToNodeBreakerConverter.java  bus-breaker → node-breaker converter (rebuild)
    ├── InPlaceNodeBreakerConverter.java  prototype: same conversion by moving feeders
    ├── ExtendedIeee14Factory.java      IEEE-14 grown with extra equipment types
    ├── ConvertToNodeBreaker.java       picocli CLI for the converter
    ├── CurrentLimitsGenerator.java     load-flow-based current limit sets
    ├── AddCurrentLimits.java           picocli CLI for the limit generator
    ├── NetworkCompleter.java           fill missing reactive limits + ratio taps
    └── CompleteNetwork.java            picocli CLI for the completer
```

## Bus-breaker → node-breaker conversion

`BusToNodeBreakerConverter.convert(Network)` rebuilds a bus-breaker network as
an electrically identical **node-breaker** network. For every configured bus it
creates one busbar section, and every feeder that used to sit on that bus is
reconnected through its own bay — a disconnector to the busbar plus a series
breaker — using powsybl's `CreateFeederBay` / `CreateBranchFeederBays`
modifications. The transformation is purely topological, so a load flow on the
result matches the original bus-for-bus.

Supported equipment: generators (min/max or curve reactive limits), loads,
batteries, static var compensators, boundary lines (the type powsybl 7.3
renamed from `DanglingLine`), VSC and LCC converter stations (all reconnected
as injection bays); lines, two- and three-winding
transformers with their ratio/phase tap changers; HVDC
lines; tie lines; linear/non-linear shunts; and bus couplers (mapped to
breakers between busbars). All **operational-limit groups** (current /
apparent-power / active-power limits, every named group with the selected one
restored) are copied over, as is the `activePowerControl` extension; the powsybl
Java model has no generic extension clone, so terminal- and position-bound
extensions are left to the rebuild. The only connectable type still unhandled is
`Ground`, which raises a clear `UnsupportedOperationException` rather than
failing silently.

Run it on the bundled IEEE-14 network, the extended IEEE-14 network (which adds
one of every supported extra type), or any bus-breaker `.xiidm`:

```bash
java -cp target/curve-transporter-1.0.0-shaded.jar \
     com.example.transporter.ConvertToNodeBreaker \
     --ieee14 -o /tmp/ieee14_nb.xiidm --validate
# --ieee14-extended : IEEE-14 + battery, SVC, boundary line, non-linear shunt,
#                     ratio/phase tap changers, 3-winding transformer, VSC HVDC,
#                     LCC HVDC, a tie line and a bus coupler
# --input path/to/bus_breaker.xiidm -o out.xiidm --validate
```

### Busbar layout options

By default a bus hosting **generators** is given one busbar section **per
generator** (the usual layout for a multi-unit power station, where every unit
must be independently switchable); a bus without generators becomes a single
section. The sections of a bus are chained by *closed* coupler breakers, so they
stay one electrical node until a coupler is opened.

The two counts are **decoupled**:

- `--busbars-per-bus N` — how many busbar sections buses **without** generators
  get (a sectionalized single busbar). Feeders are spread round-robin. Generator
  buses are unaffected — they always get one section per generator.
- `--no-generator-busbars` — disable the one-busbar-per-generator policy, so
  every bus (generator or not) gets `N` sections uniformly.

So a bus with `k` generators gets exactly `k` sections regardless of
`--busbars-per-bus`.

The same is available programmatically:
`BusToNodeBreakerConverter.convert(network, busbarSectionsPerBus, oneBusbarPerGenerator)`.

`--validate` runs an AC load flow on both networks and reports the maximum bus
voltage/angle deviation (≈1e-4 kV / 1e-4 deg on IEEE-14; ≈1e-3 kV / 1e-2 deg on
the extended network, where the SVC control loop adds a little solver noise). It
tries a flat (uniform) start first and falls back to a DC-based start if either
load flow fails to converge — a large node-breaker graph can trip a flat start
where the bus-breaker one held.

### Scale

The converter has been exercised well beyond IEEE-14. Feed it any bus-breaker
`.xiidm` (for example a MATPOWER/PEGASE case exported through pypowsybl):

| network        | buses → busbar sections | validate (max ΔV / Δangle) |
|----------------|-------------------------|----------------------------|
| IEEE-118       | 118                     | 2.7e-3 kV / 2.3e-2 deg     |
| IEEE-300       | 300                     | 5.8e-3 kV / 9.6e-3 deg     |
| case1354pegase | 1 354                   | 4.3e-6 kV / 4.3e-3 deg     |
| case2869pegase | 2 869                   | 4.0e-7 kV / 3.0e-4 deg     |
| case9241pegase | 9 241                   | 2.3e-6 kV / 6.1e-4 deg *   |

`*` case9241pegase needs the DC-start fallback to converge; the network itself
is electrically identical either way.

The three-winding transformer is the one type powsybl has no ready-made feeder
bay for, so the converter builds each of its three bays by hand (a disconnector
to the busbar plus a series breaker per leg); every other type goes through
`CreateFeederBay` / `CreateBranchFeederBays`.

### Prototype: in-place conversion by moving feeders

`BusToNodeBreakerConverter` **rebuilds** the network from scratch, so anything
attached to an element that it does not explicitly re-create is dropped (the
reason operational limits and extensions had to be copied by hand, above).

`InPlaceNodeBreakerConverter` is a prototype of a fundamentally different, and
cleaner, approach: it never destroys a connectable. It creates a node-breaker
voltage level per bus-breaker one, then **moves** each feeder onto its busbar
section with powsybl's `MoveFeederBay` modification, re-creates bus couplers as
coupling devices, and removes the emptied voltage levels. Because the
connectables are moved rather than rebuilt, **every piece of data on them
survives automatically** — limit groups and their selection, all extensions,
properties, aliases, reactive limits, tap changers — with *no* per-attribute
copy code. Validated on the extended IEEE-14 (limits, `activePowerControl`, a
property and an alias all preserved, HVDC/tie line/3-winding transformer intact,
load flow unchanged) and on IEEE-300.

It has the **same busbar-layout options** as the rebuild converter —
`convert(network, busbarSectionsPerBus, oneBusbarPerGenerator)` with the same
decoupled counts (one section per generator on generator buses, N sections on
the rest), sections chained by closed coupling devices.

This is the shape the conversion would ideally take upstream in powsybl-core (a
`NetworkModification` that moves feeders). Its one prototype limitation:
node-breaker voltage levels take a new id (`_NB` suffix) because IIDM fixes a
voltage level's topology kind at creation and cannot rename — so the *containers*
change id while every *connectable* keeps its id and data.

## Load-flow-based current limit sets

`CurrentLimitsGenerator.apply(network, config)` fills in the operational
**current limits** that many cases ship without — the PEGASE snapshots are the
usual example (`case13659pegase` has none at all). It runs an AC load flow,
reads the current that actually flows through each branch side, and sizes a
complete operational-limit group (a "current limits set") around it: one
permanent limit (PATL) plus a configurable ladder of temporary limits (TATL),
each with its own acceptable duration ("temporisation").

Sizing is **per side**, because the amperage on the two ends of a transformer
differs by the voltage ratio — a current limit is only meaningful next to the
current it bounds. For a side carrying `I` amps:

```
permanent          value = I * permanentMargin        duration = -1 (∞)
temporary tier k   value = permanent * tierMargin_k    duration = tierSeconds_k
```

With the defaults (`permanentMargin=1.25`, tiers `1200s:1.10, 600s:1.20,
60s:1.40`) a branch loaded to `I` in the base case sits at exactly 80 %
(`1 / 1.25`) of its permanent limit. The set is stored as a named
operational-limit group and selected as the branch's active group; existing
groups are left untouched (`--only-missing` skips branches that already carry
limits). Lines, two- and three-winding transformers and boundary (dangling)
lines are handled; sides with no usable flow (disconnected / near-zero current)
are skipped. This mirrors the Python `add_current_limits.py` module bit for bit
— the two produce identical side counts on every case below.

```bash
java -cp target/curve-transporter-1.0.0-shaded.jar \
     com.example.transporter.AddCurrentLimits --ieee14 --validate
# --ieee14-extended : IEEE-14 + 3-winding transformer, boundary lines, …
# -i case13659pegase.xiidm -o out.xiidm --validate
# --permanent-margin 1.3 --tiers 1200:1.10,600:1.20,60:1.40 --group-name LOADFLOW_BASED
```

`--validate` runs the load flow and reports each side's base-case loading
against its new permanent limit (peaks at `1/permanentMargin`, no overloads).
Exercised on IEEE-14/300 and on the PEGASE cases:

| network         | branch sides sized | skipped (no flow) | max base loading |
|-----------------|--------------------|-------------------|------------------|
| case1354pegase  | 3 968              | 14                | 80.0 %           |
| case9241pegase  | 31 632             | 466               | 80.0 % *         |
| case13659pegase | 39 798             | 1 136             | 80.0 %           |

`*` case9241pegase needs the DC-start fallback to converge, applied
automatically. `case13659pegase` carries no operational limits at all, so the
tool builds the full set from scratch.

## Completing missing network data

`NetworkCompleter` (CLI `complete-network`) fills several things a case is often
missing, sized from a load flow — the Java port of Python's
`complete_network.py`. With no completion flag, all of them run:

- **`addReactiveLimits`** gives a generator a finite MIN_MAX band when it has
  none or a placeholder "infinite" one (`|Q| >= 1e4`, the MATPOWER/PEGASE
  "unlimited" convention). Sized as `Q = sqrt(ratedS^2 - P^2)` when a rated
  apparent power is known, otherwise from a power factor on the active power.
  Real existing bands and reactive capability curves are left untouched.
- **`addRatioTapChangers`** gives a two-winding transformer a voltage-regulating
  ratio tap changer when it has none: symmetric steps around `rho = 1` (±10 %
  over 8 steps by default) with the tap at **neutral**, regulating the side-2
  voltage to its base-case value. At the neutral tap the transformer is
  electrically identical to before, so the base case is unchanged; the regulator
  only acts once tap control is on, with its setpoint already at the current
  voltage.

- **`setGeneratorEnergySource`** assigns an energy source (default `THERMAL`) to
  generators whose source is `OTHER`. A load flow can't infer fuel type, so this
  is a documented blanket default, not inference.
- **`addActivePowerControl`** sets the `ActivePowerControl` extension (participate,
  participation factor proportional to `maxP`, configurable droop) so distributed
  slack / redispatch has something to act on.

Phase tap changers are deliberately not synthesized (a phase shifter is a
physical device; cases that use them already carry them).

```bash
java -cp target/curve-transporter-1.0.0-shaded.jar \
     com.example.transporter.CompleteNetwork --ieee14          # all completions
# -i case13659pegase.xiidm -o out.xiidm
# --reactive-limits                                (only reactive limits)
# --ratio-tap-changers --rtc-steps 8 --rtc-step 0.0125
# --energy-source --active-power-control
```

On `case13659pegase` this fills the 7 placeholder reactive bands, adds a ratio
tap changer to the 5 655 transformers lacking one (the 74 phase-shifters are
left alone), sets 4 092 energy sources and 4 092 participation factors —
matching the Python module's counts.

## Dependencies

- powsybl-core 7.3.0 (IIDM model + serde; reads IIDM up to schema 1.17, so it
  ingests pypowsybl's default 1.16 exports directly)
- powsybl-open-loadflow 2.3.0 (validation only)
- powsybl-ieee-cdf-converter 7.3.0 (IEEE-14 demo network)
- commons-math3 3.6.1 (complex arithmetic)
- picocli 4.7.6 (CLI)
- slf4j 2.0.13

The maven-shade-plugin merges all `META-INF/services/*` files (powsybl
discovers loadflow providers, importers and exporters via ServiceLoader),
strips signed-JAR signatures, and produces a single self-contained jar.

## Notes & caveats

- **LV voltage assumption.** The transport assumes the LV bus stays at the
  generator's `targetV`. In the original network a load flow with the AVR
  active will hold this exactly; if it doesn't (Q hits a limit, AVR off…),
  the transported curve is approximate at the boundary.
- **Topology.** Both bus-breaker and node-breaker HV voltage levels are
  supported. The new generator reuses the node/bus the transformer was
  connected to.
- **Tap changers.** The current rho of any ratio-tap-changer is folded
  into the ideal-ratio of the transport. Phase shifters are not handled —
  if your transformer has a phase tap changer, extend
  `TransformerTransport.orientToHv` to multiply `rhoTap` by the complex
  step ratio.
- **Auxiliary load model.** Constant PQ. For ZIP loads, evaluate
  `P_aux(V_lv)` and `Q_aux(V_lv)` before calling `transport`.
