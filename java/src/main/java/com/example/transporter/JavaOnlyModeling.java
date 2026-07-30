package com.example.transporter;

import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;

import java.util.function.Consumer;

/**
 * Modeling that OpenLoadFlow and the powsybl-core model support but pypowsybl
 * 1.16.1 cannot author — so these go beyond the Python {@code complete_network.py}:
 *
 * <ul>
 *   <li>generator remote reactive-power control (the {@code RemoteReactivePowerControl}
 *   extension, OLF's {@code generatorReactivePowerRemoteControl} loop);</li>
 *   <li>transformer reactive-power control (a ratio tap changer in
 *   {@code REACTIVE_POWER} regulation mode, OLF's {@code transformerReactivePowerControl});</li>
 *   <li>automation systems (an {@code OverloadManagementSystem}, OLF's
 *   {@code simulateAutomationSystems}).</li>
 * </ul>
 *
 * <p>Each control target is set to the value already present in the solved base
 * case (or a threshold high enough never to trip), and convergence with the
 * matching outer loop on is verified — the change is rolled back if it does not
 * converge. Run after a load flow.
 */
public final class JavaOnlyModeling {

    private JavaOnlyModeling() {
    }

    private static boolean converges(Network network, Consumer<LoadFlowParameters> tweak) {
        LoadFlowParameters params = new LoadFlowParameters()
                .setDistributedSlack(true).setUseReactiveLimits(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        tweak.accept(params);
        return LoadFlow.run(network, params).isFullyConverged();
    }

    // -----------------------------------------------------------------------
    // Generator remote reactive-power control
    // -----------------------------------------------------------------------

    public record GeneratorReactiveStats(int generators) {
    }

    /**
     * Make a few generators control the reactive power flowing into an adjacent
     * line instead of their voltage, target Q = the flow already there, so the
     * {@code generatorReactivePowerRemoteControl} loop starts satisfied. Verified
     * converging with the loop on; the extension is removed on divergence.
     */
    public static GeneratorReactiveStats addGeneratorRemoteReactivePowerControl(Network network,
                                                                                Integer count) {
        int target = count == null ? 1 : count;
        int applied = 0;
        for (Generator g : network.getGenerators()) {
            if (applied >= target) {
                break;
            }
            Bus genBus = g.getTerminal().getBusView().getBus();
            if (genBus == null) {
                continue;
            }
            Terminal regTerm = lineTerminalOnBus(network, genBus);
            if (regTerm == null || !Double.isFinite(regTerm.getQ())) {
                continue;
            }
            g.newExtension(RemoteReactivePowerControlAdder.class)
                    .withTargetQ(regTerm.getQ())
                    .withRegulatingTerminal(regTerm)
                    .withEnabled(true)
                    .add();
            if (converges(network, p -> OpenLoadFlowParameters.create(p)
                    .setGeneratorReactivePowerRemoteControl(true))) {
                applied++;
            } else {
                g.removeExtension(RemoteReactivePowerControl.class);
            }
        }
        return new GeneratorReactiveStats(applied);
    }

    private static Terminal lineTerminalOnBus(Network network, Bus bus) {
        for (Line line : network.getLines()) {
            for (TwoSides side : TwoSides.values()) {
                Terminal t = line.getTerminal(side);
                Bus b = t.getBusView().getBus();
                if (b != null && b.getId().equals(bus.getId())) {
                    return t;
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Transformer reactive-power control
    // -----------------------------------------------------------------------

    public record TransformerReactiveStats(int transformers) {
    }

    /**
     * Put a few ratio tap changers into REACTIVE_POWER regulation mode, target =
     * the reactive power already flowing through the transformer, so the
     * {@code transformerReactivePowerControl} loop starts satisfied. Verified
     * converging with the loop on; reverted to voltage mode on divergence.
     */
    public static TransformerReactiveStats addTransformerReactivePowerControl(Network network,
                                                                              Integer count) {
        int target = count == null ? 1 : count;
        int applied = 0;
        for (TwoWindingsTransformer tx : network.getTwoWindingsTransformers()) {
            if (applied >= target) {
                break;
            }
            RatioTapChanger rtc = tx.getRatioTapChanger();
            if (rtc == null) {
                continue;
            }
            Terminal t = tx.getTerminal(TwoSides.TWO);
            if (!Double.isFinite(t.getQ())) {
                continue;
            }
            boolean wasRegulating = rtc.isRegulating();
            double oldValue = rtc.getRegulationValue();
            RatioTapChanger.RegulationMode oldMode = rtc.getRegulationMode();
            rtc.setRegulating(false);
            rtc.setRegulationTerminal(t);
            rtc.setTargetDeadband(5.0);
            rtc.setRegulationValue(t.getQ());
            rtc.setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER);
            rtc.setRegulating(true);
            if (converges(network, p -> OpenLoadFlowParameters.create(p)
                    .setTransformerReactivePowerControl(true))) {
                applied++;
            } else {
                rtc.setRegulating(false);
                rtc.setRegulationMode(oldMode);
                rtc.setRegulationValue(oldValue);
                rtc.setRegulating(wasRegulating);
            }
        }
        return new TransformerReactiveStats(applied);
    }

    // -----------------------------------------------------------------------
    // Automation systems (overload management)
    // -----------------------------------------------------------------------

    public record AutomationStats(int systems) {
    }

    /**
     * Add a few overload-management systems that monitor a line and would trip it
     * on overload, with a current threshold high enough never to trip, so the
     * {@code simulateAutomationSystems} loop is present but inactive. Verified
     * converging with the loop on.
     */
    public static AutomationStats addAutomationSystems(Network network, Integer count) {
        int target = count == null ? 1 : count;
        int applied = 0;
        for (Line line : network.getLines()) {
            if (applied >= target) {
                break;
            }
            Substation sub = line.getTerminal1().getVoltageLevel().getSubstation().orElse(null);
            if (sub == null) {
                continue;
            }
            String omsId = "SYN_OMS_" + applied;
            sub.newOverloadManagementSystem()
                    .setId(omsId)
                    .setEnabled(true)
                    .setMonitoredElementId(line.getId())
                    .setMonitoredElementSide(ThreeSides.ONE)
                    .newBranchTripping()
                    .setKey("k")
                    .setName("never")
                    .setCurrentLimit(1.0e9)     // high enough never to trip
                    .setOpenAction(true)
                    .setBranchToOperateId(line.getId())
                    .setSideToOperate(TwoSides.ONE)
                    .add()
                    .add();
            if (converges(network, p -> OpenLoadFlowParameters.create(p)
                    .setSimulateAutomationSystems(true))) {
                applied++;
            } else {
                network.getOverloadManagementSystem(omsId).remove();
            }
        }
        return new AutomationStats(applied);
    }
}
