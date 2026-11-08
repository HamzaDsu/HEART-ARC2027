// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// All elaboration parameters (TopParams) with their consistency checks.

package sacris_v

sealed trait SafetyMode
object SafetyMode {
  case object None         extends SafetyMode
  case object DualLockstep extends SafetyMode
  case object TripleVoting extends SafetyMode
}

sealed trait MmioGateTarget
object MmioGateTarget {

  case object SevenSeg   extends MmioGateTarget
  case object SevenSegDp extends MmioGateTarget
  case object Gpo        extends MmioGateTarget
  case object SimEnd     extends MmioGateTarget

  case object UartThr    extends MmioGateTarget

  case object SpiData    extends MmioGateTarget
  case object I2cCmd     extends MmioGateTarget

  case object PlicCfg    extends MmioGateTarget
  case object AclintMsip extends MmioGateTarget

  case object UartScr    extends MmioGateTarget
  case object UartCfg    extends MmioGateTarget

  val terminal: Set[MmioGateTarget] = Set(SevenSeg, SevenSegDp, Gpo, SimEnd)

  val all: Set[MmioGateTarget] = terminal + UartThr

  val fifo: Set[MmioGateTarget] = Set(UartThr, SpiData, I2cCmd)
}

final case class TopParams(
  clkHz: Int = 50_000_000,
  uartBaud: Int = 115_200,
  timerTickCompare: Int = 50,           // mtime ticks every N core cycles (1 MHz at clkHz = 50 MHz; DTS timebase-frequency = clkHz / N)
  mtimecmpReset: BigInt = 0x0FFFFFFF,
  uartTxFifoDepth: Int = 64,
  uartRxFifoDepth: Int = 64,
  imemSize: Int = 32768,//32 Bit Words not Bytes
  dmemSize: Int = 32768,
  enableMExtension: Boolean = false,
  enableBExtension: Boolean = false,
  enableFExtension: Boolean = false,
  enableCExtension: Boolean = false,       // P2 reserved: no behavioral effect in P1
  withLateLoadForward: Boolean = false,    // Remove load-use stall (late MEM→ID forwarding)
  enableIntraPairForward: Boolean = false, // P1.3b: intra-pair RAW forwarding; off by default (P1.3a §7 timing fallback / shipping FPGA setting)
  enableDualIssue: Boolean = false,         // P1: 64-bit IMEM + FetchSplitter (Slot B discarded in P1.1)

  fpDivStepsPerCycle: Int = 1,
  divSteps: Int = 1,
  regOut: Boolean = true,
  spiTxFifoDepth: Int = 64,
  spiRxFifoDepth: Int = 64,
  spiBaud: Int = 20_000_000,
  i2cFifoDepth: Int = 64,
  i2cBaud: Int = 400_000,
  withSPI: Boolean = false,
  withI2C: Boolean = false,
  withSevenSegment: Boolean = false,
  withWDT: Boolean = true,
  withDmaMem: Boolean = false,
  dmaMemSize: Int = 16384,                // 32-bit words (default 64 KiB)
  withDmaController: Boolean = false,      // Phase 2 — DMA controller
  dmaChannels: Int = 1,                    // Phase 3 — number of DMA channels (1..4)
  withDmaTestSource: Boolean = false,      // Phase 3 — DREQ handshake test peripheral
  dmaTestSourceCount: Int = 1,             // Phase 3 — number of DmaTestSource instances (1..4)
  testDmaArbContention: Boolean = false,   // Test mode: drive arbiter port 1 to cause stalls
  withSpiLoopback: Boolean = false,        // Simulation-only SPI loopback slave
  withI2cLoopback: Boolean = false,        // Simulation-only I2C loopback slave

  dynPredBranch: Boolean = false,
  bhtEntries: Int = 256,                   // BHT table size (must be power of 2, used when dynPredBranch=true)
  enableIssueObservability: Boolean = false, // P1.2.5: expose IssueLogic per-check reject counters
  withBranchObserve: Boolean = false,      // Mirrors enableIssueObservability: 4 dontTouch'd 64-bit branch-event counters scraped by tb_TopLevel.cpp

  safetyMode: SafetyMode = SafetyMode.None,
  lockstepDelay: Int = 2,                  // temporal-diversity offset in cycles between replicas (SafeLS / AURIX practice: 2)

  safetyStoreGate: Boolean = false,
  withMemEcc: Boolean = false,

  safetyRollback: Boolean = false,
  safetyCkptPeriod: Int = 65536,

  safetyResync: Boolean = false,
  safetyResyncDebounce: Int = 16,

  safetyResyncPcDelay: Int = 5,

  safetyResyncCsrDelay: Int = 0,

  safetyResyncMcycleDelay: Int = 3,

  safetyResyncFpuGate: Boolean = true,

  safetyResyncFpRestore: Boolean = true,

  safetyResyncReleaseGate: Boolean = true,

  safetyResyncQuiesceAll: Boolean = false,

  safetyResyncInflight: Boolean = false,

  safetyResyncInflightReleaseAdj: Int = 0,

  safetyMmioGateSet: Set[MmioGateTarget] = Set.empty,

  safetyMmioFwdStatusSet: Set[MmioGateTarget] = Set.empty,

  safetyVoterFi: Boolean = false,

  safetyWalkRsGate: Boolean = false,

  safetyWalkExt: Boolean = false,

  safetyEgressCheck: Boolean = false,

  safetyEgressBlock: Boolean = false,

  safetyMmioDropCounter: Boolean = false,

  safetyEgressFi: Boolean = false,

  safetyRegfileScrub: Boolean = false,

  regfileScrubSelfCheck: Boolean = false,

  regfileScrubSelfCheckSweeps: Int = 256,

  regfileScrubSelfCheckDefeat: Boolean = false,

  safetyRegfileEcc: Boolean = false,

  bootRomHex: String = "src/Bootloader/instruction_mem.hex",

  hostMemPort: Boolean = false,

  bootRomRegisteredData: Boolean = false,

  frontendStallAddrFromReg: Boolean = false,

  frontendMinus4FromReg: Boolean = false,

  fetchEnableOpen: Boolean = false,

  dmemAddrFromAdder: Boolean = false,

  mduSplitPartials: Boolean = false,
) extends ParamSummary {

  def safetyMmioGate: Boolean     = safetyMmioGateSet.nonEmpty
  def safetyMmioGateFifo: Boolean = safetyMmioGateSet.contains(MmioGateTarget.UartThr)
  def mmioGated(t: MmioGateTarget): Boolean = safetyMmioGateSet.contains(t)

  def mmioFwdStatus(t: MmioGateTarget): Boolean = safetyMmioFwdStatusSet.contains(t)

  require(!(frontendStallAddrFromReg && enableCExtension),
    "frontendStallAddrFromReg is not supported with enableCExtension")

  require(!(frontendMinus4FromReg && (enableCExtension || safetyMode != SafetyMode.None)),
    "frontendMinus4FromReg is not supported with enableCExtension or a safety mode")

  require(!(dmemAddrFromAdder && (withDmaMem || withDmaController || withDmaTestSource)),
    "dmemAddrFromAdder is not supported with the DMA option group")

  require(!withDmaController || withDmaMem,
    "withDmaController requires withDmaMem")
  require(!withDmaTestSource || withDmaController,
    "withDmaTestSource requires withDmaController")
  require(!(withDmaController && testDmaArbContention),
    "testDmaArbContention is a stimulus-only mode, incompatible with withDmaController")
  require(dmaChannels >= 1 && dmaChannels <= 4,
    s"dmaChannels must be in 1..4, got $dmaChannels")
  require(dmaTestSourceCount >= 1 && dmaTestSourceCount <= 4,
    s"dmaTestSourceCount must be in 1..4, got $dmaTestSourceCount")
  require(!withDmaTestSource || dmaTestSourceCount <= dmaChannels,
    s"dmaTestSourceCount ($dmaTestSourceCount) must be <= dmaChannels ($dmaChannels)")
  require(!withSpiLoopback || withSPI,
    "withSpiLoopback requires withSPI")
  require(!withI2cLoopback || withI2C,
    "withI2cLoopback requires withI2C")
  require(!dynPredBranch || (bhtEntries > 0 && (bhtEntries & (bhtEntries - 1)) == 0),
    s"bhtEntries must be a power of 2, got $bhtEntries")
  require(fpDivStepsPerCycle == 1 || fpDivStepsPerCycle == 2,
    s"fpDivStepsPerCycle must be 1 or 2, got $fpDivStepsPerCycle")
  require(lockstepDelay >= 1,
    s"lockstepDelay must be >= 1, got $lockstepDelay")
  require(!safetyStoreGate || safetyMode != SafetyMode.None,
    "safetyStoreGate requires a redundant safetyMode (the gate is the " +
    "comparator's per-cycle verdict — there is nothing to gate on at None)")
  require(!withMemEcc || !enableDualIssue,
    "withMemEcc is implemented for the 32-bit InstructionMem only; the " +
    "64-bit dual-issue IMEM has no ECC variant yet")
  require(!safetyRollback || safetyMode == SafetyMode.DualLockstep,
    "safetyRollback (phase 3b) is the DCLS checkpoint/rollback path; it " +
    "requires safetyMode = DualLockstep")
  require(!safetyRollback || safetyStoreGate,
    "safetyRollback requires safetyStoreGate: a rollback must drop the " +
    "uncommitted stores of the discarded instructions, which is the store " +
    "gate's buffer-flush path (block 2)")
  require(!safetyRollback || !enableDualIssue,
    "safetyRollback is implemented for the single-issue core only (the " +
    "flop-based GPR snapshot/restore path); dual-issue is later work")
  require(!safetyRollback || safetyCkptPeriod >= 1,
    s"safetyCkptPeriod must be >= 1, got $safetyCkptPeriod")
  require(!safetyResync || safetyMode == SafetyMode.TripleVoting,
    "safetyResync (phase 3c) is the TMR majority re-sync path; it requires " +
    "safetyMode = TripleVoting (the voter is what makes a forward, " +
    "rollback-free repair possible — DCLS has no majority to copy from)")
  require(!safetyResync || !enableDualIssue,
    "safetyResync is implemented for the single-issue core only (it reuses " +
    "the flop-based GPR snapshot/restore datapath from phase 3b); dual-issue " +
    "is later work")
  require(!(safetyResync && safetyRollback),
    "safetyResync (TMR) and safetyRollback (DCLS) are mutually exclusive — " +
    "they are the recovery paths of two different safetyModes")
  require(!safetyRegfileScrub || safetyResync,
    "safetyRegfileScrub (cross-replica register-file scrub, STAGED-1) reuses the " +
    "per-replica gprMirror and the TMR resync classify/source-select datapath, both " +
    "of which ship only under safetyResync (⇒ TripleVoting). It is detection + " +
    "escalation folded into the existing resync trigger, not a freestanding mode.")
  require(!regfileScrubSelfCheck || safetyRegfileScrub,
    "regfileScrubSelfCheck (scrub-comparator walking-bit proof test, V-2 analogue) " +
    "proof-tests the Mem-vs-mirror comparator the scrub introduces, so it needs the " +
    "scrub itself (safetyRegfileScrub) present.")
  require(!regfileScrubSelfCheck || regfileScrubSelfCheckSweeps >= 2,
    "regfileScrubSelfCheckSweeps must be >= 2: N=1 would steal EVERY sweep for the " +
    "proof test and never scan for real faults (stActive always true).")
  require(!regfileScrubSelfCheckDefeat || regfileScrubSelfCheck,
    "regfileScrubSelfCheckDefeat (measurement-only stuck-comparator injection) only " +
    "makes sense with the self-check running to catch it.")
  require(!safetyRegfileEcc || safetyResync,
    "safetyRegfileEcc (per-replica register-file SECDED ECC, STAGE 1) is one arm of " +
    "the regfile-protection comparison matrix against safetyRegfileScrub (design memo " +
    "regfile_ecc_vs_scrub_comparison_design.md §3.1) and reuses the safetyResync " +
    "observability plumbing (SafetyCommitIO) for its correctable/uncorrectable event " +
    "export; like the scrub it requires safetyResync (=> TripleVoting).")
  require(!safetyResync || safetyResyncDebounce >= 1,
    s"safetyResyncDebounce must be >= 1, got $safetyResyncDebounce")
  require(!safetyResyncQuiesceAll || safetyResync,
    "safetyResyncQuiesceAll selects the QuiesceRestore variant of the TMR " +
    "re-sync; it requires safetyResync (it is a sub-flag selecting a strategy, " +
    "not a freestanding mode)")
  require(!safetyResyncInflight || safetyResync,
    "safetyResyncInflight selects the ForwardRepair in-flight pipeline-state copy " +
    "(Option 1); it requires safetyResync (it is a sub-flag, not a freestanding mode)")
  require(!(safetyResyncInflight && safetyResyncQuiesceAll),
    "safetyResyncInflight (ForwardRepair Δ=2 via in-flight copy) and " +
    "safetyResyncQuiesceAll (QuiesceRestore via all-core halt) are the two re-sync " +
    "strategies — mutually exclusive; QuiesceRestore reaches no-floor by halting, " +
    "ForwardRepair by copying in-flight state")
  require(!safetyMmioGate || safetyMode == SafetyMode.TripleVoting ||
          (safetyMode == SafetyMode.DualLockstep &&
           safetyMmioGateSet.subsetOf(MmioGateTarget.terminal)),
    "safetyMmioGate (MMIO egress commit gate) requires safetyMode = TripleVoting " +
    "(MASK: the 2-of-3 voted value commits, fail-operational) or DualLockstep " +
    "(BLOCK, FuSi O5: the maxLag-delayed primary slice commits only on a clean " +
    "same-cycle comparison, fail-safe — DCLS has no majority, so MASK is " +
    "information-theoretically impossible). The DCLS BLOCK arm is RESTRICTED to the " +
    "terminal register subset: FIFO-enqueue targets are ADDITIVE emits and the " +
    "DCLS-B3 rollback replays the checkpoint window, so a replayed gated enqueue " +
    "would double-emit (registers are overwrite-idempotent; queues are not). The " +
    "PLIC/MSIP/UART sinks are excluded not merely as unstaged: DCLS BLOCK drops on " +
    "EVERY divergence, so a dropped PLIC COMPLETE reproduces the persistent " +
    "IRQ-source-loss hazard the safetyEgressBlock x PlicCfg require forbids on TMR " +
    "(and a rollback replay double-executes the destructive CLAIM read); a dropped " +
    "MSIP self-IPI can WFI-deadlock. Extending the DCLS subset requires that " +
    "posture analysis, not just wiring.")
  require(!safetyMmioGateFifo || safetyMmioGate,
    "safetyMmioGateFifo (FIFO-enqueue deferred-commit prototype, UART THR) extends " +
    "the terminal MMIO egress gate and reuses its maxLag-delayed voted store slice; " +
    "it requires safetyMmioGate (⇒ TripleVoting)")
  require(!safetyMmioGateSet.contains(MmioGateTarget.SpiData) || withSPI,
    "MmioGateTarget.SpiData (SPI TX-FIFO deferred-commit) gates the SPI_DATA enqueue " +
    "egress — it requires withSPI so the SPI TX FIFO (spiTxFifo) is elaborated and the " +
    "deferred-commit driver has a consumer. (SpiData implies safetyMmioGate ⇒ TripleVoting.)")
  require(!safetyMmioGateSet.contains(MmioGateTarget.UartCfg) ||
          (safetyMmioGateSet.contains(MmioGateTarget.UartThr) &&
           safetyMmioGateSet.contains(MmioGateTarget.UartScr)),
    "MmioGateTarget.UartCfg (16550 config-register gate, O3) is only sound over the " +
    "FULLY voted UART write stream: the monolithic write port routes offset 0/1 by the " +
    "LIVE dlab = lcrReg(7), and a gated (maxLag-deferred) LCR write against a LIVE lead " +
    "offset-0/7 write arm breaks the DLAB-decode timeline and re-opens the lead/deferred " +
    "port-contention window. UartCfg therefore requires UartThr AND UartScr in the set " +
    "(readable design §3.2: re-route addr+data+wen together — a partial re-route is " +
    "wrong by construction).")
  require(!safetyMmioGateSet.contains(MmioGateTarget.I2cCmd) || withI2C,
    "MmioGateTarget.I2cCmd (I2C TX-FIFO deferred-commit) gates the I2C_CMD enqueue " +
    "egress — it requires withI2C so the I2C TX FIFO (i2cTxFifo) is elaborated and the " +
    "deferred-commit driver has a consumer. (I2cCmd implies safetyMmioGate ⇒ TripleVoting.)")
  require(safetyMmioFwdStatusSet.subsetOf(
      Set[MmioGateTarget](MmioGateTarget.UartThr, MmioGateTarget.SpiData, MmioGateTarget.I2cCmd)),
    "safetyMmioFwdStatusSet (O4 forwarded-status view) is defined only for the " +
    "FIFO-enqueue deferred-commit targets whose device status can go stale " +
    "(UartThr → LSR THRE/TEMT + THRE irq, SpiData → SPI_STATUS tx_ready/tx_empty, " +
    "I2cCmd → I2C_STATUS tx_ready/tx_empty). Register sinks (terminal/PLIC/MSIP/" +
    "UART config) have no occupancy status to forward.")
  require(safetyMmioFwdStatusSet.subsetOf(safetyMmioGateSet),
    "safetyMmioFwdStatusSet (O4 forwarded-status view) compensates the staleness " +
    "INTRODUCED by the deferred commit of the same target — each viewed target must " +
    "itself be in safetyMmioGateSet (an un-gated target's live enqueue keeps its " +
    "status exact; a view over it would be wrong). Transitively this pulls in " +
    "safetyMmioGate ⇒ TripleVoting and withSPI/withI2C where applicable.")
  require(!safetyVoterFi || safetyMode == SafetyMode.TripleVoting,
    "safetyVoterFi (voter/egress-mux FI force plane, measurement-only) targets " +
    "the TMR majority voter and its egress consumers; it requires safetyMode = " +
    "TripleVoting (there is no voted value to force in DCLS/None)")
  require(!safetyEgressFi || (safetyMode == SafetyMode.DualLockstep && safetyMmioGate),
    "safetyEgressFi (DCLS egress-branch FI force plane, measurement-only) targets " +
    "the post-fork MMIO egress branch of the DCLS BLOCK gate — the single-instance " +
    "combinational cone between the legsInj(0) fork and the gated destination " +
    "registers, which the xorAB comparator is structurally blind to (it compares " +
    "the fork NODE, not the branch after it). That branch exists only in the " +
    "DualLockstep arm, so the flag requires safetyMode = DualLockstep (the TMR " +
    "equivalent of this fault site is safetyVoterFi's votedEff/deviant planes), " +
    "AND it requires safetyMmioGate: without the gate the egress consumers " +
    "(dEn/dAct/dDat extraction, mmioGateBlockNow) do not elaborate and there is " +
    "nothing to force.")
  require(!safetyWalkRsGate || safetyResync,
    "safetyWalkRsGate (V-2a walk-window re-sync suppression) gates the re-sync " +
    "trigger during the walking-lane proof test; it requires safetyResync " +
    "(without the controller there is no trigger to gate — the walk is already " +
    "clean on non-resync configs)")
  require(!safetyWalkExt || safetyMode == SafetyMode.TripleVoting,
    "safetyWalkExt (V-2b leg-2 walk + deviant one-hot check) walks the third " +
    "TMR leg and checks the 3-bit deviant classify one-hot; it requires " +
    "safetyMode = TripleVoting (DCLS has neither a third leg nor a deviant " +
    "one-hot)")
  require(!safetyEgressCheck || safetyMmioGate,
    "safetyEgressCheck (V-2c egress-slice re-check) re-computes and compares " +
    "the vEn/vAct/vDat egress slices consumed by the MASK muxes/FIFO drivers; " +
    "it requires safetyMmioGate (without the gate those consumers do not " +
    "elaborate and there is nothing to check)")
  require(!safetyEgressCheck || safetyMode == SafetyMode.TripleVoting ||
          (safetyMode == SafetyMode.DualLockstep && safetyMmioGate &&
           safetyMmioGateSet.subsetOf(MmioGateTarget.terminal)),
    "safetyEgressCheck requires safetyMode = TripleVoting, or DualLockstep with safetyMmioGate restricted to the terminal targets")
  require(!safetyEgressBlock || safetyEgressCheck,
    "safetyEgressBlock (V-2c commit-suppress option) blocks the gated egress " +
    "commit on a live slice disagreement; it requires safetyEgressCheck (the " +
    "disagreement signal is the check's output)")
  require(!(safetyEgressBlock && safetyMmioGateSet.contains(MmioGateTarget.PlicCfg)),
    "safetyEgressBlock and MmioGateTarget.PlicCfg do not compose: a blocked PLIC COMPLETE write would leave the source claimed")
  require(!safetyMmioDropCounter || (safetyMmioGate &&
          (safetyMode == SafetyMode.DualLockstep || safetyEgressBlock)),
    "safetyMmioDropCounter (SAFETY_MG drop counter) counts gated MMIO commits " +
    "suppressed by a veto path, so it requires the MMIO egress gate " +
    "(safetyMmioGateSet nonempty) AND at least one veto term to observe: the " +
    "DCLS BLOCK verdict (safetyMode = DualLockstep, mmioGateBlockNow) and/or " +
    "the V-2c egress veto (safetyEgressBlock). Pure TMR MASK never drops — " +
    "the voted value still commits (substitution, not suppression), so a " +
    "counter there would read 0 by construction and falsely suggest the " +
    "drop-observability gap is covered.")
  require(!(withDmaController && safetyMmioGate),
    "withDmaController and safetyMmioGate do not compose: the DMA MMIO master writes the gated peripherals without passing the voted slice")

  require(!(enableCExtension && enableDualIssue),
    "TopParams: C-extension and dual-issue cannot be combined (see §8). " +
    "Got enableCExtension=true, enableDualIssue=true.")
  require(!(enableFExtension && !enableMExtension),
    "TopParams: F-extension requires M-extension in this generator. " +
    "Got enableFExtension=true, enableMExtension=false.")
}

object TopParams extends ConfigPresets
