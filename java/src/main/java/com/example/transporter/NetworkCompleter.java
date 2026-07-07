package com.example.transporter;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.RatioTapChangerAdder;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.ReactiveLimitsKind;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;

/**
 * Fill in network data that a case is missing, from a load flow.
 *
 * <p>Two independent completions, both driven by an AC load flow so the base
 * case is preserved and the additions are physically grounded — the Java port
 * of the Python {@code complete_network.py} module.
 *
 * <ul>
 *   <li>{@link #addReactiveLimits} gives generators a finite MIN_MAX reactive
 *   band when they have none or carry a placeholder "infinite" one (the
 *   {@code |Q| >= 1e4} values MATPOWER/PEGASE use for "unlimited"). The band is
 *   sized as {@code Q = sqrt(ratedS^2 - P^2)} when a rated apparent power is
 *   known, otherwise from a power factor applied to the active power.</li>
 *   <li>{@link #addRatioTapChangers} gives two-winding transformers a
 *   voltage-regulating ratio tap changer when they have none: symmetric steps
 *   around {@code rho = 1} with the tap at neutral, regulating the side-2
 *   voltage to its base-case value. At the neutral tap the transformer is
 *   electrically identical to before, so the base case is unchanged.</li>
 * </ul>
 *
 * <p>Phase tap changers are deliberately not synthesized: a phase shifter is a
 * specific physical device, and cases that use them already carry them.
 */
public final class NetworkCompleter {

    public static final double DEFAULT_POWER_FACTOR = 0.95;
    public static final double DEFAULT_PLACEHOLDER_THRESHOLD = 1e4;
    public static final int DEFAULT_RTC_STEPS_PER_SIDE = 8;
    public static final double DEFAULT_RTC_STEP_INCREMENT = 0.0125;
    public static final EnergySource DEFAULT_ENERGY_SOURCE = EnergySource.THERMAL;
    public static final double DEFAULT_DROOP = 4.0;

    private NetworkCompleter() {
    }

    // -----------------------------------------------------------------------
    // Reactive limits
    // -----------------------------------------------------------------------

    public record ReactiveConfig(double powerFactor, double placeholderThreshold,
                                 boolean onlyMissing, boolean runLoadFlow) {

        public static ReactiveConfig defaults() {
            return new ReactiveConfig(DEFAULT_POWER_FACTOR, DEFAULT_PLACEHOLDER_THRESHOLD,
                    true, true);
        }

        public ReactiveConfig withPowerFactor(double pf) {
            return new ReactiveConfig(pf, placeholderThreshold, onlyMissing, runLoadFlow);
        }

        public ReactiveConfig withOnlyMissing(boolean b) {
            return new ReactiveConfig(powerFactor, placeholderThreshold, b, runLoadFlow);
        }

        public ReactiveConfig withRunLoadFlow(boolean b) {
            return new ReactiveConfig(powerFactor, placeholderThreshold, onlyMissing, b);
        }
    }

    public record ReactiveStats(int generators, int filled, int skippedExisting,
                                int skippedNoSize) {
    }

    /** Give generators a finite reactive band where they lack a real one. */
    public static ReactiveStats addReactiveLimits(Network network, ReactiveConfig cfg) {
        if (!(cfg.powerFactor() > 0 && cfg.powerFactor() <= 1)) {
            throw new IllegalArgumentException("powerFactor must be in (0, 1]");
        }
        if (cfg.runLoadFlow()) {
            runAcOrThrow(network);
        }
        int generators = 0;
        int filled = 0;
        int skippedExisting = 0;
        int skippedNoSize = 0;
        for (Generator g : network.getGenerators()) {
            generators++;
            if (cfg.onlyMissing() && !needsReactiveLimits(g, cfg.placeholderThreshold())) {
                skippedExisting++;
                continue;
            }
            double q = sizedReactive(g, cfg.powerFactor(), cfg.placeholderThreshold());
            if (!Double.isFinite(q)) {
                skippedNoSize++;
                continue;
            }
            g.newMinMaxReactiveLimits().setMinQ(-q).setMaxQ(q).add();
            filled++;
        }
        return new ReactiveStats(generators, filled, skippedExisting, skippedNoSize);
    }

