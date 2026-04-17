package com.example.transporter;

import com.powsybl.iidm.modification.topology.CreateFeederBayBuilder;
import com.powsybl.iidm.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Modify a network in place to replace
 * {@code (LV-generator + auxiliary load + transformer)} by a single
 * equivalent generator on the HV bus carrying the transported reactive
 * capability curve and the transported (P, Q) operating point.
 *
 * <p>Both bus-breaker and node-breaker topologies are supported on the HV
 * voltage level. For node-breaker, the equivalent generator is connected to
 * the same node the transformer's HV terminal used; for bus-breaker, to the
 * same bus.
 */
public final class EquivalentBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EquivalentBuilder.class);

    private EquivalentBuilder() {
        // Static utility
    }

    public record BuildResult(Generator equivalentGenerator,
                              List<CurveTransporter.HvCurvePoint> curve) { }

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

        Generator gen = network.getGenerator(generatorId);
        if (gen == null) {
            throw new IllegalArgumentException("Generator not found: " + generatorId);
        }
        TwoWindingsTransformer tx = network.getTwoWindingsTransformer(transformerId);
        if (tx == null) {
            throw new IllegalArgumentException("Transformer not found: " + transformerId);
        }
        Load auxLoad = (auxLoadId != null) ? network.getLoad(auxLoadId) : null;
        if (auxLoadId != null && auxLoad == null) {
            throw new IllegalArgumentException("Auxiliary load not found: " + auxLoadId);
        }

        // Use the generator's regulating setpoint as the LV voltage assumption
        double vLvKv = gen.getTargetV();
        if (Double.isNaN(vLvKv) || vLvKv <= 0.0) {
            // Fallback to LV nominal voltage if no setpoint
            vLvKv = gen.getTerminal().getVoltageLevel().getNominalV();
            LOGGER.warn("Generator {} has no valid targetV - using nominal V_lv = {} kV",
                    generatorId, vLvKv);
        }

        // Sample the transported curve
        List<CurveTransporter.HvCurvePoint> curve =
                CurveTransporter.transportCurve(gen, tx, auxLoad, vLvKv, nSamples);

        // Transport the original operating point (P_target, Q_target)
        TransformerTransport.OrientedParams op =
                TransformerTransport.orientToHv(tx);
        double pAux = (auxLoad != null) ? auxLoad.getP0() : 0.0;
        double qAux = (auxLoad != null) ? auxLoad.getQ0() : 0.0;
        double pTarget = gen.getTargetP();
        double qTarget = gen.getTargetQ();
        TransformerTransport.HvPoint opHv = TransformerTransport.transport(
                op, vLvKv, pTarget - pAux, qTarget - qAux);

        // Capture the HV-side topology BEFORE we remove the transformer.
        VoltageLevel hvVl = network.getVoltageLevel(op.hvVoltageLevelId());
        if (hvVl == null) {
            throw new IllegalStateException("HV voltage level missing: " + op.hvVoltageLevelId());
        }
        TopologyKind topologyKind = hvVl.getTopologyKind();
        Terminal hvTerm = sameSideTerminal(tx, op);
        // For node-breaker: identify the busbar section to connect the new
        // generator to via CreateFeederBay (adds a proper disconnector+breaker bay).
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

        // Compute pMin/pMax from the curve (HV side)
        double pMinEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).min()
                .orElseThrow();
        double pMaxEq = curve.stream().mapToDouble(CurveTransporter.HvCurvePoint::pHv).max()
                .orElseThrow();

        LOGGER.info("Removing original LV-side equipment");
        gen.remove();
        if (auxLoad != null) {
            auxLoad.remove();
        }
        tx.remove();

        LOGGER.info("Creating equivalent generator {} on voltage level {} (V_nom={} kV)",
                newGeneratorId, hvVl.getId(), nomHv);

        GeneratorAdder adder = hvVl.newGenerator()
                .setId(newGeneratorId)
                .setMinP(pMinEq)
                .setMaxP(pMaxEq)
                .setTargetP(opHv.pHvMw())
                .setTargetQ(opHv.qHvMvar())
                .setTargetV(nomHv)
                .setVoltageRegulatorOn(false);

        Generator newGen;
        if (topologyKind == TopologyKind.NODE_BREAKER) {
            // Use CreateFeederBay to wire the generator with a proper
            // disconnector + breaker bay attached to the busbar section.
            new CreateFeederBayBuilder()
                    .withInjectionAdder(adder)
                    .withBusOrBusbarSectionId(hvBusbarSectionId)
                    .withInjectionPositionOrder(1)
                    .build()
                    .apply(network);
            newGen = network.getGenerator(newGeneratorId);
            if (newGen == null) {
                throw new IllegalStateException(
                        "CreateFeederBay did not create generator: " + newGeneratorId);
            }
        } else {
            adder.setBus(hvBusBreakerBusId)
                 .setConnectableBus(hvBusBreakerBusId);
            newGen = adder.add();
        }

        // Attach the transported reactive capability curve
        ReactiveCapabilityCurveAdder curveAdder = newGen.newReactiveCapabilityCurve();
        for (CurveTransporter.HvCurvePoint pt : curve) {
            curveAdder.beginPoint()
                    .setP(pt.pHv())
                    .setMinQ(pt.minQHv())
                    .setMaxQ(pt.maxQHv())
                    .endPoint();
        }
        curveAdder.add();

        LOGGER.info("Equivalent built: P=[{} ; {}] MW, target P={} MW, target Q={} MVar",
                pMinEq, pMaxEq, opHv.pHvMw(), opHv.qHvMvar());

        return new BuildResult(newGen, curve);
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

