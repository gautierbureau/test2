package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.modification.topology.RemoveFeederBay;
import com.powsybl.iidm.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Modify a network in place to replace
 * {@code (LV-generator(s) + auxiliary load(s) + transformer)} by equivalent
 * generator(s) on the HV bus, each carrying the transported reactive
 * capability curve and the transported (P, Q) operating point of its source.
 *
 * <p>Both bus-breaker and node-breaker topologies are supported on the HV
 * voltage level. For node-breaker, each equivalent generator is connected
 * through its own new disconnector + breaker bay to the HV busbar section;
 * for bus-breaker, to the same bus the transformer's HV terminal used.
 *
 * <p>When multiple generators share a transformer, each is transported
 * independently at the assumed LV voltage (typically each generator's
 * regulating setpoint). The per-generator transport is exact as long as
 * the LV bus voltage is fixed - which is the working assumption of this
 * tool - so no cross-coupling correction is needed.
 */
public final class EquivalentBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EquivalentBuilder.class);

    private EquivalentBuilder() {
        // Static utility
    }

    public record BuildResult(Generator equivalentGenerator,
                              List<CurveTransporter.HvCurvePoint> curve) { }

    /**
     * Specification of one generator to transport through a shared transformer.
     *
     * @param generatorId    ID of the LV-side generator
     * @param auxLoadId      ID of this generator's auxiliary load on the LV bus,
     *                       or {@code null} if none
     * @param newGeneratorId ID for the new equivalent HV generator
     */
    public record GeneratorSpec(String generatorId,
                                String auxLoadId,
                                String newGeneratorId) { }

    /** Result of a multi-generator build - one {@link BuildResult} per input spec, in order. */
    public record MultiBuildResult(List<BuildResult> perGenerator) {
        public BuildResult first() {
            return perGenerator.get(0);
        }
    }

    /**
     * Build the equivalent generator on the HV bus and return it. The
     * original generator, auxiliary load and transformer are removed from
     * the network.
     *
     * @param network         the network to modify in place
     * @param generatorId     ID of the LV-side generator
     * @param transformerId   ID of the 2-winding transformer
     * @param auxLoadId       ID of the auxiliary load (may be {@code null})
     * @param newGeneratorId  ID for the new equivalent generator
     * @param nSamples        number of points used to discretize the curve
     */
    public static BuildResult build(Network network,
                                    String generatorId,
                                    String transformerId,
                                    String auxLoadId,
                                    String newGeneratorId,
                                    int nSamples) {
        MultiBuildResult mr = buildMulti(network,
                List.of(new GeneratorSpec(generatorId, auxLoadId, newGeneratorId)),
                transformerId, nSamples);
        return mr.first();
    }

    /**
     * Build equivalent HV generators for one or more LV-side generators sharing
     * the same transformer. Each spec produces one new HV generator carrying
     * its own transported reactive capability curve and operating point. The
     * original generators, their (distinct) auxiliary loads, and the shared
     * transformer are removed from the network.
     *
     * @param network        the network to modify in place
     * @param specs          one entry per generator to transport; order is preserved
     *                       in the returned {@link MultiBuildResult}
     * @param transformerId  ID of the 2-winding transformer shared by all generators
     * @param nSamples       number of points used to discretize each curve
     */
    public static MultiBuildResult buildMulti(Network network,
                                              List<GeneratorSpec> specs,
                                              String transformerId,
                                              int nSamples) {

        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one generator spec is required");
        }

        TwoWindingsTransformer tx = network.getTwoWindingsTransformer(transformerId);
        if (tx == null) {
            throw new IllegalArgumentException("Transformer not found: " + transformerId);
        }

        // Reject duplicate aux-load IDs: the load would otherwise be subtracted
        // multiple times. Each LV aux load must be assigned to at most one generator.
        Set<String> seenAuxIds = new HashSet<>();
        Set<String> seenNewIds = new HashSet<>();
        Set<String> seenGenIds = new HashSet<>();
        for (GeneratorSpec spec : specs) {
            if (spec.generatorId() == null || spec.newGeneratorId() == null) {
                throw new IllegalArgumentException(
                        "generatorId and newGeneratorId are required on every GeneratorSpec");
            }
            if (!seenGenIds.add(spec.generatorId())) {
                throw new IllegalArgumentException(
                        "Duplicate generator ID across specs: " + spec.generatorId());
            }
            if (!seenNewIds.add(spec.newGeneratorId())) {
                throw new IllegalArgumentException(
                        "Duplicate new generator ID: " + spec.newGeneratorId());
            }
            if (spec.auxLoadId() != null && !seenAuxIds.add(spec.auxLoadId())) {
                throw new IllegalArgumentException(
                        "Duplicate auxiliary load ID across specs: " + spec.auxLoadId()
                                + " (each auxiliary load must be assigned to at most one generator)");
            }
        }

        TransformerTransport.OrientedParams op = TransformerTransport.orientToHv(tx);

        // Transport every generator first - all reads happen before any mutation.
        List<PerGenData> perGen = new ArrayList<>(specs.size());
        for (GeneratorSpec spec : specs) {
            perGen.add(prepare(network, tx, op, spec, nSamples));
        }

        // Capture HV-side topology before the transformer is removed.
        VoltageLevel hvVl = network.getVoltageLevel(op.hvVoltageLevelId());
        if (hvVl == null) {
            throw new IllegalStateException("HV voltage level missing: " + op.hvVoltageLevelId());
        }
        TopologyKind topologyKind = hvVl.getTopologyKind();
        Terminal hvTerm = sameSideTerminal(tx, op);
        String hvBusbarSectionId = null;
        if (topologyKind == TopologyKind.NODE_BREAKER) {
            hvBusbarSectionId = hvVl.getNodeBreakerView().getBusbarSectionStream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No busbar section in HV voltage level: " + hvVl.getId()))
                    .getId();
        }
        String hvBusBreakerBusId = (topologyKind == TopologyKind.BUS_BREAKER)
                ? hvTerm.getBusBreakerView().getBus().getId()
                : null;
        double nomHv = op.nomHvKv();

        // Remove originals: each generator, its aux load (if any), then the shared transformer.
        LOGGER.info("Removing {} original LV generator(s) + aux load(s) + transformer {}",
                specs.size(), transformerId);
        for (GeneratorSpec spec : specs) {
            new RemoveFeederBay(spec.generatorId()).apply(network);
            if (spec.auxLoadId() != null) {
                new RemoveFeederBay(spec.auxLoadId()).apply(network);
            }
        }
        new RemoveFeederBay(transformerId).apply(network);

        // Create each new HV equivalent generator.
        List<BuildResult> results = new ArrayList<>(specs.size());
        int positionOrder = 1;
        for (PerGenData data : perGen) {
            GeneratorAdder adder = hvVl.newGenerator()
                    .setId(data.spec.newGeneratorId())
                    .setMinP(data.pMinEq)
                    .setMaxP(data.pMaxEq)
                    .setTargetP(data.opHv.pHvMw())
                    .setTargetQ(data.opHv.qHvMvar())
                    .setTargetV(nomHv)
                    .setVoltageRegulatorOn(false);

            Generator newGen;
            if (topologyKind == TopologyKind.NODE_BREAKER) {
                new CreateFeederBayBuilder()
                        .withInjectionAdder(adder)
                        .withBusOrBusbarSectionId(hvBusbarSectionId)
                        .withInjectionPositionOrder(positionOrder++)
                        .build()
                        .apply(network);
                newGen = network.getGenerator(data.spec.newGeneratorId());
                if (newGen == null) {
                    throw new IllegalStateException(
                            "CreateFeederBay did not create generator: " + data.spec.newGeneratorId());
                }
            } else {
                adder.setBus(hvBusBreakerBusId).setConnectableBus(hvBusBreakerBusId);
                newGen = adder.add();
            }

            ReactiveCapabilityCurveAdder curveAdder = newGen.newReactiveCapabilityCurve();
            for (CurveTransporter.HvCurvePoint pt : data.curve) {
                curveAdder.beginPoint()
                        .setP(pt.pHv())
                        .setMinQ(pt.minQHv())
                        .setMaxQ(pt.maxQHv())
                        .endPoint();
            }
            curveAdder.add();

            LOGGER.info("Equivalent {} built from {}: P=[{} ; {}] MW, target P={} MW, target Q={} MVar",
                    data.spec.newGeneratorId(), data.spec.generatorId(),
                    data.pMinEq, data.pMaxEq, data.opHv.pHvMw(), data.opHv.qHvMvar());

            results.add(new BuildResult(newGen, data.curve));
        }

        return new MultiBuildResult(results);
    }

    /** Per-generator transport results captured before any network mutation. */
    private record PerGenData(GeneratorSpec spec,
                              List<CurveTransporter.HvCurvePoint> curve,
                              TransformerTransport.HvPoint opHv,
                              double pMinEq,
                              double pMaxEq) { }

    private static PerGenData prepare(Network network,
                                      TwoWindingsTransformer tx,
                                      TransformerTransport.OrientedParams op,
                                      GeneratorSpec spec,
                                      int nSamples) {
        Generator gen = network.getGenerator(spec.generatorId());
        if (gen == null) {
            throw new IllegalArgumentException("Generator not found: " + spec.generatorId());
        }
        Load auxLoad = (spec.auxLoadId() != null) ? network.getLoad(spec.auxLoadId()) : null;
        if (spec.auxLoadId() != null && auxLoad == null) {
            throw new IllegalArgumentException("Auxiliary load not found: " + spec.auxLoadId());
        }

        // Use the generator's regulating setpoint as the LV voltage assumption.
        double vLvKv = gen.getTargetV();
        if (Double.isNaN(vLvKv) || vLvKv <= 0.0) {
            vLvKv = gen.getTerminal().getVoltageLevel().getNominalV();
            LOGGER.warn("Generator {} has no valid targetV - using nominal V_lv = {} kV",
                    spec.generatorId(), vLvKv);
        }

        List<CurveTransporter.HvCurvePoint> curve =
                CurveTransporter.transportCurve(gen, tx, auxLoad, vLvKv, nSamples);

        double pAux = (auxLoad != null) ? auxLoad.getP0() : 0.0;
        double qAux = (auxLoad != null) ? auxLoad.getQ0() : 0.0;
        TransformerTransport.HvPoint opHv = TransformerTransport.transport(
                op, vLvKv, gen.getTargetP() - pAux, gen.getTargetQ() - qAux);

        double pMinEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).min().orElseThrow();
        double pMaxEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).max().orElseThrow();

        return new PerGenData(spec, curve, opHv, pMinEq, pMaxEq);
    }

    /** Return the transformer terminal corresponding to the HV side. */
    private static Terminal sameSideTerminal(TwoWindingsTransformer tx,
                                             TransformerTransport.OrientedParams op) {
        if (tx.getTerminal1().getVoltageLevel().getId().equals(op.hvVoltageLevelId())) {
            return tx.getTerminal1();
        }
        return tx.getTerminal2();
    }
}

