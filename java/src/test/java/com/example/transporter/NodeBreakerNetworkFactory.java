package com.example.transporter;

import com.powsybl.iidm.network.*;

/**
 * Builds test networks electrically identical to {@code test_network.xiidm}.
 *
 * <p>{@link #create()} — HV node-breaker, LV bus-breaker.
 * <p>{@link #createWithNodeBreakerLv()} — both sides node-breaker.
 *
 * <pre>
 * VL_HV (400 kV, NODE_BREAKER)
 *   node 0  BusbarSection BBS_HV
 *   DISC_EXT  0-1  |  BRK_EXT  1-2  |  EXT_GRID at 2
 *   DISC_LOAD 0-3  |  BRK_LOAD 3-4  |  HV_LOAD  at 4
 *   DISC_TX   0-5  |  BRK_TX   5-6  |  TX (HV terminal) at 6
 * </pre>
 */
final class NodeBreakerNetworkFactory {

    private NodeBreakerNetworkFactory() {}

    static Network create() {
        Network net = Network.create("test_nb", "manual");
        Substation s = net.newSubstation().setId("S_MAIN").add();

        // HV side — node breaker
        VoltageLevel hvVl = s.newVoltageLevel()
                .setId("VL_HV").setNominalV(400.0)
                .setTopologyKind(TopologyKind.NODE_BREAKER)
                .add();
        VoltageLevel.NodeBreakerView nbv = hvVl.getNodeBreakerView();

        nbv.newBusbarSection().setId("BBS_HV").setNode(0).add();

        // EXT_GRID feeder bay
        nbv.newDisconnector().setId("DISC_EXT").setNode1(0).setNode2(1).setOpen(false).add();
        nbv.newBreaker().setId("BRK_EXT").setNode1(1).setNode2(2).setOpen(false).add();
        hvVl.newGenerator()
                .setId("EXT_GRID").setEnergySource(EnergySource.OTHER)
                .setMinP(-5000.0).setMaxP(5000.0)
                .setTargetP(0.0).setTargetV(400.0).setTargetQ(0.0)
                .setVoltageRegulatorOn(true).setNode(2).add()
                .newMinMaxReactiveLimits().setMinQ(-5000.0).setMaxQ(5000.0).add();

        // HV_LOAD feeder bay
        nbv.newDisconnector().setId("DISC_LOAD").setNode1(0).setNode2(3).setOpen(false).add();
        nbv.newBreaker().setId("BRK_LOAD").setNode1(3).setNode2(4).setOpen(false).add();
        hvVl.newLoad()
                .setId("HV_LOAD").setLoadType(LoadType.UNDEFINED)
                .setP0(200.0).setQ0(40.0).setNode(4).add();

        // TX feeder bay (HV terminal at node 6)
        nbv.newDisconnector().setId("DISC_TX").setNode1(0).setNode2(5).setOpen(false).add();
        nbv.newBreaker().setId("BRK_TX").setNode1(5).setNode2(6).setOpen(false).add();

        // LV side — bus breaker (same as test_network.xiidm)
        VoltageLevel lvVl = s.newVoltageLevel()
                .setId("VL_LV").setNominalV(20.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        lvVl.getBusBreakerView().newBus().setId("BUS_LV").add();

        lvVl.newGenerator()
                .setId("GEN_LV").setEnergySource(EnergySource.OTHER)
                .setMinP(0.0).setMaxP(500.0)
                .setTargetP(400.0).setTargetV(20.5).setTargetQ(0.0)
                .setVoltageRegulatorOn(true)
                .setBus("BUS_LV").setConnectableBus("BUS_LV").add()
                .newReactiveCapabilityCurve()
                    .beginPoint().setP(0.0).setMinQ(-150.0).setMaxQ(150.0).endPoint()
                    .beginPoint().setP(100.0).setMinQ(-200.0).setMaxQ(250.0).endPoint()
                    .beginPoint().setP(300.0).setMinQ(-180.0).setMaxQ(220.0).endPoint()
                    .beginPoint().setP(500.0).setMinQ(-100.0).setMaxQ(120.0).endPoint()
                    .add();

        lvVl.newLoad()
                .setId("AUX_LOAD").setLoadType(LoadType.UNDEFINED)
                .setP0(15.0).setQ0(5.0)
                .setBus("BUS_LV").setConnectableBus("BUS_LV").add();

        // Transformer: HV at node 6, LV at BUS_LV
        s.newTwoWindingsTransformer()
                .setId("TX")
                .setR(0.0026666666666666666).setX(0.07999999999999999)
                .setG(9.0e-4).setB(-0.026999999999999996)
                .setRatedU1(400.0).setRatedU2(20.0).setRatedS(600.0)
                .setVoltageLevel1("VL_HV").setNode1(6)
                .setVoltageLevel2("VL_LV").setBus2("BUS_LV").setConnectableBus2("BUS_LV")
                .add();

        return net;
    }

    /**
     * Same as {@link #create()} but with VL_LV also in NODE_BREAKER topology.
     *
     * <pre>
     * VL_LV (20 kV, NODE_BREAKER)
     *   node 0  BusbarSection BBS_LV
     *   DISC_GEN_LV  0-1  |  BRK_GEN_LV  1-2  |  GEN_LV   at 2
     *   DISC_AUX_LV  0-3  |  BRK_AUX_LV  3-4  |  AUX_LOAD at 4
     *   DISC_TX_LV   0-5  |  BRK_TX_LV   5-6  |  TX (LV terminal) at 6
     * </pre>
     */
    static Network createWithNodeBreakerLv() {
        Network net = Network.create("test_nb_full", "manual");
        Substation s = net.newSubstation().setId("S_MAIN").add();

        // HV side — node breaker (identical to create())
        VoltageLevel hvVl = s.newVoltageLevel()
                .setId("VL_HV").setNominalV(400.0)
                .setTopologyKind(TopologyKind.NODE_BREAKER)
                .add();
        VoltageLevel.NodeBreakerView hvNbv = hvVl.getNodeBreakerView();

        hvNbv.newBusbarSection().setId("BBS_HV").setNode(0).add();

        hvNbv.newDisconnector().setId("DISC_EXT").setNode1(0).setNode2(1).setOpen(false).add();
        hvNbv.newBreaker().setId("BRK_EXT").setNode1(1).setNode2(2).setOpen(false).add();
        hvVl.newGenerator()
                .setId("EXT_GRID").setEnergySource(EnergySource.OTHER)
                .setMinP(-5000.0).setMaxP(5000.0)
                .setTargetP(0.0).setTargetV(400.0).setTargetQ(0.0)
                .setVoltageRegulatorOn(true).setNode(2).add()
                .newMinMaxReactiveLimits().setMinQ(-5000.0).setMaxQ(5000.0).add();

        hvNbv.newDisconnector().setId("DISC_LOAD").setNode1(0).setNode2(3).setOpen(false).add();
        hvNbv.newBreaker().setId("BRK_LOAD").setNode1(3).setNode2(4).setOpen(false).add();
        hvVl.newLoad()
                .setId("HV_LOAD").setLoadType(LoadType.UNDEFINED)
                .setP0(200.0).setQ0(40.0).setNode(4).add();

        hvNbv.newDisconnector().setId("DISC_TX").setNode1(0).setNode2(5).setOpen(false).add();
        hvNbv.newBreaker().setId("BRK_TX").setNode1(5).setNode2(6).setOpen(false).add();

        // LV side — node breaker
        VoltageLevel lvVl = s.newVoltageLevel()
                .setId("VL_LV").setNominalV(20.0)
                .setTopologyKind(TopologyKind.NODE_BREAKER)
                .add();
        VoltageLevel.NodeBreakerView lvNbv = lvVl.getNodeBreakerView();

        lvNbv.newBusbarSection().setId("BBS_LV").setNode(0).add();

        // GEN_LV feeder bay
        lvNbv.newDisconnector().setId("DISC_GEN_LV").setNode1(0).setNode2(1).setOpen(false).add();
        lvNbv.newBreaker().setId("BRK_GEN_LV").setNode1(1).setNode2(2).setOpen(false).add();
        lvVl.newGenerator()
                .setId("GEN_LV").setEnergySource(EnergySource.OTHER)
                .setMinP(0.0).setMaxP(500.0)
                .setTargetP(400.0).setTargetV(20.5).setTargetQ(0.0)
                .setVoltageRegulatorOn(true).setNode(2).add()
                .newReactiveCapabilityCurve()
                    .beginPoint().setP(0.0).setMinQ(-150.0).setMaxQ(150.0).endPoint()
                    .beginPoint().setP(100.0).setMinQ(-200.0).setMaxQ(250.0).endPoint()
                    .beginPoint().setP(300.0).setMinQ(-180.0).setMaxQ(220.0).endPoint()
                    .beginPoint().setP(500.0).setMinQ(-100.0).setMaxQ(120.0).endPoint()
                    .add();

        // AUX_LOAD feeder bay
        lvNbv.newDisconnector().setId("DISC_AUX_LV").setNode1(0).setNode2(3).setOpen(false).add();
        lvNbv.newBreaker().setId("BRK_AUX_LV").setNode1(3).setNode2(4).setOpen(false).add();
        lvVl.newLoad()
                .setId("AUX_LOAD").setLoadType(LoadType.UNDEFINED)
                .setP0(15.0).setQ0(5.0).setNode(4).add();

        // TX LV feeder bay
        lvNbv.newDisconnector().setId("DISC_TX_LV").setNode1(0).setNode2(5).setOpen(false).add();
        lvNbv.newBreaker().setId("BRK_TX_LV").setNode1(5).setNode2(6).setOpen(false).add();

        // Transformer: HV at node 6, LV at node 6
        s.newTwoWindingsTransformer()
                .setId("TX")
                .setR(0.0026666666666666666).setX(0.07999999999999999)
                .setG(9.0e-4).setB(-0.026999999999999996)
                .setRatedU1(400.0).setRatedU2(20.0).setRatedS(600.0)
                .setVoltageLevel1("VL_HV").setNode1(6)
                .setVoltageLevel2("VL_LV").setNode2(6)
                .add();

        return net;
    }
}
