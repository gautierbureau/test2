# Reactive Capability Curve Transporter

Build an equivalent network where a generator behind a transformer is
replaced by a single equivalent generator on the HV bus, with the reactive
capability curve transported analytically through the transformer pi-model.

## Two implementations

- **`python/`** — `transport_curve.py` is a self-contained pypowsybl script.
  Builds a small test network, transports the curve analytically, builds the
  equivalent network, and validates by running an AC load flow on both
  networks. **Validated end-to-end** in a sandbox: `dP = 0.000 MW,
  dQ = 0.000 MVar` between the original transformer flow and the equivalent
  generator injection. Run with:
  ```
  pip install pypowsybl
  python3 transport_curve.py
  ```

- **`java/`** — Maven project that does the same as a CLI tool, reading an
  IIDM file and writing the equivalent IIDM file. Built as a fat jar via
  `maven-shade-plugin`. **Compiled and validated** against the Python output:
  all 11 transported curve points match to 3 decimal places. Build & run:
  ```
  cd java
  mvn clean package
  java -jar target/curve-transporter-1.0.0-shaded.jar \
       -i src/main/resources/test_network.xiidm \
       -g GEN_LV -t TX -a AUX_LOAD -n GEN_HV_EQ -s 11 \
       -o /tmp/equivalent.xiidm --validate
  ```

## Bus-breaker → node-breaker conversion

There are two implementations of a **bus-breaker to node-breaker converter**:
one in Java (`java/`, the reference) and a pypowsybl port in Python
(`python/bus_to_node_breaker.py`). Both build an electrically identical
node-breaker network — one busbar section per configured bus, every feeder
reconnected through a disconnector + breaker bay — and validate it with an AC
load flow.

### Python (pypowsybl)

`convert(network, busbar_sections_per_bus=1, one_busbar_per_generator=True)`
rebuilds the network with pypowsybl's `create_*_bay` / `create_coupling_device`
helpers. It supports the same equipment as the Java version — generators,
loads, batteries, static var compensators, boundary lines, VSC/LCC converter
stations, lines, two- and three-winding transformers (with ratio/phase tap
changers), HVDC lines, tie lines, linear/non-linear shunts and bus couplers
(only grounds are unhandled). Validated bus-for-bus on IEEE-14/118/300, on
an extended IEEE-14 exercising every added type, and on PEGASE 1354/2869/9241
(the last needs a DC-start fallback, applied automatically by `validate()`).

The rebuild **preserves operational limits and extensions**: every
operational-limit group (current / apparent-power / active-power, all named
groups, not just the selected one) is copied over with the active group
restored, and connectable extensions keyed by element id (e.g.
`activePowerControl`, `generatorShortCircuit`) are carried across. Extensions the
conversion sets itself (feeder `position`) and terminal-bound ones (e.g.
`slackTerminal`) are left to the rebuild.

The two busbar-layout counts are **decoupled**: `one_busbar_per_generator`
(default) puts each generator on its own busbar section on the buses that host
generators, while `busbar_sections_per_bus` sets how many sections the buses
*without* generators get (a sectionalized single busbar). A bus with `k`
generators therefore gets exactly `k` sections regardless of
`busbars_per_bus`. Turn the policy off to give every bus `busbars_per_bus`
sections uniformly.

```
cd python
pip install -r requirements.txt
python3 bus_to_node_breaker.py --builtin ieee300 --validate
python3 bus_to_node_breaker.py --input case2869pegase.xiidm -o out.xiidm --validate
```

### Java

`java/` also contains a **bus-breaker to node-breaker converter**
(`BusToNodeBreakerConverter`). Given a bus-breaker network it builds an
electrically identical node-breaker network: one busbar section per bus, with
every feeder reconnected through a disconnector + breaker bay (powsybl's
`CreateFeederBay` / `CreateBranchFeederBays`). Validated on IEEE-14 and on an
extended IEEE-14 that adds one of every supported type — battery, SVC, boundary
line, non-linear shunt, ratio/phase tap changers, three-winding transformer,
VSC HVDC, LCC HVDC, a tie line and a bus coupler — with an AC load flow matching
the original bus-for-bus. Every IIDM connectable except `Ground` is handled.

```
cd java
mvn clean package -DskipTests
java -cp target/curve-transporter-1.0.0-shaded.jar \
     com.example.transporter.ConvertToNodeBreaker \
     --ieee14          -o /tmp/ieee14_nb.xiidm     --validate
java -cp target/curve-transporter-1.0.0-shaded.jar \
     com.example.transporter.ConvertToNodeBreaker \
     --ieee14-extended -o /tmp/ieee14_ext_nb.xiidm --validate
```

