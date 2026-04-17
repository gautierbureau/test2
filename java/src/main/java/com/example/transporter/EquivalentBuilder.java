package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.modification.topology.RemoveFeederBay;
import com.powsybl.iidm.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>For the multi-generator case, two loss-allocation policies are available
 * via {@link LossAllocation}:
 * <ul>
 *   <li>{@link LossAllocation#INDEPENDENT} - each generator is transported as
 *       if it were alone on the LV bus. Series (copper) losses are attributed
 *       to each generator proportionally to {@code |S_i|^2}; shunt (magnetizing)
 *       losses are counted once per generator, so the sum of equivalents
 *       double-counts the shunt. Cross-terms between generators are dropped.
 *   <li>{@link LossAllocation#COMBINED_PROPORTIONAL} - the combined LV injection
 *       is transported once, and the resulting HV losses are split between
 *       generators proportionally to {@code |S_i|^2}. Sum of HV equivalents
 *       equals the exact combined transport; for a single generator the two
 *       policies produce identical results.
 * </ul>
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

    /** Policy for allocating transformer losses between generators. */
    public enum LossAllocation {
        /** Each generator transported independently; losses attributed per-generator. */
        INDEPENDENT,
        /**
         * Combined LV injection transported once; resulting HV losses split
         * between generators proportionally to {@code |S_i|^2}.
         */
        COMBINED_PROPORTIONAL
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
        return buildMulti(network, specs, transformerId, nSamples, LossAllocation.INDEPENDENT);
    }

    /**
     * Variant of {@link #buildMulti(Network, List, String, int)} that lets the
     * caller pick the {@link LossAllocation} policy. The policy only matters
     * when {@code specs.size() > 1}; for a single generator the two policies
     * produce identical results.
     */
    public static MultiBuildResult buildMulti(Network network,
                                              List<GeneratorSpec> specs,
                                              String transformerId,
                                              int nSamples,
                                              LossAllocation lossAllocation) {

        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one generator spec is required");
        }
        if (lossAllocation == null) {
            throw new IllegalArgumentException("lossAllocation must not be null");
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
        List<PerGenData> perGen;
        if (lossAllocation == LossAllocation.COMBINED_PROPORTIONAL) {
            perGen = prepareCombinedProportional(network, tx, op, specs, nSamples);
        } else {
            perGen = new ArrayList<>(specs.size());
            for (GeneratorSpec spec : specs) {
                perGen.add(prepare(network, tx, op, spec, nSamples));
            }
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

    /**
     * Transport all generators jointly: the combined LV injection is transported
     * once per (sample, Q-extremum), and the resulting HV losses are split
     * between generators proportionally to {@code |S_i|^2}. This makes the sum
     * of the equivalent HV injections equal to the exact combined transport.
     */
    private static List<PerGenData> prepareCombinedProportional(
            Network network,
            TwoWindingsTransformer tx,
            TransformerTransport.OrientedParams op,
            List<GeneratorSpec> specs,
            int nSamples) {

        if (nSamples < 2) {
            throw new IllegalArgumentException("nSamples must be >= 2");
        }

        int n = specs.size();
        Generator[] gens = new Generator[n];
        double[] pAux = new double[n];
        double[] qAux = new double[n];
        double[] pMinGen = new double[n];
        double[] pMaxGen = new double[n];
        ReactiveLimits[] limits = new ReactiveLimits[n];

        double vLvKv = Double.NaN;
        for (int g = 0; g < n; g++) {
            GeneratorSpec spec = specs.get(g);
            Generator gen = network.getGenerator(spec.generatorId());
            if (gen == null) {
                throw new IllegalArgumentException("Generator not found: " + spec.generatorId());
            }
            Load aux = (spec.auxLoadId() != null) ? network.getLoad(spec.auxLoadId()) : null;
            if (spec.auxLoadId() != null && aux == null) {
                throw new IllegalArgumentException("Auxiliary load not found: " + spec.auxLoadId());
            }
            gens[g] = gen;
            pAux[g] = (aux != null) ? aux.getP0() : 0.0;
            qAux[g] = (aux != null) ? aux.getQ0() : 0.0;
            pMinGen[g] = gen.getMinP();
            pMaxGen[g] = gen.getMaxP();
            limits[g] = gen.getReactiveLimits();

            // All generators share the same LV bus, so they must regulate the
            // same voltage. Use the first valid targetV; warn on mismatch.
            double vi = gen.getTargetV();
            if (Double.isNaN(vi) || vi <= 0.0) {
                vi = gen.getTerminal().getVoltageLevel().getNominalV();
            }
            if (Double.isNaN(vLvKv)) {
                vLvKv = vi;
            } else if (Math.abs(vi - vLvKv) > 1e-3) {
                LOGGER.warn("Generator {} targetV={} kV disagrees with LV voltage {} kV "
                        + "used for combined transport", spec.generatorId(), vi, vLvKv);
            }
        }

        // Per-generator HV curves, filled as we sweep t over [0, 1].
        List<List<CurveTransporter.HvCurvePoint>> perGenCurves = new ArrayList<>(n);
        for (int g = 0; g < n; g++) {
            perGenCurves.add(new ArrayList<>(nSamples));
        }

        for (int i = 0; i < nSamples; i++) {
            double t = (double) i / (nSamples - 1);

            double[] pGen = new double[n];
            double[] qMinOrig = new double[n];
            double[] qMaxOrig = new double[n];
            double pLvCombined = 0.0;
            double qLvLoCombined = 0.0;
            double qLvHiCombined = 0.0;
            for (int g = 0; g < n; g++) {
                pGen[g] = pMinGen[g] + t * (pMaxGen[g] - pMinGen[g]);
                qMinOrig[g] = limits[g].getMinQ(pGen[g]);
                qMaxOrig[g] = limits[g].getMaxQ(pGen[g]);
                pLvCombined   += pGen[g]      - pAux[g];
                qLvLoCombined += qMinOrig[g]  - qAux[g];
                qLvHiCombined += qMaxOrig[g]  - qAux[g];
            }

            TransformerTransport.HvPoint hvLo = TransformerTransport.transport(
                    op, vLvKv, pLvCombined, qLvLoCombined);
            TransformerTransport.HvPoint hvHi = TransformerTransport.transport(
                    op, vLvKv, pLvCombined, qLvHiCombined);

            double pLossLo = pLvCombined   - hvLo.pHvMw();
            double qLossLo = qLvLoCombined - hvLo.qHvMvar();
            double pLossHi = pLvCombined   - hvHi.pHvMw();
            double qLossHi = qLvHiCombined - hvHi.qHvMvar();

            double[] wLo = computeS2Weights(pGen, qMinOrig, pAux, qAux);
            double[] wHi = computeS2Weights(pGen, qMaxOrig, pAux, qAux);

            for (int g = 0; g < n; g++) {
                double pHvLo = (pGen[g]     - pAux[g]) - wLo[g] * pLossLo;
                double qHvLo = (qMinOrig[g] - qAux[g]) - wLo[g] * qLossLo;
                double pHvHi = (pGen[g]     - pAux[g]) - wHi[g] * pLossHi;
                double qHvHi = (qMaxOrig[g] - qAux[g]) - wHi[g] * qLossHi;

                double pHv = 0.5 * (pHvLo + pHvHi);
                double qMinHv = Math.min(qHvLo, qHvHi);
                double qMaxHv = Math.max(qHvLo, qHvHi);
                perGenCurves.get(g).add(new CurveTransporter.HvCurvePoint(pHv, qMinHv, qMaxHv));
            }
        }

        // Enforce strictly increasing P on each equivalent curve.
        for (List<CurveTransporter.HvCurvePoint> curve : perGenCurves) {
            curve.sort(Comparator.comparingDouble(CurveTransporter.HvCurvePoint::pHv));
            for (int i = 1; i < curve.size(); i++) {
                CurveTransporter.HvCurvePoint prev = curve.get(i - 1);
                CurveTransporter.HvCurvePoint cur = curve.get(i);
                if (cur.pHv() - prev.pHv() < 1e-6) {
                    curve.set(i, new CurveTransporter.HvCurvePoint(
                            prev.pHv() + 1e-6, cur.minQHv(), cur.maxQHv()));
                }
            }
        }

        // Transport the combined operating point and split it the same way.
        double pLvOp = 0.0;
        double qLvOp = 0.0;
        double[] pGenOp = new double[n];
        double[] qGenOp = new double[n];
        for (int g = 0; g < n; g++) {
            pGenOp[g] = gens[g].getTargetP();
            qGenOp[g] = gens[g].getTargetQ();
            pLvOp += pGenOp[g] - pAux[g];
            qLvOp += qGenOp[g] - qAux[g];
        }
        TransformerTransport.HvPoint hvOp = TransformerTransport.transport(op, vLvKv, pLvOp, qLvOp);
        double pLossOp = pLvOp - hvOp.pHvMw();
        double qLossOp = qLvOp - hvOp.qHvMvar();
        double[] wOp = computeS2Weights(pGenOp, qGenOp, pAux, qAux);

        List<PerGenData> perGen = new ArrayList<>(n);
        for (int g = 0; g < n; g++) {
            List<CurveTransporter.HvCurvePoint> curve = perGenCurves.get(g);
            TransformerTransport.HvPoint opHv = new TransformerTransport.HvPoint(
                    (pGenOp[g] - pAux[g]) - wOp[g] * pLossOp,
                    (qGenOp[g] - qAux[g]) - wOp[g] * qLossOp,
                    hvOp.vHvKv());
            double pMinEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).min().orElseThrow();
            double pMaxEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).max().orElseThrow();
            perGen.add(new PerGenData(specs.get(g), curve, opHv, pMinEq, pMaxEq));
        }
        return perGen;
    }

    /**
     * Compute split weights proportional to {@code |S_i|^2} where
     * {@code S_i = (P_i - P_aux_i) + j(Q_i - Q_aux_i)}. The returned weights
     * sum to 1. If every {@code |S_i|^2} is zero, the weight falls back to a
     * uniform {@code 1/n} split.
     */
    private static double[] computeS2Weights(double[] pGen, double[] qGen,
                                             double[] pAux, double[] qAux) {
        int n = pGen.length;
        double[] w = new double[n];
        double sum = 0.0;
        for (int g = 0; g < n; g++) {
            double p = pGen[g] - pAux[g];
            double q = qGen[g] - qAux[g];
            w[g] = p * p + q * q;
            sum += w[g];
        }
        if (sum < 1e-12) {
            for (int g = 0; g < n; g++) {
                w[g] = 1.0 / n;
            }
            return w;
        }
        for (int g = 0; g < n; g++) {
            w[g] /= sum;
        }
        return w;
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