    private static boolean needsReactiveLimits(Generator g, double threshold) {
        ReactiveLimits rl = g.getReactiveLimits();
        if (rl == null) {
            return true;
        }
        if (rl.getKind() == ReactiveLimitsKind.CURVE) {
            return false;
        }
        if (rl.getKind() != ReactiveLimitsKind.MIN_MAX) {
            return true;
        }
        MinMaxReactiveLimits mm = g.getReactiveLimits(MinMaxReactiveLimits.class);
        double minQ = mm.getMinQ();
        double maxQ = mm.getMaxQ();
        if (!(Double.isFinite(minQ) && Double.isFinite(maxQ))) {
            return true;
        }
        return Math.abs(minQ) >= threshold || Math.abs(maxQ) >= threshold;
    }

    private static double sizedReactive(Generator g, double powerFactor, double threshold) {
        double p = g.getMaxP();
        if (!(Double.isFinite(p) && Math.abs(p) < threshold)) {
            p = Double.isFinite(g.getTargetP()) ? g.getTargetP() : 0.0;
        }
        double s = g.getRatedS();
        if (Double.isFinite(s) && s > 0) {
            double q = Math.sqrt(Math.max(s * s - p * p, 0.0));
            if (q > 0) {
                return q;
            }
        }
        if (Double.isFinite(p) && p != 0) {
            double q = Math.abs(p) * Math.tan(Math.acos(powerFactor));
            if (q > 0) {  // unity power factor gives no reactive band -> leave unsized
                return q;
            }
        }
        return Double.NaN;
    }

    // -----------------------------------------------------------------------
    // Ratio tap changers
    // -----------------------------------------------------------------------

    public record RatioTapConfig(int stepsPerSide, double stepIncrement, double targetDeadband,
                                 boolean regulating, boolean onlyMissing, boolean runLoadFlow) {

        public static RatioTapConfig defaults() {
            return new RatioTapConfig(DEFAULT_RTC_STEPS_PER_SIDE, DEFAULT_RTC_STEP_INCREMENT,
                    0.0, true, true, true);
        }

        public RatioTapConfig withStepsPerSide(int n) {
            return new RatioTapConfig(n, stepIncrement, targetDeadband, regulating, onlyMissing, runLoadFlow);
        }

        public RatioTapConfig withStepIncrement(double inc) {
            return new RatioTapConfig(stepsPerSide, inc, targetDeadband, regulating, onlyMissing, runLoadFlow);
        }

        public RatioTapConfig withRegulating(boolean b) {
            return new RatioTapConfig(stepsPerSide, stepIncrement, targetDeadband, b, onlyMissing, runLoadFlow);
        }

        public RatioTapConfig withOnlyMissing(boolean b) {
            return new RatioTapConfig(stepsPerSide, stepIncrement, targetDeadband, regulating, b, runLoadFlow);
        }

        public RatioTapConfig withRunLoadFlow(boolean b) {
            return new RatioTapConfig(stepsPerSide, stepIncrement, targetDeadband, regulating, onlyMissing, b);
        }
    }

    public record RatioTapStats(int transformers, int added, int skippedExisting,
                                int skippedNoVoltage) {
    }