See `java/README.md` for the full list of supported equipment and current
limitations.

The test network is stored as a resource file at
`java/src/main/resources/test_network.xiidm`. It was exported by the Python
script at IIDM schema version 1.15 (compatible with powsybl-core 7.x). To
regenerate it:
```
cd python
python3 transport_curve.py \
    --save-network ../java/src/main/resources/test_network.xiidm
```

See `python/transport_curve.py` and `java/README.md` for details.

## Load-flow-based current limit sets

`python/add_current_limits.py` fills in the operational **current limits** that
many cases ship without — the PEGASE snapshots are the usual example. It runs an
AC load flow, reads the current that actually flows through each branch side, and
sizes a complete IIDM operational-limit group (a "current limits set") around it:
one permanent limit (PATL) plus a configurable ladder of temporary limits
(TATL), each with its own acceptable duration ("temporisation").

Sizing is **per side**, because the amperage on the two ends of a transformer
differs by the voltage ratio — a current limit is only meaningful next to the
current it bounds. For a side carrying `I` amps:

```
permanent          value = I * permanent_margin        duration = -1 (∞)
temporary tier k   value = permanent * tier_margin_k   duration = tier_seconds_k
```

With the defaults (`permanent_margin=1.25`, tiers `1200s:1.10, 600s:1.20,
60s:1.40`) a branch loaded to `I` in the base case sits at exactly 80 %
(`1 / 1.25`) of its permanent limit, with temporary steps above it. The set is
stored as a named operational-limit group and selected as the branch's active
group; existing groups are left untouched (use `--only-missing` to skip branches
that already carry limits).

Handles lines, two- and three-winding transformers and boundary (dangling)
lines; sides with no usable flow (disconnected / near-zero current) are skipped.

Beyond current, the tool can size **apparent-power** (`sqrt(P² + Q²)`, MVA) and
**active-power** (`|P|`, MW) limits into the same group — pass `--limit-types
CURRENT,APPARENT_POWER,ACTIVE_POWER` (any subset). All types share the permanent
margin and temporary tiers, so the base case sits at the same 80 % on each.

Ratings are often **seasonal** in reality. Pass `--seasons WINTER:1.1,SUMMER:0.9`
to create one operational-limit group per season, each with its limits scaled by
the season factor; `--selected-season` picks which one is made active (default:
the first). Without `--seasons` a single group is created.

```
cd python
pip install -r requirements.txt
python3 add_current_limits.py --builtin ieee300 --validate
python3 add_current_limits.py -i case1354pegase.mat -o out.xiidm --validate
python3 add_current_limits.py -i case.xiidm --permanent-margin 1.3 \
        --tiers 1200:1.10,600:1.20,60:1.40 --group-name LOADFLOW_BASED -o out.xiidm
python3 add_current_limits.py -i case.xiidm --limit-types CURRENT,APPARENT_POWER -o out.xiidm
python3 add_current_limits.py -i case.xiidm --seasons WINTER:1.1,SUMMER:0.9 \
        --selected-season SUMMER -o out.xiidm
```

There are **two implementations** of this feature: the Python module above and a
Java port (`CurrentLimitsGenerator` + the `add-current-limits` CLI, see
`java/README.md`). They produce identical side counts on every case tried.
Validated on IEEE-14/118/300 and on the real PEGASE cases — base-case loading
peaks at 80 % of the permanent limit with no overloads throughout:

| network         | branch sides sized | skipped (no flow) |
|-----------------|--------------------|-------------------|
| case1354pegase  | 3 968              | 14                |
| case9241pegase  | 31 632             | 466               |
| case13659pegase | 39 798             | 1 136             |

`case13659pegase` ships with no operational limits at all, so the tool builds
the full set from scratch; `case9241pegase` needs the automatic DC-start
fallback to converge.

## Completing missing network data

`python/complete_network.py` (and the Java `NetworkCompleter` / `complete-network`
CLI) fill several things a case is often missing, sized from a load flow. With
no completion flag on the CLI, all of them run:

- **Generator reactive limits** — `add_reactive_limits` gives a generator a
  finite MIN_MAX band when it has none or carries a placeholder "infinite" one
  (the `|Q| >= 1e4` values MATPOWER/PEGASE use for "unlimited"). The band is
  `Q = sqrt(ratedS^2 - P^2)` when a rated apparent power is known, else a power
  factor applied to the active power. Real existing bands and reactive
  capability curves are left untouched.
