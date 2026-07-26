# OpenLoadFlow modeling / outer-loop coverage

Which OpenLoadFlow (OLF) modeling options and outer loops the fully-enhanced
fixtures already exercise, and — for the ones they don't — a **light**
modification that would make the network exercise them while still converging.

Grounded in the OLF provider parameters of the installed `open-loadflow 2.3.0`
(each maps to a modeling capability / outer loop) and the current control state
of `python/data/pegase13659_full.xiidm.gz`.

Convergence rule of thumb: set every new control **target to the value already
present in the solved base case** (or leave the controller disabled/at neutral),
so the corresponding outer loop has little or nothing to do and the flow still
converges. Where we want the loop to actually *act*, perturb the target by a
small amount that stays inside the device's range.

## Already exercised

| outer loop / option | why it's active |
|---|---|
| Distributed slack (`slackDistribution*`) | `activePowerControl` on all generators |
| Reactive limits (`reactiveLimits*`) | generators carry MIN_MAX / curve Q limits |
| Generator **local** voltage control | all 4,092 generators regulate their own bus |

## Not yet exercised — light modification to enable

| # | outer loop / option (OLF param) | current state | light modification | convergence |
|---|---|---|---|---|
| 1 | **Remote** voltage control (`voltageRemoteControl`) | ✅ **done** — `add_remote_voltage_control.deport_generators` moves EHV generators onto a new LV bus behind a GSU transformer (node-breaker, feeder bays), regulating the HV bus remotely | done | converges (DC init) |
| 2 | **Shared/coordinated** voltage control | one controller per bus | ✅ **done** — `complete_network.add_shared_voltage_control` points the generators of a multi-unit voltage level at one common bus (50 groups), target = its solved voltage | done | converges |
| 3 | **Transformer** voltage control (`transformerVoltageControlMode`) | ✅ **done** — `complete_network.add_transformer_voltage_control` keeps a converging subset (~300) of the ratio tap changers regulating (all 5 655 at once diverge) and verifies the outer loop converges | done | converges (subset) |
| 4 | **Shunt** voltage control (`shuntVoltageControlMode`) | ✅ **done** — source shunts are single-section (can't control), so `complete_network.add_shunt_voltage_control` adds a few multi-section switchable shunts (node-breaker bays) regulating to the bus solved V | done | converges |
| 5 | **Phase** control (`phaseShifterRegulationOn`) | ✅ **done** — `complete_network.add_phase_control` enables the 74 PTCs as CURRENT_LIMITER with a threshold above base-case flow (passive until overload, like real shifters) | done | converges with phase control on |
| 6 | **Secondary** voltage control (`secondaryVoltageControl`) | extension absent | add a control zone: one pilot bus + a couple of controlling generators, target = pilot's solved V | trivial |
| 7 | **SVC** voltage control + slope (`voltagePerReactivePowerControl`, `svcVoltageMonitoring`) | ✅ **done** — `complete_network.add_svc_voltage_control` puts the SVCs in VOLTAGE mode (target = bus solved V) with a voltage-droop slope | done | converges |
| 8 | **SVC standby automaton** (`svcVoltageMonitoring`) | `standbyAutomaton` present, standby off | set one SVC to standby with thresholds around solved V | trivial |
| 9 | **Generator remote reactive-power control** (`generatorReactivePowerRemoteControl`) | extension absent | add `RemoteReactivePowerControl` on a generator, target Q = current branch Q | trivial |
| 10 | **Transformer reactive-power control** (`transformerReactivePowerControl`) | absent | set one RTC to reactive-power regulation, target = current Q | trivial |
| 11 | **Area interchange control** (`areaInterchangeControl`) | 0 areas | create a few Areas with interchange target = current net position | trivial (target met) |
| 12 | **HVDC AC emulation** (angle droop) | ✅ **done** — `complete_network.add_hvdc_ac_emulation` enables angle-droop control on the HVDC links, with p0 cancelling the droop term at the solved angle difference | done | converges |
| 13 | **Automation systems** (`simulateAutomationSystems`) | none | add one `OverloadManagementSystem` on a substation with a high threshold (never trips) | trivial (inactive) |

## Notes

- Items 1–2, 4–12 are pure attribute/extension edits on existing components and
  keep the base case converged because each target is set to the already-solved
  value; they can be added as a `complete_network`-style completion with a
  before/after load-flow check.
- Item 3 (transformer VC) and any "make the loop actually move" variant need the
  matching LF option switched on to be visible; the fixture only needs to *carry*
  the regulating equipment.
- Item 11 (areas) also fills the `AREA*` object types, currently zero.