    /** Give two-winding transformers a voltage-regulating ratio tap changer. */
    public static RatioTapStats addRatioTapChangers(Network network, RatioTapConfig cfg) {
        if (cfg.stepsPerSide() < 1) {
            throw new IllegalArgumentException("stepsPerSide must be >= 1");
        }
        if (!(cfg.stepIncrement() > 0)) {
            throw new IllegalArgumentException("stepIncrement must be positive");
        }
        if (cfg.runLoadFlow()) {
            runAcOrThrow(network);
        }
        int neutral = cfg.stepsPerSide();
        int transformers = 0;
        int added = 0;
        int skippedExisting = 0;
        int skippedNoVoltage = 0;
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            transformers++;
            if (cfg.onlyMissing() && (tx.hasRatioTapChanger() || tx.hasPhaseTapChanger())) {
                skippedExisting++;
                continue;
            }
            double targetV = side2Voltage(tx);
            if (!(Double.isFinite(targetV) && targetV > 0)) {
                skippedNoVoltage++;
                continue;
            }
            RatioTapChangerAdder adder = tx.newRatioTapChanger()
                    .setLowTapPosition(0)
                    .setTapPosition(neutral)
                    .setLoadTapChangingCapabilities(true)
                    .setRegulationTerminal(tx.getTerminal2())
                    .setTargetDeadband(cfg.targetDeadband());
            for (int k = 0; k <= 2 * cfg.stepsPerSide(); k++) {
                double rho = 1.0 + (k - neutral) * cfg.stepIncrement();
                adder.beginStep().setRho(rho).setR(0).setX(0).setG(0).setB(0).endStep();
            }
            // Always store the base-case setpoint so a later flip to regulating
            // has a valid target (matches the Python module).
            adder.setRegulationMode(RatioTapChanger.RegulationMode.VOLTAGE)
                    .setTargetV(targetV)
                    .setRegulating(cfg.regulating());
            adder.add();
            added++;
        }
        return new RatioTapStats(transformers, added, skippedExisting, skippedNoVoltage);
    }

    private static double side2Voltage(TwoWindingsTransformer tx) {
        Bus bus = tx.getTerminal2().getBusView().getBus();
        return bus == null ? Double.NaN : bus.getV();
    }

    // -----------------------------------------------------------------------
    // Generator energy source
    // -----------------------------------------------------------------------

    public record EnergySourceStats(int generators, int set, int skippedDefined) {
    }

    /**
     * Assign an energy source to generators that carry none. A load flow cannot
     * infer fuel type, so this is a blanket default: generators whose source is
     * {@link EnergySource#OTHER} (the IIDM "unset" value) are set to
     * {@code source}. With {@code onlyUndefined = false} every generator is set.
     */
    public static EnergySourceStats setGeneratorEnergySource(Network network,
                                                             EnergySource source,
                                                             boolean onlyUndefined) {
        int generators = 0;
        int set = 0;
        int skippedDefined = 0;
        for (Generator g : network.getGenerators()) {
            generators++;
            if (onlyUndefined && g.getEnergySource() != EnergySource.OTHER) {
                skippedDefined++;
                continue;
            }
            g.setEnergySource(source);
            set++;
        }
        return new EnergySourceStats(generators, set, skippedDefined);
    }

    // -----------------------------------------------------------------------
    // Active power control (participation factors)
    // -----------------------------------------------------------------------

    public record ActivePowerControlStats(int generators, int added, int skippedExisting) {
    }

    /**
     * Give generators an active-power-control participation factor. Sets the
     * {@code ActivePowerControl} extension with participation enabled and a
     * factor proportional to the generator's active-power capability
     * ({@code maxP}, falling back to {@code targetP} then 1), so distributed
     * slack / redispatch has something to act on. Generators that already carry
     * the extension are left untouched when {@code onlyMissing}.
     */
    public static ActivePowerControlStats addActivePowerControl(Network network,
                                                                double droop,
                                                                boolean onlyMissing) {
        if (!(droop > 0)) {
            throw new IllegalArgumentException("droop must be positive");
        }
        int generators = 0;
        int added = 0;
        int skippedExisting = 0;
        for (Generator g : network.getGenerators()) {
            generators++;
            if (onlyMissing && g.getExtension(ActivePowerControl.class) != null) {
                skippedExisting++;
                continue;
            }
            g.newExtension(ActivePowerControlAdder.class)
                    .withParticipate(true)
                    .withDroop(droop)
                    .withParticipationFactor(participationFactor(g))
                    .add();
            added++;
        }
        return new ActivePowerControlStats(generators, added, skippedExisting);
    }

    private static double participationFactor(Generator g) {
        for (double value : new double[]{g.getMaxP(), g.getTargetP()}) {
            if (Double.isFinite(value) && value > 0) {
                return value;
            }
        }
        return 1.0;
    }

    // -----------------------------------------------------------------------
    // Load flow
    // -----------------------------------------------------------------------

    static void runAcOrThrow(Network network) {
        // Flat start first, then a DC-based start, which converges large cases
        // where a flat start does not.
        LoadFlowResult result = null;
        for (LoadFlowParameters.VoltageInitMode init : new LoadFlowParameters.VoltageInitMode[]{
                LoadFlowParameters.VoltageInitMode.UNIFORM_VALUES,
                LoadFlowParameters.VoltageInitMode.DC_VALUES}) {
            LoadFlowParameters params = new LoadFlowParameters()
                    .setUseReactiveLimits(true)
                    .setTransformerVoltageControlOn(false)
                    .setDistributedSlack(true)
                    .setVoltageInitMode(init);
            result = LoadFlow.run(network, params);
            if (result.isFullyConverged()) {
                return;
            }
        }
        throw new IllegalStateException("load flow did not converge: "
                + (result == null ? "no attempt" : result.getStatus()));
    }
}