- **Ratio tap changers** — `add_ratio_tap_changers` gives a two-winding
  transformer a voltage-regulating ratio tap changer when it has none: symmetric
  steps around `rho = 1` (±10 % over 8 steps by default) with the tap at
  **neutral**, regulating the side-2 voltage to its base-case value. At the
  neutral tap the transformer is electrically identical to before, so the base
  case is unchanged (max ΔV ≈ 0); the regulator only acts once tap control is
  switched on, and its setpoint already equals the current voltage.

- **Generation mix** — `set_generation_mix` lays down a realistic energy-source
  distribution instead of a single default. Fuel type can't be inferred from a
  load flow, so generators are ranked by active-power capability and the largest
  units get the base-load sources (nuclear, thermal), the smallest the
  intermittent ones (wind, solar), so the shares of a representative European mix
  (`{NUCLEAR: 0.15, THERMAL: 0.35, HYDRO: 0.20, WIND: 0.20, SOLAR: 0.10}` by
  default) are met **by capacity**. Deterministic; only generators whose source
  is `OTHER` are touched.
- **Apparent-power ratings (`rated_s`)** — `add_rated_s` fills the nameplate MVA
  many cases omit: generators get `rated_s = |P| / power_factor` (0.85 default),
  two-winding transformers `rated_s = base-case apparent flow / loading` (0.6
  default, i.e. loaded to 60 % of nameplate in the base case). Existing ratings
  are left untouched.
- **Active power control** — `add_active_power_control` sets the
  `activePowerControl` extension (participate = true, participation factor
  proportional to `maxP`, configurable droop) so distributed slack / redispatch
  has something to act on.
- **Synthetic measurements** (opt-in, `--measurements`) — `add_measurements`
  attaches `measurements` extensions valued from the load flow: active/reactive
  power on every generator and load, and active/reactive power and current on
  every line and transformer side, each with a standard deviation of
  `max(|value| * rel, floor)`. Useful as state-estimation input.
- **Observability** (opt-in, `--observability`) — `add_observability` marks every
  injection and branch observable via the `injectionObservability` /
  `branchObservability` extensions, with a per-quantity standard deviation.

Phase tap changers are deliberately **not** synthesized — a phase shifter is a
specific physical device, and cases that use them already carry them (PEGASE 13k
has 74). On `case13659pegase` the run-all default fills the 7 placeholder-infinite
reactive bands, adds a ratio tap changer to the 5 655 transformers that lack one
(leaving the 74 phase-shifters alone), assigns the mix across all 4 092
generators (95 nuclear … 1 596 wind by count, base-load first), rates 5 727
transformers, and adds 4 092 participation factors. Measurements and observability
are opt-in (142 074 measurements on 30 103 elements when enabled). Python and Java
produce identical counts.

```
cd python
python3 complete_network.py --builtin ieee14                   # all completions
python3 complete_network.py -i case13659pegase.mat -o out.xiidm
python3 complete_network.py -i case.xiidm --reactive-limits    # only one completion
python3 complete_network.py -i case.xiidm --ratio-tap-changers --rtc-steps 8 --rtc-step 0.0125
python3 complete_network.py -i case.xiidm --generation-mix --rated-s --active-power-control
python3 complete_network.py -i case.xiidm --measurements --observability -o out.xiidm
```

## Method (per-unit, on HV side)

```
i1     = conj(s1 / v_lv)         LV-side current
v1'    = v_lv * rho              refer through ideal ratio
i1'    = i1 / rho
v_hv   = v1' - z * i1'           drop across series Z
i_shnt = y * v_hv                magnetising shunt
i_hv   = i1' - i_shnt
s_hv   = v_hv * conj(i_hv)       injection toward the HV grid
```

with `s1 = (P_gen - P_aux) + j(Q_gen - Q_aux)` and `R, X, G, B` (powsybl
side-2 parameters) referred to the HV side. The reactive capability curve
is sampled in P; for each P the (Qmin, Qmax) extremes are transported,
giving (P_hv, Qmin_hv, Qmax_hv).

The per-unit formulation eliminates the line-to-line vs line-to-neutral
sqrt(3) ambiguity that bites you if you try the math directly in physical
units.

The transporter works in place (it removes the LV generator, aux load and
transformer and adds the HV equivalent, leaving the rest of the network
untouched), and it **carries each original generator's `activePowerControl`
extension onto its equivalent** so distributed slack / redispatch still sees the
unit. Both the Python (`transport_curve.py`) and Java (`EquivalentBuilder`)
implementations do this.
