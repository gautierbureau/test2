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
    ├── TransformerTransport.java   per-unit transport math + orientation
    ├── CurveTransporter.java       sweep across the original P range
    ├── EquivalentBuilder.java      mutate the network in place
    └── Main.java                   picocli CLI entry point
```

## Dependencies

- powsybl-core 7.1.1 (IIDM model + serde)
- powsybl-open-loadflow 2.1.1 (validation only)
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
