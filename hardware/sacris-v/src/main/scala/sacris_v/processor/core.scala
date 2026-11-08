// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Core = ControlUnit + Pipeline + system counter.

package sacris_v.processor

import chisel3._
import circt.stage.ChiselStage
import sacris_v.common._

class CoreIo(enableDualIssue: Boolean = false, enableFExtension: Boolean = false, exposeSafetyCommit: Boolean = false, exposeGprSnapshot: Boolean = false, exposeResyncRestore: Boolean = false, exposeResyncInflight: Boolean = false, exposeRegfileScrub: Boolean = false, exposeRegfileScrubSelfCheck: Boolean = false, exposeRegfileEcc: Boolean = false, bootRomWait: Boolean = false, exposeRollbackPc: Boolean = false) extends Bundle
{

   val imem = new BootRomPortIO; val imemWaitReq = if (bootRomWait) Some(Input(Bool())) else None

   val bootRomFlush = if (bootRomWait) Some(Output(Bool())) else None

   val imem_data_hi = if (enableDualIssue) Some(Input(UInt(32.W))) else None

   val safetyCommit = if (exposeSafetyCommit) Some(Output(new SafetyCommitIO(enableDualIssue, enableFExtension, exposeGprSnapshot, withInflight = exposeResyncInflight, exposeRegfileScrub = exposeRegfileScrub, exposeRegfileScrubSelfCheck = exposeRegfileScrubSelfCheck, exposeRegfileEcc = exposeRegfileEcc, withRollbackPc = exposeRollbackPc))) else None

   val rbRestore = if (exposeGprSnapshot) Some(Input(new RollbackRestoreIO(CSR.archStateWidth(enableFExtension), exposeResyncRestore, enableFExtension && exposeResyncRestore, withInflight = exposeResyncInflight))) else None
   val dmem = new MemPortIO(32)
   val counter = new SysCounterIO
   val timerIRQ = Input(Bool())
   val softwareIRQ = Input(Bool())
   val externalIRQ = Input(Bool())
   val faultIRQ = Input(Bool())
   val dmaMemStall = Input(Bool())
   val exMemValid = Output(Bool())
   val exMemAddr  = Output(UInt(32.W))
   val reset_vector = Input(UInt(32.W))

}

abstract class AbstractCore extends Module {

   val hartid: UInt
   val reset_vector: UInt
   val io: Data
}

class Core(
   enableMExtension: Boolean = true,
   enableBExtension: Boolean = true,
   divSteps: Int = 1,
   regOut: Boolean = true,
   withLateLoadForward: Boolean = false,
   dynPredBranch: Boolean = false,
   bhtEntries: Int = 256,
   enableDualIssue: Boolean = false,
   enableCExtension: Boolean = false,
   enableIssueObservability: Boolean = false,
   enableIntraPairForward: Boolean = true,
   enableBranchObserve: Boolean = false,
   enableFExtension: Boolean = false,
   fpDivStepsPerCycle: Int = 1,
   enableCExtensionCycleObserve: Boolean = false,
   exposeSafetyCommit: Boolean = false,
   exposeGprSnapshot: Boolean = false,
   exposeResyncRestore: Boolean = false,
   exposeResyncInflight: Boolean = false,
   exposeRegfileScrub: Boolean = false,
   exposeRegfileScrubSelfCheck: Boolean = false, regfileScrubSelfCheckSweeps: Int = 256, regfileScrubSelfCheckDefeat: Boolean = false,
   exposeRegfileEcc: Boolean = false, bootRomWait: Boolean = false, stallAddrFromReg: Boolean = false, dmemAddrFromAdder: Boolean = false, mduSplitPartials: Boolean = false, minus4FromReg: Boolean = false,
   exposeRollbackPc: Boolean = false
) extends Module
{
   val io = IO(new CoreIo(enableDualIssue, enableFExtension, exposeSafetyCommit, exposeGprSnapshot, exposeResyncRestore, exposeResyncInflight, exposeRegfileScrub, exposeRegfileScrubSelfCheck, exposeRegfileEcc, bootRomWait, exposeRollbackPc))

   val c  = Module(new ControlUnit(enableMExtension, enableBExtension, withLateLoadForward, exposeDecodeBundle = enableDualIssue, enableFExtension = enableFExtension, enableCExtension = enableCExtension))
   val d  = Module(new Pipeline(enableMExtension, enableBExtension, divSteps, regOut, withLateLoadForward, dynPredBranch, bhtEntries, enableDualIssue, enableCExtension, enableIssueObservability, enableIntraPairForward, enableBranchObserve, enableFExtension, fpDivStepsPerCycle, enableCExtensionCycleObserve, exposeSafetyCommit = exposeSafetyCommit, exposeGprSnapshot = exposeGprSnapshot, exposeResyncRestore = exposeResyncRestore, exposeResyncInflight = exposeResyncInflight, exposeRegfileScrub = exposeRegfileScrub, exposeRegfileScrubSelfCheck = exposeRegfileScrubSelfCheck, regfileScrubSelfCheckSweeps = regfileScrubSelfCheckSweeps, regfileScrubSelfCheckDefeat = regfileScrubSelfCheckDefeat, exposeRegfileEcc = exposeRegfileEcc, bootRomWait = bootRomWait, stallAddrFromReg = stallAddrFromReg, dmemAddrFromAdder = dmemAddrFromAdder, mduSplitPartials = mduSplitPartials, minus4FromReg = minus4FromReg, exposeRollbackPc = exposeRollbackPc))

   io.safetyCommit.foreach { _ := d.io.safetyCommit.get }

   io.rbRestore.foreach { d.io.rbRestore.get := _ }

   val counter = Module(new SysCounter)

   c.io.ctl  <> d.io.ctl
   c.io.dat  <> d.io.dat
   c.io.mdu_busy := d.io.mdu_busy
   c.io.wfi_stall := d.io.wfi_stall
   c.io.dmaMemStall := io.dmaMemStall
   io.exMemValid := d.io.exMemValid
   io.exMemAddr  := d.io.exMemAddr

   io.imem <> c.io.imem
   io.imem <> d.io.imem
   d.io.imemWaitReq.foreach(_ := io.imemWaitReq.get)
   io.bootRomFlush.foreach(_ := d.io.bootRomFlush.get)
   if (enableDualIssue) {
      d.io.imem_data_hi.get := io.imem_data_hi.get

      d.io.slotA_dec.get := c.dec.get

      c.slotB_valid.get  := d.io.slotB_valid_out.get
      c.id_rsB1_addr.get := d.io.slotB_rs1_addr.get
      c.id_rsB2_addr.get := d.io.slotB_rs2_addr.get
   }

   io.dmem <> c.io.dmem
   io.dmem <> d.io.dmem

   counter.io <> io.counter

   d.io.sysCnt := counter.io.counter0
   d.io.hartid := 0.U
   d.io.reset_vector := io.reset_vector
   d.io.timerInterrupt := io.timerIRQ
   d.io.softwareInterrupt := io.softwareIRQ
   d.io.externalInterrupt := io.externalIRQ
   d.io.faultDetectInterrupt := io.faultIRQ

   val hartid = 0.U

}
