package com.example.transporter;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;

/**
 * Build an <b>extended IEEE-14</b> network: the standard IEEE-14 case grown
 * with every equipment type the bus-to-node-breaker converter supports beyond
 * the vanilla generators / loads / lines / two-winding transformers, so the
 * conversion can be exercised end to end.
 *
 * <p>Added on top of {@link IeeeCdfNetworkFactory#create14()}:
 * <ul>
 *   <li>a {@link Battery} (B10) and a {@link StaticVarCompensator} (B4);</li>
 *   <li>a {@link BoundaryLine} (B13) and a non-linear
 *       {@link ShuntCompensator} (B14);</li>
 *   <li>a ratio tap changer on {@code T4-7-1} and a phase tap changer on
 *       {@code T4-9-1} (both parked at their neutral step, so the load flow is
 *       unchanged);</li>
 *   <li>a {@link ThreeWindingsTransformer} sub-station fed from B5, with loads
 *       on its MV and LV sides;</li>
 *   <li>a VSC {@link HvdcLine} between B5 and B2 (both stations in
 *       reactive-power mode, so no voltage-control conflict);</li>
 *   <li>an LCC {@link HvdcLine} between B3 and B6 (stations sat next to the
 *       generators there for reactive support);</li>
 *   <li>a {@link TieLine} (two paired boundary lines) in parallel with the
 *       B10-B11 line;</li>
 *   <li>a two-busbar voltage level joined by a closed bus coupler, fed from B1
 *       with a load reachable only through the coupler.</li>
 * </ul>
 *
 * <p>Everything is attached to the main connected component and sized modestly
 * so the AC load flow converges.
 */
public final class ExtendedIeee14Factory {

    private ExtendedIeee14Factory() {
        // Static utility
    }

    public static Network create() {
        Network net = IeeeCdfNetworkFactory.create14();
        net.setName("ieee14-extended");

        addBattery(net);
        addStaticVarCompensator(net);
        addBoundaryLine(net);
        addNonLinearShunt(net);
        addRatioTapChanger(net);
        addPhaseTapChanger(net);
        addThreeWindingsTransformer(net);
        addVscHvdc(net);
        addLccHvdc(net);
        addTieLine(net);
        addBusCoupler(net);

        return net;
    }

    private static void addBattery(Network net) {
        net.getVoltageLevel("VL10").newBattery()
                .setId("BAT1")
                .setBus("B10").setConnectableBus("B10")
                .setMinP(-20.0).setMaxP(20.0)
                .setTargetP(5.0).setTargetQ(0.0)
                .add()
                .newMinMaxReactiveLimits().setMinQ(-10.0).setMaxQ(10.0).add();
    }

