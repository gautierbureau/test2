package com.example.transporter;

import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.RatioTapChangerAdder;
import com.powsybl.iidm.network.ReactiveCapabilityCurveAdder;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.ReactiveLimitsKind;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.BranchObservability;
import com.powsybl.iidm.network.extensions.BranchObservabilityAdder;
import com.powsybl.iidm.network.extensions.DiscreteMeasurement;
import com.powsybl.iidm.network.extensions.DiscreteMeasurements;
import com.powsybl.iidm.network.extensions.DiscreteMeasurementsAdder;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuit;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuitAdder;
import com.powsybl.iidm.network.extensions.IdentifiableShortCircuit;
import com.powsybl.iidm.network.extensions.IdentifiableShortCircuitAdder;
import com.powsybl.iidm.network.extensions.InjectionObservability;
import com.powsybl.iidm.network.extensions.InjectionObservabilityAdder;
import com.powsybl.iidm.network.extensions.LoadDetail;
import com.powsybl.iidm.network.extensions.LoadDetailAdder;
import com.powsybl.iidm.network.extensions.Measurement;
import com.powsybl.iidm.network.extensions.Measurements;
import com.powsybl.iidm.network.extensions.MeasurementsAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static final double DEFAULT_DROOP = 4.0;
    public static final double DEFAULT_GENERATOR_POWER_FACTOR = 0.85;
    public static final double DEFAULT_TRANSFORMER_LOADING = 0.6;
    public static final double DEFAULT_MEASUREMENT_STD_DEV = 0.01;
    public static final double DEFAULT_STD_DEV_FLOOR = 0.1;
    public static final double DEFAULT_OBSERVABILITY_STD_DEV = 1.0;

    /** Representative European installed-capacity split (shares need not sum to 1). */
    public static final Map<EnergySource, Double> DEFAULT_GENERATION_MIX = Map.of(
            EnergySource.NUCLEAR, 0.15, EnergySource.THERMAL, 0.35, EnergySource.HYDRO, 0.20,
            EnergySource.WIND, 0.20, EnergySource.SOLAR, 0.10);
    /** Order the mix is laid down in, from the largest units to the smallest. */
    private static final List<EnergySource> MIX_ORDER = List.of(
            EnergySource.NUCLEAR, EnergySource.THERMAL, EnergySource.HYDRO,
            EnergySource.WIND, EnergySource.SOLAR, EnergySource.OTHER);

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
    // Generation mix (energy sources)
    // -----------------------------------------------------------------------

    public record GenerationMixStats(int generators, int assigned, int skippedDefined,
                                     Map<EnergySource, Integer> bySource) {
    }

    /**
     * Assign a realistic energy-source mix across the fleet. Fuel type cannot be
     * inferred from a load flow, so this lays down a representative
     * installed-capacity distribution: generators are ranked by active-power
     * capability and the largest units get the base-load sources (nuclear,
     * thermal), the smallest the intermittent ones (wind, solar), so the shares
     * in {@code mix} are met by capacity. Deterministic. With
     * {@code onlyUndefined} only {@link EnergySource#OTHER} generators are set.
     */
    public static GenerationMixStats setGenerationMix(Network network,
                                                      Map<EnergySource, Double> mix,
                                                      boolean onlyUndefined) {
        if (mix.isEmpty()) {
            throw new IllegalArgumentException("mix must not be empty");
        }
        for (double weight : mix.values()) {
            if (!(weight > 0)) {
                throw new IllegalArgumentException("mix weights must be positive");
            }
        }
        int generators = 0;
        int skippedDefined = 0;
        List<Generator> candidates = new ArrayList<>();
        for (Generator g : network.getGenerators()) {
            generators++;
            if (onlyUndefined && g.getEnergySource() != EnergySource.OTHER) {
                skippedDefined++;
            } else {
                candidates.add(g);
            }
        }
        Map<EnergySource, Integer> bySource = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            return new GenerationMixStats(generators, 0, skippedDefined, bySource);
        }

        List<EnergySource> sources = new ArrayList<>();
        for (EnergySource s : MIX_ORDER) {
            if (mix.containsKey(s)) {
                sources.add(s);
            }
        }
        for (EnergySource s : mix.keySet()) {
            if (!sources.contains(s)) {
                sources.add(s);
            }
        }
        double totalWeight = mix.values().stream().mapToDouble(Double::doubleValue).sum();

        Map<Generator, Double> caps = new java.util.HashMap<>();
        double totalCap = 0.0;
        for (Generator g : candidates) {
            double c = genCapacity(g);
            caps.put(g, c);
            totalCap += c;
        }
        candidates.sort(Comparator.comparingDouble((Generator g) -> caps.get(g)).reversed()
                .thenComparing(Generator::getId));

        double scale = totalCap > 0 ? totalCap : candidates.size();
        List<Map.Entry<EnergySource, Double>> boundaries = new ArrayList<>();
        double running = 0.0;
        for (EnergySource s : sources) {
            running += mix.get(s) / totalWeight;
            boundaries.add(Map.entry(s, running * scale));
        }

        int assigned = 0;
        double cum = 0.0;
        int cursor = 0;
        for (Generator g : candidates) {
            cum += totalCap > 0 ? caps.get(g) : 1.0;
            while (cursor < boundaries.size() - 1 && cum > boundaries.get(cursor).getValue()) {
                cursor++;
            }
            EnergySource s = boundaries.get(cursor).getKey();
            g.setEnergySource(s);
            bySource.merge(s, 1, Integer::sum);
            assigned++;
        }
        return new GenerationMixStats(generators, assigned, skippedDefined, bySource);
    }

    private static double genCapacity(Generator g) {
        double p = g.getMaxP();
        if (!(Double.isFinite(p) && Math.abs(p) > 0 && Math.abs(p) < DEFAULT_PLACEHOLDER_THRESHOLD)) {
            p = g.getTargetP();
        }
        return (Double.isFinite(p) && p > 0) ? Math.abs(p) : 0.0;
    }

    // -----------------------------------------------------------------------
    // Apparent power ratings (rated S)
    // -----------------------------------------------------------------------

    public record RatedSStats(int generators, int generatorsSet, int generatorsSkippedExisting,
                              int generatorsUnsizable, int transformers, int transformersSet,
                              int transformersSkippedExisting, int transformersNoFlow) {
    }

    /**
     * Give generators and two-winding transformers an apparent-power rating where
     * missing. Generators: {@code ratedS = |P| / generatorPowerFactor};
     * transformers: {@code ratedS = base-case apparent flow / transformerLoading}.
     */
    public static RatedSStats addRatedS(Network network, double generatorPowerFactor,
                                        double transformerLoading, boolean onlyMissing,
                                        boolean runLoadFlow) {
        if (!(generatorPowerFactor > 0 && generatorPowerFactor <= 1)) {
            throw new IllegalArgumentException("generatorPowerFactor must be in (0, 1]");
        }
        if (!(transformerLoading > 0 && transformerLoading <= 1)) {
            throw new IllegalArgumentException("transformerLoading must be in (0, 1]");
        }
        if (runLoadFlow) {
            runAcOrThrow(network);
        }
        int g = 0;
        int gSet = 0;
        int gSkip = 0;
        int gUns = 0;
        for (Generator gen : network.getGenerators()) {
            g++;
            if (onlyMissing && Double.isFinite(gen.getRatedS()) && gen.getRatedS() > 0) {
                gSkip++;
                continue;
            }
            double capacity = genCapacity(gen);
            if (capacity <= 0) {
                gUns++;
                continue;
            }
            gen.setRatedS(capacity / generatorPowerFactor);
            gSet++;
        }
        int t = 0;
        int tSet = 0;
        int tSkip = 0;
        int tNo = 0;
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            t++;
            if (onlyMissing && Double.isFinite(tx.getRatedS()) && tx.getRatedS() > 0) {
                tSkip++;
                continue;
            }
            double sBase = apparentFlow(tx);
            if (!(sBase > 0)) {
                tNo++;
                continue;
            }
            tx.setRatedS(sBase / transformerLoading);
            tSet++;
        }
        return new RatedSStats(g, gSet, gSkip, gUns, t, tSet, tSkip, tNo);
    }

    private static double apparentFlow(TwoWindingsTransformer tx) {
        double best = 0.0;
        for (Terminal term : new Terminal[]{tx.getTerminal1(), tx.getTerminal2()}) {
            double p = term.getP();
            double q = term.getQ();
            if (Double.isFinite(p) && Double.isFinite(q)) {
                best = Math.max(best, Math.hypot(p, q));
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // Synthetic measurements and observability (state-estimation input)
    // -----------------------------------------------------------------------

    public record MeasurementsStats(int measurements, int elements, int skippedExisting) {
    }

    /**
     * Attach synthetic analog measurements taken from a load flow: active/reactive
     * power on every generator and load, and active/reactive power and current on
     * every line and transformer side. Values are the exact load-flow results;
     * each carries a standard deviation of {@code max(|value| * relativeStdDev,
     * stdDevFloor)}.
     */
    public static MeasurementsStats addMeasurements(Network network, double relativeStdDev,
                                                    double stdDevFloor, boolean onlyMissing,
                                                    boolean runLoadFlow) {
        if (!(relativeStdDev >= 0)) {
            throw new IllegalArgumentException("relativeStdDev must be >= 0");
        }
        if (runLoadFlow) {
            runAcOrThrow(network);
        }
        int[] tally = {0, 0, 0};  // measurements, elements, skippedExisting
        for (Generator gen : network.getGenerators()) {
            addInjectionMeasurements(gen, relativeStdDev, stdDevFloor, onlyMissing, tally);
        }
        for (Load load : network.getLoads()) {
            addInjectionMeasurements(load, relativeStdDev, stdDevFloor, onlyMissing, tally);
        }
        for (Line line : network.getLines()) {
            addBranchMeasurements(line, relativeStdDev, stdDevFloor, onlyMissing, tally);
        }
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            addBranchMeasurements(tx, relativeStdDev, stdDevFloor, onlyMissing, tally);
        }
        return new MeasurementsStats(tally[0], tally[1], tally[2]);
    }

    private static <I extends Injection<I>> void addInjectionMeasurements(I inj, double relativeStdDev,
                                                 double stdDevFloor, boolean onlyMissing,
                                                 int[] tally) {
        if (onlyMissing && inj.getExtension(Measurements.class) != null) {
            tally[2]++;
            return;
        }
        Terminal term = inj.getTerminal();
        Measurements<?> meas = getOrCreateMeasurements(inj);
        boolean any = false;
        any |= addMeasurement(meas, inj.getId(), Measurement.Type.ACTIVE_POWER, null,
                term.getP(), relativeStdDev, stdDevFloor, tally);
        any |= addMeasurement(meas, inj.getId(), Measurement.Type.REACTIVE_POWER, null,
                term.getQ(), relativeStdDev, stdDevFloor, tally);
        if (any) {
            tally[1]++;
        }
    }

    private static <T extends Branch<T> & Connectable<T>> void addBranchMeasurements(T branch,
                                              double relativeStdDev,
                                              double stdDevFloor, boolean onlyMissing,
                                              int[] tally) {
        if (onlyMissing && branch.getExtension(Measurements.class) != null) {
            tally[2]++;
            return;
        }
        Measurements<?> meas = getOrCreateMeasurements(branch);
        boolean any = false;
        for (ThreeSides side : new ThreeSides[]{ThreeSides.ONE, ThreeSides.TWO}) {
            Terminal term = side == ThreeSides.ONE ? branch.getTerminal1() : branch.getTerminal2();
            String id = branch.getId();
            any |= addMeasurement(meas, id, Measurement.Type.ACTIVE_POWER, side,
                    term.getP(), relativeStdDev, stdDevFloor, tally);
            any |= addMeasurement(meas, id, Measurement.Type.REACTIVE_POWER, side,
                    term.getQ(), relativeStdDev, stdDevFloor, tally);
            any |= addMeasurement(meas, id, Measurement.Type.CURRENT, side,
                    term.getI(), relativeStdDev, stdDevFloor, tally);
        }
        if (any) {
            tally[1]++;
        }
    }

    @SuppressWarnings("unchecked")
    private static <C extends Connectable<C>> Measurements<C> getOrCreateMeasurements(C c) {
        Measurements<C> meas = c.getExtension(Measurements.class);
        if (meas != null) {
            return meas;
        }
        return (Measurements<C>) c.newExtension(MeasurementsAdder.class).add();
    }

    private static boolean addMeasurement(Measurements<?> meas, String elementId,
                                          Measurement.Type type, ThreeSides side, double value,
                                          double relativeStdDev, double stdDevFloor, int[] tally) {
        if (!Double.isFinite(value)) {
            return false;
        }
        double std = Math.max(Math.abs(value) * relativeStdDev, stdDevFloor);
        String id = elementId + "_" + type + (side != null ? "_" + side : "");
        var adder = meas.newMeasurement().setId(id).setType(type)
                .setValue(value).setStandardDeviation(std).setValid(true);
        if (side != null) {
            adder.setSide(side);
        }
        adder.add();
        tally[0]++;
        return true;
    }

    public record ObservabilityStats(int injections, int branches) {
    }

    /** Mark injections and branches observable, with a per-quantity standard deviation. */
    public static ObservabilityStats addObservability(Network network, double stdDev,
                                                      boolean onlyMissing) {
        int injections = 0;
        for (Generator gen : network.getGenerators()) {
            injections += addInjectionObservability(gen, stdDev, onlyMissing);
        }
        for (Load load : network.getLoads()) {
            injections += addInjectionObservability(load, stdDev, onlyMissing);
        }
        int branches = 0;
        for (Line line : network.getLines()) {
            branches += addBranchObservability(line, stdDev, onlyMissing);
        }
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            branches += addBranchObservability(tx, stdDev, onlyMissing);
        }
        return new ObservabilityStats(injections, branches);
    }

    private static int addInjectionObservability(Injection<?> inj, double stdDev, boolean onlyMissing) {
        if (onlyMissing && inj.getExtension(InjectionObservability.class) != null) {
            return 0;
        }
        inj.newExtension(InjectionObservabilityAdder.class).withObservable(true)
                .withStandardDeviationP(stdDev).withRedundantP(false)
                .withStandardDeviationQ(stdDev).withRedundantQ(false)
                .withStandardDeviationV(stdDev).withRedundantV(false).add();
        return 1;
    }

    private static int addBranchObservability(Branch<?> branch, double stdDev, boolean onlyMissing) {
        if (onlyMissing && branch.getExtension(BranchObservability.class) != null) {
            return 0;
        }
        branch.newExtension(BranchObservabilityAdder.class).withObservable(true)
                .withStandardDeviationP1(stdDev).withRedundantP1(false)
                .withStandardDeviationP2(stdDev).withRedundantP2(false)
                .withStandardDeviationQ1(stdDev).withRedundantQ1(false)
                .withStandardDeviationQ2(stdDev).withRedundantQ2(false).add();
        return 1;
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
    // Reactive capability curves
    // -----------------------------------------------------------------------

    public static final int DEFAULT_CURVE_POINTS = 3;
    public static final double DEFAULT_CURVE_MIN_Q_FRACTION = 0.2;

    public record ReactiveCurveStats(int generators, int set) {
    }

    /**
     * Give generators a P-dependent reactive capability curve (the classic
     * "D-curve") instead of a flat MIN_MAX band: a half-band of
     * {@code sqrt(ratedS^2 - P^2)} (the armature-current circle, floored at
     * {@code DEFAULT_CURVE_MIN_Q_FRACTION * ratedS}) sampled at {@code points}
     * active-power values from 0 to maxP, never narrower than the generator's
     * existing band. Needs rated_s (run {@link #addRatedS} first); generators
     * without a usable rating or maxP are left as they are, and with
     * {@code onlyMissing} those already using a curve are skipped.
     */
    public static ReactiveCurveStats addReactiveCapabilityCurves(Network network, int points,
                                                                 boolean onlyMissing) {
        if (points < 2) {
            throw new IllegalArgumentException("points must be >= 2");
        }
        int considered = 0;
        int set = 0;
        for (Generator g : network.getGenerators()) {
            ReactiveLimits rl = g.getReactiveLimits();
            if (onlyMissing && rl != null && rl.getKind() == ReactiveLimitsKind.CURVE) {
                continue;
            }
            considered++;
            double s = g.getRatedS();
            double pmax = g.getMaxP();
            if (!(Double.isFinite(s) && s > 0 && Double.isFinite(pmax) && pmax > 0)) {
                continue;
            }
            double emax = 0.0;
            double emin = 0.0;
            if (rl instanceof MinMaxReactiveLimits mm) {
                emax = Double.isFinite(mm.getMaxQ()) ? mm.getMaxQ() : 0.0;
                emin = Double.isFinite(mm.getMinQ()) ? mm.getMinQ() : 0.0;
            }
            double floor = DEFAULT_CURVE_MIN_Q_FRACTION * s;
            ReactiveCapabilityCurveAdder adder = g.newReactiveCapabilityCurve();
            for (int k = 0; k < points; k++) {
                double p = pmax * k / (points - 1);
                double arm = Math.sqrt(Math.max(s * s - p * p, floor * floor));
                adder.beginPoint()
                        .setP(p)
                        .setMaxQ(Math.max(arm, emax))    // never narrower than the existing band
                        .setMinQ(Math.min(-arm, emin))
                        .endPoint();
            }
            adder.add();
            set++;
        }
        return new ReactiveCurveStats(considered, set);
    }

    // -----------------------------------------------------------------------
    // Load detail (fixed vs variable P/Q split)
    // -----------------------------------------------------------------------

    public static final double DEFAULT_LOAD_FIXED_FRACTION = 0.4;

    public record LoadDetailStats(int loads, int set) {
    }

    /**
     * Split each load's P/Q into a fixed and a variable part (the
     * {@code detail} extension). A load flow cannot infer the split, so a
     * representative constant {@code fixedFraction} (default 40 % fixed, 60 %
     * variable) is applied to every load's p0/q0.
     */
    public static LoadDetailStats addLoadDetail(Network network, double fixedFraction,
                                                boolean onlyMissing) {
        if (!(fixedFraction >= 0.0 && fixedFraction <= 1.0)) {
            throw new IllegalArgumentException("fixedFraction must be in [0, 1]");
        }
        double var = 1.0 - fixedFraction;
        int set = 0;
        for (Load load : network.getLoads()) {
            if (onlyMissing && load.getExtension(LoadDetail.class) != null) {
                continue;
            }
            double p0 = load.getP0();
            double q0 = load.getQ0();
            load.newExtension(LoadDetailAdder.class)
                    .withFixedActivePower(fixedFraction * p0)
                    .withVariableActivePower(var * p0)
                    .withFixedReactivePower(fixedFraction * q0)
                    .withVariableReactivePower(var * q0)
                    .add();
            set++;
        }
        return new LoadDetailStats(set, set);
    }

    // -----------------------------------------------------------------------
    // Discrete measurements (tap-changer positions)
    // -----------------------------------------------------------------------

    public record DiscreteMeasurementsStats(int measurements) {
    }

    /**
     * Attach a discrete tap-position measurement to every ratio and phase tap
     * changer (the discrete counterpart of the analog {@code measurements}
     * extension), keyed by the transformer. With {@code onlyMissing},
     * transformers already carrying the extension are skipped.
     */
    public static DiscreteMeasurementsStats addDiscreteMeasurements(Network network,
                                                                    boolean onlyMissing) {
        int count = 0;
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            if (onlyMissing && tx.getExtension(DiscreteMeasurements.class) != null) {
                continue;
            }
            DiscreteMeasurements<?> meas = null;
            if (tx.getRatioTapChanger() != null) {
                meas = getOrCreateDiscreteMeasurements(tx);
                addTapPosition(meas, tx.getId(), DiscreteMeasurement.TapChanger.RATIO_TAP_CHANGER,
                        tx.getRatioTapChanger().getTapPosition());
                count++;
            }
            if (tx.getPhaseTapChanger() != null) {
                if (meas == null) {
                    meas = getOrCreateDiscreteMeasurements(tx);
                }
                addTapPosition(meas, tx.getId(), DiscreteMeasurement.TapChanger.PHASE_TAP_CHANGER,
                        tx.getPhaseTapChanger().getTapPosition());
                count++;
            }
        }
        return new DiscreteMeasurementsStats(count);
    }

    @SuppressWarnings("unchecked")
    private static <I extends Identifiable<I>> DiscreteMeasurements<I> getOrCreateDiscreteMeasurements(
            I identifiable) {
        DiscreteMeasurements<I> meas = identifiable.getExtension(DiscreteMeasurements.class);
        if (meas == null) {
            identifiable.newExtension(DiscreteMeasurementsAdder.class).add();
            meas = identifiable.getExtension(DiscreteMeasurements.class);
        }
        return meas;
    }

    private static void addTapPosition(DiscreteMeasurements<?> meas, String elementId,
                                       DiscreteMeasurement.TapChanger tapChanger, int position) {
        String kind = tapChanger.name();
        meas.newDiscreteMeasurement()
                .setId(elementId + "_" + kind + "_POS")
                .setType(DiscreteMeasurement.Type.TAP_POSITION)
                .setTapChanger(tapChanger)
                .setValue(position)
                .setValid(true)
                .add();
    }

    // -----------------------------------------------------------------------
    // Element properties (free-form string key/value tags)
    // -----------------------------------------------------------------------

    public static final int DEFAULT_REGION_COUNT = 10;
    public static final String DEFAULT_COUNTRY = "XX";

    public record PropertiesStats(int substations, int voltageLevels) {
    }

    static String voltageClass(double nominalV) {
        if (nominalV >= 300.0) {
            return "EHV";
        }
        if (nominalV >= 100.0) {
            return "HV";
        }
        if (nominalV >= 1.0) {
            return "MV";
        }
        return "LV";
    }

    /**
     * Tag substations and voltage levels with representative string properties:
     * each substation gets {@code region} (partitioned into {@code regionCount}
     * zones by sorted id) and {@code country_code}; each voltage level gets
     * {@code voltage_class} (EHV/HV/MV/LV from nominal kV). Keys avoid native
     * IIDM attribute names so they do not collide on a rebuild. With
     * {@code onlyMissing} elements that already carry any property are left alone.
     */
    public static PropertiesStats addProperties(Network network, int regionCount, String country,
                                                boolean onlyMissing) {
        if (regionCount < 1) {
            throw new IllegalArgumentException("regionCount must be >= 1");
        }
        int subCount = 0;
        List<Substation> subs = network.getSubstationStream()
                .sorted(Comparator.comparing(Substation::getId)).toList();
        for (Substation s : subs) {
            if (onlyMissing && !s.getPropertyNames().isEmpty()) {
                continue;
            }
            s.setProperty("region", String.format("REGION_%02d", subCount % regionCount));
            s.setProperty("country_code", country);
            subCount++;
        }
        int vlCount = 0;
        List<VoltageLevel> vls = network.getVoltageLevelStream()
                .sorted(Comparator.comparing(VoltageLevel::getId)).toList();
        for (VoltageLevel vl : vls) {
            if (onlyMissing && !vl.getPropertyNames().isEmpty()) {
                continue;
            }
            vl.setProperty("voltage_class", voltageClass(vl.getNominalV()));
            vlCount++;
        }
        return new PropertiesStats(subCount, vlCount);
    }

    // -----------------------------------------------------------------------
    // Short-circuit data
    // -----------------------------------------------------------------------

    public static final double DEFAULT_TRANS_X_PU = 0.25;
    public static final double DEFAULT_SUBTRANS_X_PU = 0.18;
    private static final Map<String, Double> IP_MAX_BY_CLASS = Map.of(
            "EHV", 40000.0, "HV", 31500.0, "MV", 25000.0, "LV", 10000.0);
    private static final double IP_MIN_FRACTION = 0.3;

    public record ShortCircuitStats(int generators, int voltageLevels) {
    }

    /**
     * Add short-circuit data used by fault calculations: transient /
     * subtransient reactances on every generator (sized
     * {@code x_pu * Vn^2 / ratedS}, step-up transformer reactance 0), and
     * representative min/max short-circuit current by voltage class on every
     * voltage level. Run {@link #addRatedS} first for the best sizing; with
     * {@code onlyMissing} elements already carrying the extension are skipped.
     */
    public static ShortCircuitStats addShortCircuit(Network network, boolean onlyMissing) {
        int genCount = 0;
        for (Generator g : network.getGenerators()) {
            if (onlyMissing && g.getExtension(GeneratorShortCircuit.class) != null) {
                continue;
            }
            double vn = g.getTerminal().getVoltageLevel().getNominalV();
            double s = g.getRatedS();
            if (!(Double.isFinite(s) && s > 0)) {
                double cap = genCapacity(g);
                s = cap > 0 ? cap / DEFAULT_GENERATOR_POWER_FACTOR : 0.0;
            }
            double base = (vn > 0 && s > 0) ? (vn * vn / s) : 1.0;
            g.newExtension(GeneratorShortCircuitAdder.class)
                    .withDirectTransX(DEFAULT_TRANS_X_PU * base)
                    .withDirectSubtransX(DEFAULT_SUBTRANS_X_PU * base)
                    .withStepUpTransformerX(0.0)
                    .add();
            genCount++;
        }
        int vlCount = 0;
        for (VoltageLevel vl : network.getVoltageLevels()) {
            if (onlyMissing && vl.getExtension(IdentifiableShortCircuit.class) != null) {
                continue;
            }
            double ipMax = IP_MAX_BY_CLASS.get(voltageClass(vl.getNominalV()));
            vl.newExtension(IdentifiableShortCircuitAdder.class)
                    .withIpMin(ipMax * IP_MIN_FRACTION)
                    .withIpMax(ipMax)
                    .add();
            vlCount++;
        }
        return new ShortCircuitStats(genCount, vlCount);
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
