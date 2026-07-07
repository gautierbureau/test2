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
(only grounds are unhandled) — plus the `--busbars-per-bus` and
one-busbar-per-generator options. Validated bus-for-bus on IEEE-14/118/300, on
an extended IEEE-14 exercising every added type, and on PEGASE 1354/2869/9241
(the last needs a DC-start fallback, applied automatically by `validate()`).

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