    private static void addStaticVarCompensator(Network net) {
        // B4 has no generator, so the SVC is the only voltage regulator there.
        net.getVoltageLevel("VL4").newStaticVarCompensator()
                .setId("SVC1")
                .setBus("B4").setConnectableBus("B4")
                .setBmin(-0.005).setBmax(0.005)
                .setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE)
                .setRegulating(true)
                .setVoltageSetpoint(137.4)
                .setReactivePowerSetpoint(0.0)
                .add();
    }

    private static void addBoundaryLine(Network net) {
        net.getVoltageLevel("VL13").newBoundaryLine()
                .setId("DL1")
                .setBus("B13").setConnectableBus("B13")
                .setP0(3.0).setQ0(1.0)
                .setR(0.5).setX(1.0).setG(0.0).setB(0.0)
                .add();
    }

    private static void addNonLinearShunt(Network net) {
        net.getVoltageLevel("VL14").newShuntCompensator()
                .setId("SHNL1")
                .setBus("B14").setConnectableBus("B14")
                .setSectionCount(1)
                .newNonLinearModel()
                    .beginSection().setB(0.02).setG(0.0).endSection()
                    .beginSection().setB(0.05).setG(0.0).endSection()
                    .add()
                .add();
    }

    private static void addRatioTapChanger(Network net) {
        // Parked at the neutral tap (rho = 1), so the load flow is unchanged.
        TwoWindingsTransformer tx = net.getTwoWindingsTransformer("T4-7-1");
        tx.newRatioTapChanger()
                .setLowTapPosition(0).setTapPosition(1)
                .setLoadTapChangingCapabilities(true)
                .setRegulating(false)
                .setTargetDeadband(0.0)
                .setRegulationMode(RatioTapChanger.RegulationMode.VOLTAGE)
                .setRegulationValue(14.0)
                .setTargetV(14.0)
                .setRegulationTerminal(tx.getTerminal2())
                .beginStep().setRho(0.99).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .beginStep().setRho(1.00).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .beginStep().setRho(1.01).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .add();
    }

    private static void addPhaseTapChanger(Network net) {
        // Parked at the neutral tap (alpha = 0), so the load flow is unchanged.
        TwoWindingsTransformer tx = net.getTwoWindingsTransformer("T4-9-1");
        tx.newPhaseTapChanger()
                .setLowTapPosition(0).setTapPosition(1)
                .setRegulating(false)
                .setTargetDeadband(0.0)
                .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setRegulationValue(1000.0)
                .setRegulationTerminal(tx.getTerminal1())
                .beginStep().setAlpha(-5.0).setRho(1.0).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .beginStep().setAlpha(0.0).setRho(1.0).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .beginStep().setAlpha(5.0).setRho(1.0).setR(0.0).setX(0.0).setG(0.0).setB(0.0).endStep()
                .add();
    }

    private static void addThreeWindingsTransformer(Network net) {
        Substation s3w = net.newSubstation().setId("S_3W").setCountry(Country.FR).add();

        VoltageLevel hv = newBusBreakerVl(s3w, "VL_3W_HV", 135.0, "B_3W_HV");
        VoltageLevel mv = newBusBreakerVl(s3w, "VL_3W_MV", 33.0, "B_3W_MV");
        VoltageLevel lv = newBusBreakerVl(s3w, "VL_3W_LV", 11.0, "B_3W_LV");

        // Radial feed from B5 (135 kV).
        net.newLine()
                .setId("L_5_3W")
                .setVoltageLevel1("VL5").setBus1("B5").setConnectableBus1("B5")
                .setVoltageLevel2("VL_3W_HV").setBus2("B_3W_HV").setConnectableBus2("B_3W_HV")
                .setR(2.0).setX(6.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add();

        s3w.newThreeWindingsTransformer()
                .setId("T3W").setRatedU0(135.0)
                .newLeg1().setVoltageLevel("VL_3W_HV").setBus("B_3W_HV").setConnectableBus("B_3W_HV")
                    .setR(0.5).setX(10.0).setG(0.0).setB(0.0).setRatedU(135.0).add()
                .newLeg2().setVoltageLevel("VL_3W_MV").setBus("B_3W_MV").setConnectableBus("B_3W_MV")
                    .setR(0.5).setX(8.0).setG(0.0).setB(0.0).setRatedU(33.0).add()
                .newLeg3().setVoltageLevel("VL_3W_LV").setBus("B_3W_LV").setConnectableBus("B_3W_LV")
                    .setR(0.5).setX(6.0).setG(0.0).setB(0.0).setRatedU(11.0).add()
                .add();

        mv.newLoad().setId("LOAD_3W_MV").setBus("B_3W_MV").setConnectableBus("B_3W_MV")
                .setP0(20.0).setQ0(5.0).add();
        lv.newLoad().setId("LOAD_3W_LV").setBus("B_3W_LV").setConnectableBus("B_3W_LV")
                .setP0(10.0).setQ0(3.0).add();
    }

    private static void addVscHvdc(Network net) {
        net.getVoltageLevel("VL5").newVscConverterStation()
                .setId("VSC_A")
                .setBus("B5").setConnectableBus("B5")
                .setLossFactor(1.0f)
                .setVoltageRegulatorOn(false)
                .setReactivePowerSetpoint(0.0)
                .add()
                .newMinMaxReactiveLimits().setMinQ(-50.0).setMaxQ(50.0).add();

        net.getVoltageLevel("VL2").newVscConverterStation()
                .setId("VSC_B")
                .setBus("B2").setConnectableBus("B2")
                .setLossFactor(1.0f)
                .setVoltageRegulatorOn(false)
                .setReactivePowerSetpoint(0.0)
                .add()
                .newMinMaxReactiveLimits().setMinQ(-50.0).setMaxQ(50.0).add();

        net.newHvdcLine()
                .setId("HVDC1")
                .setR(1.0).setNominalV(150.0)
                .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                .setActivePowerSetpoint(15.0).setMaxP(60.0)
                .setConverterStationId1("VSC_A")
                .setConverterStationId2("VSC_B")
                .add();
    }

    private static void addLccHvdc(Network net) {
        // LCC stations consume reactive power, so sit them on buses that already
        // have a generator (B3, B6) for local voltage support.
        net.getVoltageLevel("VL3").newLccConverterStation()
                .setId("LCC_A")
                .setBus("B3").setConnectableBus("B3")
                .setLossFactor(1.1f).setPowerFactor(0.9f)
                .add();
        net.getVoltageLevel("VL6").newLccConverterStation()
                .setId("LCC_B")
                .setBus("B6").setConnectableBus("B6")
                .setLossFactor(1.1f).setPowerFactor(0.9f)
                .add();
        net.newHvdcLine()
                .setId("HVDC_LCC")
                .setR(1.5).setNominalV(150.0)
                .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                .setActivePowerSetpoint(10.0).setMaxP(50.0)
                .setConverterStationId1("LCC_A")
                .setConverterStationId2("LCC_B")
                .add();
    }

    private static void addTieLine(Network net) {
        // Two paired half-lines joined at boundary key XN_10_11, forming a tie
        // line in parallel with the existing B10-B11 line.
        net.getVoltageLevel("VL10").newBoundaryLine()
                .setId("DLT_A")
                .setBus("B10").setConnectableBus("B10")
                .setP0(0.0).setQ0(0.0)
                .setR(0.1).setX(0.4).setG(0.0).setB(0.0)
                .setPairingKey("XN_10_11")
                .add();
        net.getVoltageLevel("VL11").newBoundaryLine()
                .setId("DLT_B")
                .setBus("B11").setConnectableBus("B11")
                .setP0(0.0).setQ0(0.0)
                .setR(0.1).setX(0.4).setG(0.0).setB(0.0)
                .setPairingKey("XN_10_11")
                .add();
        net.newTieLine()
                .setId("TIE1")
                .setBoundaryLine1("DLT_A")
                .setBoundaryLine2("DLT_B")
                .add();
    }

    private static void addBusCoupler(Network net) {
        Substation sc = net.newSubstation().setId("S_COUP").setCountry(Country.FR).add();
        VoltageLevel vc = sc.newVoltageLevel()
                .setId("VL_COUP").setNominalV(135.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vc.getBusBreakerView().newBus().setId("B_COUP_A").add();
        vc.getBusBreakerView().newBus().setId("B_COUP_B").add();
        vc.getBusBreakerView().newSwitch()
                .setId("COUPLER1").setBus1("B_COUP_A").setBus2("B_COUP_B").setOpen(false)
                .add();

        // Radial feed from B1, load sits on the far busbar (reached via the coupler).
        net.newLine()
                .setId("L_1_COUP")
                .setVoltageLevel1("VL1").setBus1("B1").setConnectableBus1("B1")
                .setVoltageLevel2("VL_COUP").setBus2("B_COUP_A").setConnectableBus2("B_COUP_A")
                .setR(2.0).setX(6.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add();
        vc.newLoad().setId("LOAD_COUP").setBus("B_COUP_B").setConnectableBus("B_COUP_B")
                .setP0(10.0).setQ0(3.0).add();
    }

    private static VoltageLevel newBusBreakerVl(Substation s, String vlId,
                                                double nominalV, String busId) {
        VoltageLevel vl = s.newVoltageLevel()
                .setId(vlId).setNominalV(nominalV)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl.getBusBreakerView().newBus().setId(busId).add();
        return vl;
    }
}
