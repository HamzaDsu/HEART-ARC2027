// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// System top: core(s), memories, bus decode, peripherals, safety wrappers; the main object elaborates a named configuration.

package sacris_v

import chisel3._
import chisel3.util.{Cat, PopCount, PriorityEncoder, Queue, ShiftRegister, UIntToOH, is, log2Ceil, switch}
import circt.stage.ChiselStage
import sacris_v.periphery.{BootRom, BootRom64, DMem, DmaArbiter, DmaController, DmaTestSource, InstructionMem, InstructionMem64, Plic, SevenSegment, SpiMaster, I2cMaster, WatchdogTimer, Uart16550}
import sacris_v.testdevices.{SpiLoopbackSlave, I2cLoopbackSlave}
import sacris_v.processor.{Core, CSR, RollbackRestoreIO, InflightSnapshot}
import sacris_v.common.{MemCmd, MemType}

import java.io.{File, PrintWriter}

class TopLevel(p: TopParams, enableCExtensionCycleObserve: Boolean = false) extends Module {

  val io = IO(new Bundle {
    val GPO      = Output(UInt(16.W))
    val timerIRQ = Output(Bool())
    val faultIRQ = Input(Bool())

    val END = Output(Bool())
    val reset_vector = Input(UInt(32.W))

    val seg = Output(UInt(8.W))
    val an  = Output(UInt(4.W))

    val spi_sclk = Output(Bool())
    val spi_mosi = Output(Bool())
    val spi_miso = Input(Bool())
    val spi_cs   = Output(Bool())
    val spi_dc   = Output(Bool())
    val spi_rst  = Output(Bool())
    val spi_bl   = Output(Bool())

    val i2c_scl    = Output(Bool())
    val i2c_sda_o  = Output(Bool())
    val i2c_sda_oe = Output(Bool())
    val i2c_sda_i  = Input(Bool())

    val uart16550_tx = Output(Bool())
    val uart16550_rx = Input(Bool())

    val host_sel     = if (p.hostMemPort) Some(Input(Bool())) else None
    val host_wr      = if (p.hostMemPort) Some(Input(Bool())) else None
    val host_addr    = if (p.hostMemPort) Some(Input(UInt(32.W))) else None
    val host_data    = if (p.hostMemPort) Some(Input(UInt(32.W))) else None
    val host_is_dmem = if (p.hostMemPort) Some(Input(Bool())) else None

    val safetyMismatch = if (p.safetyMode != SafetyMode.None) Some(Output(Bool())) else None
    val safetyErrIRQ   = if (p.safetyMode != SafetyMode.None) Some(Output(Bool())) else None
    val safetyOutvoted = if (p.safetyMode == SafetyMode.TripleVoting) Some(Output(UInt(3.W))) else None
  })

  private val A = AddressMap

  object MMIO {

    val Base          = A.MMIO_BASE.U(32.W)

    val MSIP          = (A.ACLINT_BASE + A.ACLINT_MSIP_OFFSET).U(32.W)

    val MTIMECMP_LO   = (A.ACLINT_BASE + A.ACLINT_MTIMECMP_LO_OFFSET).U(32.W)
    val MTIMECMP_HI   = (A.ACLINT_BASE + A.ACLINT_MTIMECMP_HI_OFFSET).U(32.W)

    val MTIME_LO      = (A.ACLINT_BASE + A.ACLINT_MTIME_LO_OFFSET).U(32.W)
    val MTIME_HI      = (A.ACLINT_BASE + A.ACLINT_MTIME_HI_OFFSET).U(32.W)

    val COUNTER_LO  = (A.ACLINT_BASE + A.ACLINT_COUNTER_LO_OFFSET).U(32.W)
    val COUNTER_HI  = (A.ACLINT_BASE + A.ACLINT_COUNTER_HI_OFFSET).U(32.W)

    val SIMULATION_END = A.SIMULATION_END_ADDR.U(32.W)
    val IMEM_WDATA = (A.IMEM_WRITER_BASE + A.IMEM_WDATA_OFFSET).U(32.W)
    val IMEM_COUNT = (A.IMEM_WRITER_BASE + A.IMEM_COUNT_OFFSET).U(32.W)

    val SEVENSEG    = A.SEVENSEG_ADDR.U(32.W)
    val SEVENSEG_DP = A.SEVENSEG_DP_ADDR.U(32.W)
    val GPO         = A.GPO_ADDR.U(32.W)

    val SPI_DATA     = (A.SPI_BASE + 0x00).U(32.W)
    val SPI_STATUS   = (A.SPI_BASE + 0x04).U(32.W)
    val SPI_GPIO     = (A.SPI_BASE + 0x08).U(32.W)
    val SPI_CTRL     = (A.SPI_BASE + 0x0C).U(32.W)
    val SPI_RX_DATA  = (A.SPI_BASE + 0x10).U(32.W)
    val SPI_RX_LEVEL = (A.SPI_BASE + 0x14).U(32.W)

    val I2C_CMD    = (A.I2C_BASE + 0x00).U(32.W)
    val I2C_STATUS = (A.I2C_BASE + 0x04).U(32.W)
    val I2C_DATA   = (A.I2C_BASE + 0x08).U(32.W)

    val UART16550_BASE = A.UART16550_BASE
    val UART16550_RBR  = (UART16550_BASE + 0x00).U(32.W)
    val UART16550_IER  = (UART16550_BASE + 0x04).U(32.W)
    val UART16550_IIR  = (UART16550_BASE + 0x08).U(32.W)
    val UART16550_LCR  = (UART16550_BASE + 0x0C).U(32.W)
    val UART16550_MCR  = (UART16550_BASE + 0x10).U(32.W)
    val UART16550_LSR  = (UART16550_BASE + 0x14).U(32.W)
    val UART16550_MSR  = (UART16550_BASE + 0x18).U(32.W)
    val UART16550_SCR  = (UART16550_BASE + 0x1C).U(32.W)

    val PLIC_BASE     = A.PLIC_BASE

    val TEST_IRQ      = A.TEST_IRQ_ADDR.U(32.W)

    val SAFETY_STATUS = (A.SAFETY_BASE + A.SAFETY_STATUS_OFFSET).U(32.W)
    val SAFETY_CTRL   = (A.SAFETY_BASE + A.SAFETY_CTRL_OFFSET).U(32.W)
    val SAFETY_WALK   = (A.SAFETY_BASE + A.SAFETY_WALK_OFFSET).U(32.W)

    val SAFETY_SG     = (A.SAFETY_BASE + A.SAFETY_SG_OFFSET).U(32.W)
    val MEM_ECC       = (A.SAFETY_BASE + A.MEM_ECC_OFFSET).U(32.W)

    val MEM_ECC_ADDR  = (A.SAFETY_BASE + A.MEM_ECC_ADDR_OFFSET).U(32.W)
    val IMEM_SCRUB_ADDR = (A.SAFETY_BASE + A.IMEM_SCRUB_ADDR_OFFSET).U(32.W)

    val SAFETY_CKPT     = (A.SAFETY_BASE + A.SAFETY_CKPT_OFFSET).U(32.W)
    val SAFETY_CKPT_PC  = (A.SAFETY_BASE + A.SAFETY_CKPT_PC_OFFSET).U(32.W)

    val SAFETY_RESYNC    = (A.SAFETY_BASE + A.SAFETY_RESYNC_OFFSET).U(32.W)
    val SAFETY_RESYNC_PC = (A.SAFETY_BASE + A.SAFETY_RESYNC_PC_OFFSET).U(32.W)

    val SAFETY_MG        = (A.SAFETY_BASE + A.SAFETY_MG_OFFSET).U(32.W)

    val DMA_CTRL_BASE  = A.DMA_CTRL_BASE

    val DMA_TEST_SRC_BASE  = A.DMA_TEST_SOURCE_BASE

    val WDT_CTRL      = (A.WDT_BASE + 0x00).U(32.W)
    val WDT_RELOAD    = (A.WDT_BASE + 0x04).U(32.W)
    val WDT_WINDOW    = (A.WDT_BASE + 0x08).U(32.W)
    val WDT_FEED      = (A.WDT_BASE + 0x0C).U(32.W)
    val WDT_STATUS    = (A.WDT_BASE + 0x10).U(32.W)
    val WDT_CHALLENGE = (A.WDT_BASE + 0x14).U(32.W)
    val WDT_RELOAD2   = (A.WDT_BASE + 0x18).U(32.W)
  }

  private def mkCore(): Core = Module(new Core(p.enableMExtension, p.enableBExtension, p.divSteps, p.regOut, p.withLateLoadForward, p.dynPredBranch, p.bhtEntries, p.enableDualIssue, p.enableCExtension, p.enableIssueObservability, p.enableIntraPairForward, p.withBranchObserve, p.enableFExtension, p.fpDivStepsPerCycle, enableCExtensionCycleObserve, exposeSafetyCommit = p.safetyMode != SafetyMode.None, exposeGprSnapshot = p.safetyRollback || p.safetyResync, exposeResyncRestore = p.safetyResync, exposeResyncInflight = p.safetyResyncInflight, exposeRegfileScrub = p.safetyRegfileScrub, exposeRegfileScrubSelfCheck = p.regfileScrubSelfCheck, regfileScrubSelfCheckSweeps = p.regfileScrubSelfCheckSweeps, regfileScrubSelfCheckDefeat = p.regfileScrubSelfCheckDefeat, exposeRegfileEcc = p.safetyRegfileEcc, bootRomWait = p.bootRomRegisteredData, stallAddrFromReg = p.frontendStallAddrFromReg, dmemAddrFromAdder = p.dmemAddrFromAdder, mduSplitPartials = p.mduSplitPartials, minus4FromReg = p.frontendMinus4FromReg, exposeRollbackPc = p.safetyRollback))
  val core = mkCore()

  val bootRom   = if (!p.enableDualIssue) Some(Module(new BootRom(p.bootRomHex, A.BOOTROM_SIZE_WORDS, p.bootRomRegisteredData))) else None
  val bootRom64 = if ( p.enableDualIssue) Some(Module(new BootRom64(p.bootRomHex, A.BOOTROM_SIZE_WORDS, p.bootRomRegisteredData))) else None

  private val storeGateDepth: Int =
    if (p.safetyStoreGate) (p.safetyMode match {
      case SafetyMode.None         => 0
      case SafetyMode.DualLockstep => 1
      case SafetyMode.TripleVoting => 2
    }) * p.lockstepDelay else 0

  val imem   = if (!p.enableDualIssue) Some(Module(new InstructionMem(p.imemSize, None, p.withMemEcc, p.fetchEnableOpen))) else None
  val imem64 = if ( p.enableDualIssue) Some(Module(new InstructionMem64(p.imemSize, None, p.fetchEnableOpen))) else None
  val dmem = Module(new DMem(p.dmemSize, None, storeGateDepth, p.withMemEcc, withRestoreFlush = p.safetyRollback || p.safetyResync))

  core.io.reset_vector := io.reset_vector

  private val mmioFwdPendW: Int = log2Ceil(2 * p.lockstepDelay + 2)
  val uartTxPendingO4 = if (p.mmioFwdStatus(MmioGateTarget.UartThr)) Some(WireDefault(0.U(mmioFwdPendW.W))) else None
  val spiTxPendingO4  = if (p.mmioFwdStatus(MmioGateTarget.SpiData)) Some(WireDefault(0.U(mmioFwdPendW.W))) else None
  val i2cTxPendingO4  = if (p.mmioFwdStatus(MmioGateTarget.I2cCmd))  Some(WireDefault(0.U(mmioFwdPendW.W))) else None

  val uart16550 = Module(new Uart16550(p.clkHz, p.uartBaud, p.uartTxFifoDepth, p.uartRxFifoDepth,
    fwdStatusView = p.mmioFwdStatus(MmioGateTarget.UartThr), fwdStatusPendW = mmioFwdPendW))
  uart16550.io.txPending.foreach(_ := uartTxPendingO4.get)
  val spiMaster = if (p.withSPI) Some(Module(new SpiMaster(p.clkHz, p.spiBaud))) else None
  val i2cMaster = if (p.withI2C) Some(Module(new I2cMaster(p.clkHz, p.i2cBaud))) else None
  val sevenSegment = if (p.withSevenSegment) Some(Module(new SevenSegment())) else None
  val wdt = if (p.withWDT) Some(Module(new WatchdogTimer())) else None

  val dmaMem = if (p.withDmaMem) Some(Module(new DMem(p.dmaMemSize))) else None
  val dmaArb = if (p.withDmaMem) Some(Module(new DmaArbiter(numPorts = 2))) else None
  val dmaCtrl = if (p.withDmaController) Some(Module(new DmaController(log2Ceil(p.dmaMemSize), p.dmaChannels))) else None
  val dmaTestSrcs = if (p.withDmaTestSource) Some((0 until p.dmaTestSourceCount).map(_ => Module(new DmaTestSource()))) else None

  val plic = Module(new Plic(numSources = A.PLIC_NUM_SOURCES))

  val testIrqReg = RegInit(0.U(4.W))

  plic.io.sources(0)  := false.B
  plic.io.sources(1)  := RegNext(uart16550.io.irq, false.B)
  plic.io.sources(2)  := false.B
  plic.io.sources(3)  := false.B
  plic.io.sources(4)  := false.B
  plic.io.sources(5)  := testIrqReg(0)
  plic.io.sources(6)  := testIrqReg(1)
  plic.io.sources(7)  := testIrqReg(2)
  plic.io.sources(8)  := testIrqReg(3)

  for (i <- 9 to 12) {
    val chIdx = i - 9
    plic.io.sources(i) := dmaCtrl.map { c =>
      if (chIdx < p.dmaChannels) RegNext(c.io.irq(chIdx), false.B) else false.B
    }.getOrElse(false.B)
  }
  for (i <- 13 to 15) { plic.io.sources(i) := false.B }

  plic.io.wen   := false.B
  plic.io.ren   := false.B
  plic.io.addr  := 0.U
  plic.io.wdata := 0.U

  core.io.externalIRQ := plic.io.mei

  private val bootMode = RegInit(true.B)
  when (core.io.imem.addr(31).asBool) {
    bootMode := false.B
  }
  private val imemWrPtr   = RegInit(0.U(32.W))
  private val imemWrPtr_z1   = RegInit(0.U(32.W))

  private val scrubArmedReg = if (p.withMemEcc) Some(RegInit(false.B)) else None

  private val gpoValReg      = RegInit(0.U(16.W))

  private val aclint_mtimecmp = RegInit(p.mtimecmpReset.U(64.W))
  private val aclint_mtime    = RegInit(0.U(64.W))
  private val aclint_msip     = RegInit(false.B)
  private val simulation_end = RegInit(false.B)
  private val imem_wdataReg = RegInit(0.U(32.W))
  private val imem_wrEn_reg = RegInit(false.B)
  private val imem_wrEn_reg_z1 = RegInit(false.B)

  io.END := simulation_end

  private val dmemAddrMMIOReg = RegInit(0.U(32.W))
  dmemAddrMMIOReg := core.io.dmem.req.addr

  private val isDmaMemRegion = if (p.withDmaMem) {
    core.io.dmem.req.addr(31, 24) === (A.DMA_MEM_BASE >> 24).U
  } else false.B
  private val isDmaMemRegionReg = RegNext(isDmaMemRegion, false.B)

  private val isDmaCtrlRegion = if (p.withDmaController) {
    val addr = core.io.dmem.req.addr
    val inBase = addr(31, 16) === (A.DMA_CTRL_BASE >> 16).U
    val chIdx = addr(15, 8)
    val regOk = addr(7, 5) === 0.U
    inBase && (chIdx < p.dmaChannels.U) && regOk
  } else false.B
  private val isDmaCtrlRegionReg = RegNext(isDmaCtrlRegion, false.B)

  private val isDmaTestSrcRegion = if (p.withDmaTestSource) {
    val addr = core.io.dmem.req.addr
    val inBase = addr(31, 16) === (A.DMA_TEST_SOURCE_BASE >> 16).U
    val srcIdx = addr(15, 8)
    val regOk = addr(7, 5) === 0.U
    inBase && (srcIdx < p.dmaTestSourceCount.U) && regOk
  } else false.B
  private val isDmaTestSrcRegionReg = RegNext(isDmaTestSrcRegion, false.B)

  private val isDmaMemRegionEx = if (p.withDmaMem) {
    core.io.exMemValid && (core.io.exMemAddr(31, 24) === (A.DMA_MEM_BASE >> 24).U)
  } else false.B

  if (p.withDmaMem) {
    val arb = dmaArb.get
    val mem = dmaMem.get

    arb.io.portReq(0) := core.io.dmem.req
    arb.io.portEn(0)  := isDmaMemRegionEx

    if (p.withDmaController) {
      val ctrl = dmaCtrl.get

      val addrMsb = log2Ceil(p.dmaMemSize) + 1
      arb.io.portReq(1).addr := ctrl.io.dmaMemAddr
      arb.io.portReq(1).data := ctrl.io.dmaMemWdata
      arb.io.portReq(1).cmd  := Mux(ctrl.io.dmaMemWr, MemCmd.Write, MemCmd.Read)
      arb.io.portReq(1).typ  := ctrl.io.dmaMemTyp
      arb.io.portEn(1)       := ctrl.io.dmaMemValid
      ctrl.io.dmaMemStall    := arb.io.stall(1)
      ctrl.io.dmaMemRdata    := mem.io.data
    } else if (p.testDmaArbContention) {
      val contentionToggle = RegInit(false.B)
      contentionToggle := !contentionToggle
      arb.io.portReq(1)      := 0.U.asTypeOf(arb.io.portReq(1))
      arb.io.portReq(1).addr := A.DMA_MEM_BASE.U
      arb.io.portReq(1).cmd  := MemCmd.Read
      arb.io.portReq(1).typ  := MemType.W
      arb.io.portEn(1)       := contentionToggle
    } else {
      arb.io.portReq(1) := 0.U.asTypeOf(arb.io.portReq(1))
      arb.io.portEn(1)  := false.B
    }

    mem.io.req  := arb.io.memReq
    mem.io.en   := arb.io.memEn
  }

  dmaCtrl.foreach { ctrl =>
    if (p.dmaChannels == 1) {
      ctrl.io.regAddr  := core.io.dmem.req.addr(4, 0)
    } else {
      ctrl.io.regAddr  := core.io.dmem.req.addr(log2Ceil(p.dmaChannels * 256) - 1, 0)
    }
    ctrl.io.regWdata := core.io.dmem.req.data
    ctrl.io.regWen   := false.B
    ctrl.io.regRen   := false.B
  }

  dmaCtrl.foreach { ctrl =>
    for (i <- 0 until p.dmaChannels) {
      ctrl.io.dreq(i) := {
        dmaTestSrcs match {
          case Some(srcs) if i < srcs.length => srcs(i).io.dreq
          case _ => false.B
        }
      }
    }
  }

  dmaTestSrcs.foreach { srcs =>
    for (src <- srcs) {
      src.io.regAddr  := core.io.dmem.req.addr(4, 0)
      src.io.regWdata := core.io.dmem.req.data
      src.io.regWen   := false.B
      src.io.regRen   := false.B
    }
  }

  val dmaMMioActive = dmaCtrl.map(_.io.mmioValid).getOrElse(false.B)
  val dmaMMioGranted = WireDefault(false.B)
  val dmaMMioAddrReg = RegInit(0.U(32.W))
  val dmaMMioGrantedReg = RegNext(dmaMMioGranted, false.B)

  dmaCtrl.foreach(_.io.mmioRdata := 0.U)

  imem_wrEn_reg_z1 := imem_wrEn_reg
  imemWrPtr_z1 := imemWrPtr
  imem_wrEn_reg := false.B

  val selApp = core.io.imem.addr(31).asBool
  val selApp_d = RegNext(selApp, init=false.B)

  private def bootRomEn: Bool =
    if (!p.fetchEnableOpen) !selApp
    else if (p.bootRomRegisteredData) !selApp_d
    else true.B

  val scrubAllowed = scrubArmedReg.map(bootMode || _).getOrElse(bootMode)

  if (!p.enableDualIssue) {

    bootRom.get.io.addr := core.io.imem.addr
    imem.get.io.addr    := core.io.imem.addr

    if (p.hostMemPort) {

      val hsel = io.host_sel.get && !io.host_is_dmem.get
      imem.get.io.wr      := Mux(hsel, io.host_wr.get, imem_wrEn_reg && scrubAllowed)
      imem.get.io.data_in := Mux(hsel, io.host_data.get, imem_wdataReg)
      imem.get.io.waddr   := Mux(hsel, io.host_addr.get, imemWrPtr_z1)
    } else {
      imem.get.io.wr := imem_wrEn_reg && scrubAllowed
      imem.get.io.data_in := imem_wdataReg
      imem.get.io.waddr := imemWrPtr_z1
    }

    core.io.imem.data := Mux(selApp_d, imem.get.io.data, bootRom.get.io.data)

    core.io.imemWaitReq.foreach(_ := !selApp_d && bootRom.get.waitReq.get)

    bootRom.get.flush.foreach(_ := core.io.bootRomFlush.get)
    bootRom.get.io.en := bootRomEn
    imem.get.io.en    := selApp
  } else {

    bootRom64.get.io.addr := core.io.imem.addr
    imem64.get.io.addr    := core.io.imem.addr

    if (p.hostMemPort) {
      val hsel = io.host_sel.get && !io.host_is_dmem.get
      imem64.get.io.wr      := Mux(hsel, io.host_wr.get, imem_wrEn_reg && scrubAllowed)
      imem64.get.io.data_in := Mux(hsel, io.host_data.get, imem_wdataReg)
      imem64.get.io.waddr   := Mux(hsel, io.host_addr.get, imemWrPtr_z1)
    } else {
      imem64.get.io.wr := imem_wrEn_reg && scrubAllowed
      imem64.get.io.data_in := imem_wdataReg
      imem64.get.io.waddr := imemWrPtr_z1
    }

    val fetch_data_64 = Mux(selApp_d, imem64.get.io.data, bootRom64.get.io.data)
    core.io.imem.data          := fetch_data_64(31, 0)
    core.io.imem_data_hi.get   := fetch_data_64(63, 32)
    core.io.imemWaitReq.foreach(_ := !selApp_d && bootRom64.get.waitReq.get)
    bootRom64.get.flush.foreach(_ := core.io.bootRomFlush.get)
    bootRom64.get.io.en := bootRomEn
    imem64.get.io.en    := selApp
  }

  dmem.io.req <> core.io.dmem.req

  private val aclint_prescale = RegInit(0.U(log2Ceil(p.timerTickCompare).W))
  private val aclint_tick     = aclint_prescale === (p.timerTickCompare - 1).U
  aclint_prescale := Mux(aclint_tick, 0.U, aclint_prescale + 1.U)
  when(aclint_tick) {
    aclint_mtime := aclint_mtime + 1.U
  }

  val timerIrq = aclint_mtime >= aclint_mtimecmp
  io.timerIRQ := timerIrq
  core.io.timerIRQ := timerIrq

  core.io.softwareIRQ := aclint_msip

  private val safetyErrIrq: Option[Bool] =
    if (p.safetyMode != SafetyMode.None) Some(WireDefault(false.B)) else None

  private val memEccFaultIrq: Option[Bool] =
    if (p.withMemEcc) Some(WireDefault(false.B)) else None

  (safetyErrIrq, memEccFaultIrq) match {
    case (None, None) =>

      core.io.faultIRQ := io.faultIRQ || wdt.map(w => RegNext(w.io.hardReset, false.B)).getOrElse(false.B)
    case (Some(err), None) =>
      core.io.faultIRQ := io.faultIRQ || wdt.map(w => RegNext(w.io.hardReset, false.B)).getOrElse(false.B) || err
    case (None, Some(ecc)) =>
      core.io.faultIRQ := io.faultIRQ || wdt.map(w => RegNext(w.io.hardReset, false.B)).getOrElse(false.B) || ecc
    case (Some(err), Some(ecc)) =>
      core.io.faultIRQ := io.faultIRQ || wdt.map(w => RegNext(w.io.hardReset, false.B)).getOrElse(false.B) || err || ecc
  }

  core.io.dmaMemStall := dmaArb.map(_.io.stall(0)).getOrElse(false.B)

  io.uart16550_tx := uart16550.io.txd
  uart16550.io.rxd := io.uart16550_rx

  uart16550.io.addr    := core.io.dmem.req.addr(4, 2)
  uart16550.io.addrReg := Mux(dmaMMioGrantedReg, dmaMMioAddrReg(4, 2), dmemAddrMMIOReg(4, 2))
  uart16550.io.wdata   := core.io.dmem.req.data(7, 0)

  uart16550.io.wen := false.B
  uart16550.io.ren := false.B

  val isLoad = core.io.dmem.en && (core.io.dmem.req.cmd === MemCmd.Read)

  private val uart16550Region = core.io.dmem.req.addr(31, 5) === (A.UART16550_BASE >> 5).U
  when(isLoad && uart16550Region) {
    uart16550.io.ren := true.B
  }

  private val plicRegion = core.io.dmem.req.addr(31, 24) === (A.PLIC_BASE >> 24).U
  private val plicRegionReg = dmemAddrMMIOReg(31, 24) === (A.PLIC_BASE >> 24).U

  private val plicOffset    = core.io.dmem.req.addr(21, 0)
  private val plicOffsetReg = dmemAddrMMIOReg(21, 0)

  when(isLoad && plicRegion) {
    plic.io.ren  := true.B
    plic.io.addr := plicOffset
  }

  when(isLoad && isDmaCtrlRegion) {
    dmaCtrl.foreach { ctrl =>
      ctrl.io.regRen := true.B
      if (p.dmaChannels == 1) {
        ctrl.io.regAddr := core.io.dmem.req.addr(4, 0)
      } else {
        ctrl.io.regAddr := core.io.dmem.req.addr(log2Ceil(p.dmaChannels * 256) - 1, 0)
      }
    }
  }

  when(isLoad && isDmaTestSrcRegion) {
    dmaTestSrcs.foreach { srcs =>
      val srcIdx = core.io.dmem.req.addr(15, 8)
      for (i <- srcs.indices) {
        when(srcIdx === i.U) {
          srcs(i).io.regRen  := true.B
          srcs(i).io.regAddr := core.io.dmem.req.addr(4, 0)
        }
      }
    }
  }

  if (p.withDmaController) {
    val ctrl = dmaCtrl.get

    val coreMmioAccess = core.io.dmem.en && (core.io.dmem.req.addr >= MMIO.Base) &&
                         !isDmaMemRegion && !plicRegion && !isDmaCtrlRegion && !isDmaTestSrcRegion

    dmaMMioGranted := dmaMMioActive && !coreMmioAccess
    ctrl.io.mmioReady := dmaMMioGranted

    when(dmaMMioGranted) {
      dmaMMioAddrReg := ctrl.io.mmioAddr
    }

    when(dmaMMioGranted && !ctrl.io.mmioWr) {

      val dmaUart16550 = ctrl.io.mmioAddr(31, 5) === (A.UART16550_BASE >> 5).U
      when(dmaUart16550) {
        uart16550.io.addr := ctrl.io.mmioAddr(4, 2)
        uart16550.io.ren  := true.B
      }

      if (p.withDmaTestSource) {
        val dmaDmaTestSrcBase = ctrl.io.mmioAddr(31, 16) === (A.DMA_TEST_SOURCE_BASE >> 16).U
        val dmaSrcIdx = ctrl.io.mmioAddr(15, 8)
        when(dmaDmaTestSrcBase) {
          dmaTestSrcs.get.zipWithIndex.foreach { case (src, i) =>
            when(dmaSrcIdx === i.U) {
              src.io.regAddr := ctrl.io.mmioAddr(4, 0)
              src.io.regRen  := true.B
            }
          }
        }
      }
    }
  }

  val spiDataGateCommit = if (p.mmioGated(MmioGateTarget.SpiData)) Some(WireDefault(false.B)) else None
  val spiDataGateByte   = if (p.mmioGated(MmioGateTarget.SpiData)) Some(WireDefault(0.U(8.W)))  else None
  val i2cCmdGateCommit  = if (p.mmioGated(MmioGateTarget.I2cCmd))  Some(WireDefault(false.B)) else None
  val i2cCmdGateBits    = if (p.mmioGated(MmioGateTarget.I2cCmd))  Some(WireDefault(0.U(11.W))) else None

  val (spiTxReady, spiTxEmpty, spiBusy, spiGpioReg,
       spiRxReady, spiRxFull, spiRxOverflow,
       spiLoopbackMode, spiRxDataLatch, spiRxLevel) = if (p.withSPI) {
    val spiTxFifo = Module(new Queue(UInt(8.W), entries = p.spiTxFifoDepth))
    val spiRxFifo = Module(new Queue(UInt(8.W), entries = p.spiRxFifoDepth))
    val spi = spiMaster.get
    val gpioReg = RegInit("b0101".U(4.W))

    val txReady = WireDefault(false.B)
    val txEmpty = WireDefault(true.B)
    val busy    = WireDefault(false.B)

    val rxOverflow   = RegInit(false.B)
    val loopbackMode = RegInit(false.B)
    val rxDataLatch  = RegInit(0.U(8.W))
    val rxLevelCnt   = RegInit(0.U(log2Ceil(p.spiRxFifoDepth + 1).W))
    val rxFifoDrain  = RegInit(false.B)

    spiTxFifo.io.enq.valid := false.B
    spiTxFifo.io.enq.bits  := 0.U

    spi.io.tx_valid        := spiTxFifo.io.deq.valid
    spi.io.tx_data         := spiTxFifo.io.deq.bits
    spiTxFifo.io.deq.ready := spi.io.tx_ready

    txReady := spiTxFifo.io.enq.ready
    txEmpty := !spiTxFifo.io.deq.valid && !spi.io.busy

    if (p.mmioFwdStatus(MmioGateTarget.SpiData)) {
      txReady := (spiTxFifo.io.count +& spiTxPendingO4.get) < p.spiTxFifoDepth.U
      txEmpty := !spiTxFifo.io.deq.valid && !spi.io.busy && (spiTxPendingO4.get === 0.U)
    }
    busy    := spi.io.busy

    spi.io.miso := Mux(loopbackMode, spi.io.mosi, io.spi_miso)

    io.spi_sclk := spi.io.sclk
    io.spi_mosi := spi.io.mosi
    io.spi_cs   := gpioReg(0)
    io.spi_dc   := gpioReg(1)
    io.spi_rst  := gpioReg(2)
    io.spi_bl   := gpioReg(3)

    if (p.withSpiLoopback) {
      val spiSlave = Module(new SpiLoopbackSlave)
      spiSlave.io.cs   := gpioReg(0)
      spiSlave.io.sclk := spi.io.sclk
      spiSlave.io.mosi := spi.io.mosi

      when(!loopbackMode) {
        spi.io.miso := spiSlave.io.miso
      }
    }

    spiRxFifo.io.enq.valid := false.B
    spiRxFifo.io.enq.bits  := 0.U
    spiRxFifo.io.deq.ready := false.B

    when(rxFifoDrain) {
      when(spiRxFifo.io.deq.valid) {
        spiRxFifo.io.deq.ready := true.B
      }.otherwise {
        rxFifoDrain := false.B
      }
    }

    when(spi.io.rx_valid && !rxFifoDrain) {
      when(spiRxFifo.io.enq.ready) {
        spiRxFifo.io.enq.valid := true.B
        spiRxFifo.io.enq.bits  := spi.io.rx_data
      }.otherwise {
        rxOverflow := true.B
      }
    }

    val rxEnq = spiRxFifo.io.enq.valid && spiRxFifo.io.enq.ready
    val rxDeq = spiRxFifo.io.deq.valid && spiRxFifo.io.deq.ready
    when(rxEnq && !rxDeq) {
      rxLevelCnt := rxLevelCnt + 1.U
    }.elsewhen(!rxEnq && rxDeq) {
      rxLevelCnt := rxLevelCnt - 1.U
    }

    val isMmioWr = core.io.dmem.req.addr >= MMIO.Base
    val isSt     = core.io.dmem.en && (core.io.dmem.req.cmd === MemCmd.Write)

    when(isMmioWr && isSt && (core.io.dmem.req.addr === MMIO.SPI_DATA)) {
      if (!p.mmioGated(MmioGateTarget.SpiData)) {
        spiTxFifo.io.enq.valid := true.B
        spiTxFifo.io.enq.bits  := core.io.dmem.req.data(7, 0)
      }
    }

    when(isMmioWr && isSt && (core.io.dmem.req.addr === MMIO.SPI_CTRL)) {
      loopbackMode := core.io.dmem.req.data(0)
      when(core.io.dmem.req.data(1)) {
        rxFifoDrain := true.B
        rxOverflow  := false.B
      }
      when(core.io.dmem.req.data(2)) {
        rxOverflow := false.B
      }
    }

    when(isLoad && (core.io.dmem.req.addr === MMIO.SPI_RX_DATA) && !rxFifoDrain) {
      when(spiRxFifo.io.deq.valid) {
        rxDataLatch := spiRxFifo.io.deq.bits
        spiRxFifo.io.deq.ready := true.B
      }
    }

    if (p.withDmaController) {
      val ctrl = dmaCtrl.get
      when(dmaMMioGranted && ctrl.io.mmioWr && (ctrl.io.mmioAddr === MMIO.SPI_DATA)) {
        spiTxFifo.io.enq.valid := true.B
        spiTxFifo.io.enq.bits  := ctrl.io.mmioWdata(7, 0)
      }
    }

    spiDataGateCommit.foreach { commit =>
      when(commit) {
        spiTxFifo.io.enq.valid := true.B
        spiTxFifo.io.enq.bits  := spiDataGateByte.get
      }
    }

    (txReady, txEmpty, busy, Some(gpioReg),
     spiRxFifo.io.deq.valid, !spiRxFifo.io.enq.ready, rxOverflow,
     loopbackMode, rxDataLatch, rxLevelCnt)
  } else {

    io.spi_sclk := false.B
    io.spi_mosi := false.B
    io.spi_cs   := true.B
    io.spi_dc   := false.B
    io.spi_rst  := true.B
    io.spi_bl   := false.B

    (WireDefault(false.B), WireDefault(true.B), WireDefault(false.B), None,
     WireDefault(false.B), WireDefault(false.B), WireDefault(false.B),
     WireDefault(false.B), WireDefault(0.U(8.W)), WireDefault(0.U(1.W)))
  }

  val (i2cTxReady, i2cTxEmpty, i2cBusy, i2cAck, i2cRxValid, i2cRxLatch) = if (p.withI2C) {
    val i2cTxFifo = Module(new Queue(UInt(11.W), entries = p.i2cFifoDepth))
    val i2cRxFifo = Module(new Queue(UInt(8.W),  entries = p.i2cFifoDepth))
    val i2c = i2cMaster.get
    val rxLatch = RegInit(0.U(8.W))

    val txReady = WireDefault(false.B)
    val txEmpty = WireDefault(true.B)
    val busy    = WireDefault(false.B)
    val ack     = WireDefault(false.B)
    val rxValid = WireDefault(false.B)

    i2cTxFifo.io.enq.valid := false.B
    i2cTxFifo.io.enq.bits  := 0.U

    i2c.io.cmd_valid := i2cTxFifo.io.deq.valid
    i2c.io.cmd_bits  := i2cTxFifo.io.deq.bits
    i2cTxFifo.io.deq.ready := i2c.io.cmd_ready

    i2cRxFifo.io.enq.valid := i2c.io.rx_valid
    i2cRxFifo.io.enq.bits  := i2c.io.rx_bits

    txReady := i2cTxFifo.io.enq.ready
    txEmpty := !i2cTxFifo.io.deq.valid && !i2c.io.busy

    if (p.mmioFwdStatus(MmioGateTarget.I2cCmd)) {
      txReady := (i2cTxFifo.io.count +& i2cTxPendingO4.get) < p.i2cFifoDepth.U
      txEmpty := !i2cTxFifo.io.deq.valid && !i2c.io.busy && (i2cTxPendingO4.get === 0.U)
    }
    busy    := i2c.io.busy
    ack     := i2c.io.ack
    rxValid := i2cRxFifo.io.deq.valid

    i2cRxFifo.io.deq.ready := false.B

    when(isLoad && (core.io.dmem.req.addr === MMIO.I2C_DATA)) {
      when(i2cRxFifo.io.deq.valid) {
        rxLatch := i2cRxFifo.io.deq.bits
        i2cRxFifo.io.deq.ready := true.B
      }
    }

    if (p.withI2cLoopback) {

      val i2cSlave = Module(new I2cLoopbackSlave(slaveAddr = 0x50))
      i2cSlave.io.scl := i2c.io.scl

      val masterDrivesLow = i2c.io.sda_oe
      val slaveDrivesLow  = i2cSlave.io.sda_oe
      val sdaBus = !(masterDrivesLow || slaveDrivesLow)

      i2c.io.sda_in      := sdaBus
      i2cSlave.io.sda_in := sdaBus

      io.i2c_scl    := i2c.io.scl
      io.i2c_sda_o  := true.B
      io.i2c_sda_oe := false.B
    } else {
      io.i2c_scl    := i2c.io.scl
      io.i2c_sda_o  := i2c.io.sda_out
      io.i2c_sda_oe := i2c.io.sda_oe
      i2c.io.sda_in := io.i2c_sda_i
    }

    val isMmioWr = core.io.dmem.req.addr >= MMIO.Base
    val isSt     = core.io.dmem.en && (core.io.dmem.req.cmd === MemCmd.Write)

    when(isMmioWr && isSt && (core.io.dmem.req.addr === MMIO.I2C_CMD)) {
      if (!p.mmioGated(MmioGateTarget.I2cCmd)) {
        i2cTxFifo.io.enq.valid := true.B
        i2cTxFifo.io.enq.bits  := core.io.dmem.req.data(15, 8) ## core.io.dmem.req.data(2, 0)
      }
    }

    if (p.withDmaController) {
      val ctrl = dmaCtrl.get
      when(dmaMMioGranted && ctrl.io.mmioWr && (ctrl.io.mmioAddr === MMIO.I2C_CMD)) {
        i2cTxFifo.io.enq.valid := true.B
        i2cTxFifo.io.enq.bits  := ctrl.io.mmioWdata(15, 8) ## ctrl.io.mmioWdata(2, 0)
      }
    }

    i2cCmdGateCommit.foreach { commit =>
      when(commit) {
        i2cTxFifo.io.enq.valid := true.B
        i2cTxFifo.io.enq.bits  := i2cCmdGateBits.get
      }
    }

    (txReady, txEmpty, busy, ack, rxValid, Some(rxLatch))
  } else {

    io.i2c_scl    := true.B
    io.i2c_sda_o  := true.B
    io.i2c_sda_oe := false.B

    (WireDefault(false.B), WireDefault(true.B), WireDefault(false.B),
     WireDefault(false.B), WireDefault(false.B), None)
  }

  val (sevenSegmentReg, dpReg) = if (p.withSevenSegment) {
    val segReg = RegInit(0.U(32.W))
    val dp = RegInit(VecInit(Seq.fill(4)(false.B)))
    val seg = sevenSegment.get
    seg.io.in := segReg
    seg.io.dp := dp

    io.seg := RegNext(seg.io.seg, "hFF".U(8.W))
    io.an  := RegNext(seg.io.an,  "hF".U(4.W))
    (Some(segReg), Some(dp))
  } else {

    io.seg := "hFF".U
    io.an  := "hF".U
    (None, None)
  }

  val mmioDecodeAddr = Mux(dmaMMioGrantedReg, dmaMMioAddrReg, dmemAddrMMIOReg)
  val mmioVal = WireDefault(1.U(32.W))
  switch(mmioDecodeAddr) {

    is(MMIO.MSIP)         { mmioVal := Cat(0.U(31.W), aclint_msip) }

    is(MMIO.MTIMECMP_LO)  { mmioVal := aclint_mtimecmp(31, 0) }
    is(MMIO.MTIMECMP_HI)  { mmioVal := aclint_mtimecmp(63, 32) }
    is(MMIO.MTIME_LO)     { mmioVal := aclint_mtime(31, 0) }
    is(MMIO.MTIME_HI)     { mmioVal := aclint_mtime(63, 32) }

    is(MMIO.COUNTER_LO) { mmioVal := core.io.counter.counter0(31, 0) }
    is(MMIO.COUNTER_HI) { mmioVal := core.io.counter.counter0(63, 32) }

    is(MMIO.IMEM_COUNT) {mmioVal := imemWrPtr}

    is(MMIO.SPI_STATUS)   { mmioVal := (if (p.withSPI) Cat(0.U(26.W), spiRxOverflow, spiRxFull, spiRxReady, spiBusy, spiTxEmpty, spiTxReady) else 0.U) }
    is(MMIO.SPI_GPIO)     { mmioVal := spiGpioReg.map(r => Cat(0.U(28.W), r)).getOrElse(0.U) }
    is(MMIO.SPI_CTRL)     { mmioVal := (if (p.withSPI) Cat(0.U(31.W), spiLoopbackMode) else 0.U) }
    is(MMIO.SPI_RX_DATA)  { mmioVal := (if (p.withSPI) Cat(0.U(24.W), spiRxDataLatch) else 0.U) }
    is(MMIO.SPI_RX_LEVEL) { mmioVal := (if (p.withSPI) spiRxLevel else 0.U) }

    is(MMIO.I2C_STATUS) { mmioVal := (if (p.withI2C) Cat(0.U(27.W), i2cRxValid, i2cAck, i2cBusy, i2cTxEmpty, i2cTxReady) else 0.U) }
    is(MMIO.I2C_DATA)   { mmioVal := i2cRxLatch.getOrElse(0.U) }

    is(MMIO.WDT_STATUS)    { mmioVal := wdt.map(_.io.status_rdata).getOrElse(0.U) }
    is(MMIO.WDT_CHALLENGE) { mmioVal := wdt.map(_.io.challenge_rdata).getOrElse(0.U) }

    is(MMIO.TEST_IRQ) { mmioVal := Cat(0.U(28.W), testIrqReg) }

    is(MMIO.UART16550_RBR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_IER) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_IIR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_LCR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_MCR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_LSR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_MSR) { mmioVal := uart16550.io.rdata }
    is(MMIO.UART16550_SCR) { mmioVal := uart16550.io.rdata }
  }

  dmaCtrl.foreach { ctrl =>
    when(dmaMMioGrantedReg) {

      val dmaReadTestSrc = dmaMMioAddrReg(31, 16) === (A.DMA_TEST_SOURCE_BASE >> 16).U
      when(dmaReadTestSrc) {
        val dmaSrcIdxReg = dmaMMioAddrReg(15, 8)
        ctrl.io.mmioRdata := 0.U
        dmaTestSrcs.foreach { srcs =>
          for (i <- srcs.indices) {
            when(dmaSrcIdxReg === i.U) {
              ctrl.io.mmioRdata := srcs(i).io.regRdata
            }
          }
        }
      }.otherwise {
        ctrl.io.mmioRdata := mmioVal
      }
    }
  }

  when(plicRegionReg) {
    core.io.dmem.data := plic.io.rdata
  }.elsewhen(isDmaMemRegionReg) {
    core.io.dmem.data := dmaMem.map(_.io.data).getOrElse(0.U)
  }.elsewhen(isDmaCtrlRegionReg) {
    core.io.dmem.data := dmaCtrl.map(_.io.regRdata).getOrElse(0.U)
  }.elsewhen(isDmaTestSrcRegionReg) {

    val srcIdxReg = RegNext(core.io.dmem.req.addr(15, 8), 0.U)
    val testSrcRdata = WireDefault(0.U(32.W))
    dmaTestSrcs.foreach { srcs =>
      for (i <- srcs.indices) {
        when(srcIdxReg === i.U) {
          testSrcRdata := srcs(i).io.regRdata
        }
      }
    }
    core.io.dmem.data := testSrcRdata
  }.elsewhen(dmemAddrMMIOReg >= MMIO.Base) {
    core.io.dmem.data := mmioVal
  }.otherwise {
    core.io.dmem.data := dmem.io.data
  }

  wdt.foreach { w =>
    w.io.ctrl_wen   := false.B
    w.io.ctrl_wdata := core.io.dmem.req.data
    w.io.reload_wen   := false.B
    w.io.reload_wdata := core.io.dmem.req.data
    w.io.window_wen   := false.B
    w.io.window_wdata := core.io.dmem.req.data
    w.io.feed_wen   := false.B
    w.io.feed_wdata := core.io.dmem.req.data
    w.io.reload2_wen   := false.B
    w.io.reload2_wdata := core.io.dmem.req.data
  }

  private val dmemDecodeAddr =
    if (p.dmemAddrFromAdder) core.io.exMemAddr else core.io.dmem.req.addr
  private val plicRegionWr = if (p.dmemAddrFromAdder) {
    dmemDecodeAddr(31, 24) === (A.PLIC_BASE >> 24).U
  } else plicRegion

  val isMmioWriteRegion = dmemDecodeAddr >= MMIO.Base
  val isStore = core.io.dmem.en && (core.io.dmem.req.cmd === MemCmd.Write)

  dmem.io.en := core.io.dmem.en

  when(plicRegionWr) {
    dmem.io.en := false.B
    when(isStore) {

      if (!p.mmioGated(MmioGateTarget.PlicCfg)) {
        plic.io.wen   := true.B
        plic.io.addr  := plicOffset
        plic.io.wdata := core.io.dmem.req.data
      }
    }
  }.elsewhen(isDmaMemRegion) {

    dmem.io.en := false.B
  }.elsewhen(isDmaCtrlRegion) {

    dmem.io.en := false.B
    when(isStore) {
      dmaCtrl.foreach { ctrl =>
        ctrl.io.regWen := true.B
        if (p.dmaChannels == 1) {
          ctrl.io.regAddr := core.io.dmem.req.addr(4, 0)
        } else {
          ctrl.io.regAddr := core.io.dmem.req.addr(log2Ceil(p.dmaChannels * 256) - 1, 0)
        }
      }
    }
  }.elsewhen(isDmaTestSrcRegion) {

    dmem.io.en := false.B
    when(isStore) {
      dmaTestSrcs.foreach { srcs =>
        val srcIdx = core.io.dmem.req.addr(15, 8)
        for (i <- srcs.indices) {
          when(srcIdx === i.U) {
            srcs(i).io.regWen  := true.B
            srcs(i).io.regAddr := core.io.dmem.req.addr(4, 0)
          }
        }
      }
    }
  }.elsewhen(isMmioWriteRegion) {
    dmem.io.en := false.B

    when(isStore) {

      when(dmemDecodeAddr === MMIO.TEST_IRQ) {
        testIrqReg := core.io.dmem.req.data(3, 0)
      }

      switch(dmemDecodeAddr) {

        is(MMIO.MSIP) { if (!p.mmioGated(MmioGateTarget.AclintMsip)) aclint_msip := core.io.dmem.req.data(0).asBool }

        is(MMIO.MTIMECMP_LO) { aclint_mtimecmp := Cat(aclint_mtimecmp(63, 32), core.io.dmem.req.data) }
        is(MMIO.MTIMECMP_HI) { aclint_mtimecmp := Cat(core.io.dmem.req.data, aclint_mtimecmp(31, 0)) }
        is(MMIO.MTIME_LO)    { aclint_mtime := Cat(aclint_mtime(63, 32), core.io.dmem.req.data) }
        is(MMIO.MTIME_HI)    { aclint_mtime := Cat(core.io.dmem.req.data, aclint_mtime(31, 0)) }

        is(MMIO.SEVENSEG) { if (!p.mmioGated(MmioGateTarget.SevenSeg)) sevenSegmentReg.foreach(_ := core.io.dmem.req.data) }
        is(MMIO.SEVENSEG_DP) { if (!p.mmioGated(MmioGateTarget.SevenSegDp)) dpReg.foreach(_ := core.io.dmem.req.data(3,0).asBools) }
        is(MMIO.GPO)      { if (!p.mmioGated(MmioGateTarget.Gpo)) gpoValReg      := core.io.dmem.req.data(15, 0) }
        is(MMIO.SIMULATION_END) {
          if (!p.mmioGated(MmioGateTarget.SimEnd)) simulation_end := core.io.dmem.req.data(0).asBool

        }
        is(MMIO.IMEM_WDATA) {
          imem_wdataReg := core.io.dmem.req.data
          imemWrPtr := imemWrPtr + 1.U
          imem_wrEn_reg := true.B
        }

        is(MMIO.SPI_GPIO) {
          spiGpioReg.foreach(_ := core.io.dmem.req.data(3, 0))
        }

        is(MMIO.UART16550_RBR) { if (!p.mmioGated(MmioGateTarget.UartThr)) uart16550.io.wen := true.B }

        is(MMIO.UART16550_IER) { if (!p.mmioGated(MmioGateTarget.UartCfg)) uart16550.io.wen := true.B }
        is(MMIO.UART16550_IIR) { if (!p.mmioGated(MmioGateTarget.UartCfg)) uart16550.io.wen := true.B }
        is(MMIO.UART16550_LCR) { if (!p.mmioGated(MmioGateTarget.UartCfg)) uart16550.io.wen := true.B }
        is(MMIO.UART16550_MCR) { if (!p.mmioGated(MmioGateTarget.UartCfg)) uart16550.io.wen := true.B }
        is(MMIO.UART16550_SCR) { if (!p.mmioGated(MmioGateTarget.UartScr)) uart16550.io.wen := true.B }

        is(MMIO.WDT_CTRL)   { wdt.foreach { w => w.io.ctrl_wen   := true.B } }
        is(MMIO.WDT_RELOAD) { wdt.foreach { w => w.io.reload_wen := true.B } }
        is(MMIO.WDT_WINDOW) { wdt.foreach { w => w.io.window_wen := true.B } }
        is(MMIO.WDT_FEED)   { wdt.foreach { w => w.io.feed_wen   := true.B } }
        is(MMIO.WDT_RELOAD2){ wdt.foreach { w => w.io.reload2_wen := true.B } }
      }
    }
  }

  if (p.dmemAddrFromAdder) {
    dmem.io.en := core.io.dmem.en && !plicRegion && !isDmaMemRegion &&
      !isDmaCtrlRegion && !isDmaTestSrcRegion && (core.io.dmem.req.addr < MMIO.Base)
  }

  if (p.withDmaController) {
    val ctrl = dmaCtrl.get
    when(dmaMMioGranted && ctrl.io.mmioWr) {

      when(ctrl.io.mmioAddr === MMIO.TEST_IRQ) {
        testIrqReg := ctrl.io.mmioWdata(3, 0)
      }

      when(ctrl.io.mmioAddr === MMIO.GPO) {
        gpoValReg := ctrl.io.mmioWdata(15, 0)
      }

      sevenSegmentReg.foreach { r =>
        when(ctrl.io.mmioAddr === MMIO.SEVENSEG) { r := ctrl.io.mmioWdata }
      }
      dpReg.foreach { r =>
        when(ctrl.io.mmioAddr === MMIO.SEVENSEG_DP) { r := ctrl.io.mmioWdata(3, 0).asBools }
      }

      when(ctrl.io.mmioAddr === MMIO.IMEM_WDATA) {
        imem_wdataReg := ctrl.io.mmioWdata
        imemWrPtr := imemWrPtr + 1.U
        imem_wrEn_reg := true.B
      }

      switch(ctrl.io.mmioAddr) {
        is(MMIO.MSIP)        { aclint_msip := ctrl.io.mmioWdata(0).asBool }
        is(MMIO.MTIMECMP_LO) { aclint_mtimecmp := Cat(aclint_mtimecmp(63, 32), ctrl.io.mmioWdata) }
        is(MMIO.MTIMECMP_HI) { aclint_mtimecmp := Cat(ctrl.io.mmioWdata, aclint_mtimecmp(31, 0)) }
        is(MMIO.MTIME_LO)    { aclint_mtime := Cat(aclint_mtime(63, 32), ctrl.io.mmioWdata) }
        is(MMIO.MTIME_HI)    { aclint_mtime := Cat(ctrl.io.mmioWdata, aclint_mtime(31, 0)) }
        is(MMIO.SIMULATION_END) { simulation_end := ctrl.io.mmioWdata(0).asBool }
      }

      val isUart16550 = ctrl.io.mmioAddr(31, 5) === (A.UART16550_BASE >> 5).U
      when(isUart16550) {
        uart16550.io.addr  := ctrl.io.mmioAddr(4, 2)
        uart16550.io.wdata := ctrl.io.mmioWdata(7, 0)
        uart16550.io.wen   := true.B
      }

      wdt.foreach { w =>
        switch(ctrl.io.mmioAddr) {
          is(MMIO.WDT_CTRL)   { w.io.ctrl_wdata   := ctrl.io.mmioWdata; w.io.ctrl_wen   := true.B }
          is(MMIO.WDT_RELOAD) { w.io.reload_wdata  := ctrl.io.mmioWdata; w.io.reload_wen := true.B }
          is(MMIO.WDT_WINDOW) { w.io.window_wdata  := ctrl.io.mmioWdata; w.io.window_wen := true.B }
          is(MMIO.WDT_FEED)   { w.io.feed_wdata    := ctrl.io.mmioWdata; w.io.feed_wen   := true.B }
          is(MMIO.WDT_RELOAD2){ w.io.reload2_wdata := ctrl.io.mmioWdata; w.io.reload2_wen := true.B }
        }
      }

      when(ctrl.io.mmioAddr === MMIO.SPI_GPIO) {
        spiGpioReg.foreach(_ := ctrl.io.mmioWdata(3, 0))
      }

      if (p.withDmaTestSource) {
        val isDmaTestSrcBase = ctrl.io.mmioAddr(31, 16) === (A.DMA_TEST_SOURCE_BASE >> 16).U
        val dmaSrcIdx = ctrl.io.mmioAddr(15, 8)
        when(isDmaTestSrcBase) {
          dmaTestSrcs.get.zipWithIndex.foreach { case (src, i) =>
            when(dmaSrcIdx === i.U) {
              src.io.regAddr  := ctrl.io.mmioAddr(4, 0)
              src.io.regWdata := ctrl.io.mmioWdata
              src.io.regWen   := true.B
            }
          }
        }
      }
    }
  }

  io.GPO := gpoValReg

  private val safetyReplicaCount = p.safetyMode match {
    case SafetyMode.None         => 1
    case SafetyMode.DualLockstep => 2
    case SafetyMode.TripleVoting => 3
  }

  private def safetyDelay[T <: Data](x: T, n: Int): T =
    ShiftRegister(x, n, 0.U.asTypeOf(x), true.B)

  private val safetyShadows: Seq[(Bool, Core)] = (1 until safetyReplicaCount).map { i =>
    val lag = i * p.lockstepDelay

    val shadowReset = ShiftRegister(reset.asBool, lag, true.B, true.B)
    val shadow = withReset(shadowReset) { mkCore() }
    shadow.suggestName(s"core_r$i")

    shadow.io.imem.data    := safetyDelay(core.io.imem.data, lag)
    if (p.enableDualIssue) {
      shadow.io.imem_data_hi.get := safetyDelay(core.io.imem_data_hi.get, lag)
    }
    shadow.io.dmem.data    := safetyDelay(core.io.dmem.data, lag)
    shadow.io.timerIRQ     := safetyDelay(core.io.timerIRQ, lag)
    shadow.io.softwareIRQ  := safetyDelay(core.io.softwareIRQ, lag)
    shadow.io.externalIRQ  := safetyDelay(core.io.externalIRQ, lag)
    shadow.io.faultIRQ     := safetyDelay(core.io.faultIRQ, lag)
    shadow.io.dmaMemStall  := safetyDelay(core.io.dmaMemStall, lag)

    shadow.io.reset_vector := core.io.reset_vector
    (shadowReset, shadow)
  }

  private def safetyVector(c: Core): UInt = {
    val s = c.io.safetyCommit.get
    val dmemWrite = c.io.dmem.en && (c.io.dmem.req.cmd === MemCmd.Write)
    val fields = Seq[UInt](

      c.io.imem.addr,

      c.io.dmem.en.asUInt,
      Mux(c.io.dmem.en, Cat(c.io.dmem.req.addr, c.io.dmem.req.cmd.asUInt, c.io.dmem.req.typ.asUInt), 0.U(36.W)),
      Mux(dmemWrite, c.io.dmem.req.data, 0.U(32.W)),

      c.io.exMemValid.asUInt,
      Mux(c.io.exMemValid, c.io.exMemAddr, 0.U(32.W)),

      c.io.counter.counter0,

      s.wp0_wen.asUInt,
      Mux(s.wp0_wen, Cat(s.wp0_waddr, s.wp0_wdata), 0.U(37.W)),
      s.wb_valid.asUInt,

      s.csrState
    ) ++ (if (p.enableDualIssue) Seq[UInt](

      s.wp1_wen.get.asUInt,
      Mux(s.wp1_wen.get, Cat(s.wp1_waddr.get, s.wp1_wdata.get), 0.U(37.W)),
      s.wb_B_valid.get.asUInt
    ) else Seq.empty) ++ (if (p.enableFExtension) Seq[UInt](

      s.fp_wen.get.asUInt,
      Mux(s.fp_wen.get, Cat(s.fp_waddr.get, s.fp_wdata.get), 0.U(38.W))
    ) else Seq.empty)

    val cat = Cat(fields)
    val vec = Wire(UInt(cat.getWidth.W))
    vec := cat
    vec
  }

  if (p.safetyMode != SafetyMode.None) {
    val maxLag = (safetyReplicaCount - 1) * p.lockstepDelay

    val safetySelfTest  = RegInit(false.B)
    val safetyClear     = RegInit(false.B)
    val safetyWalkStart = RegInit(false.B)
    safetySelfTest  := false.B
    safetyClear     := false.B
    safetyWalkStart := false.B

    val safetyRestoreReq = if (p.safetyRollback) Some(RegInit(false.B)) else None
    safetyRestoreReq.foreach(_ := false.B)
    when(isMmioWriteRegion && isStore && (dmemDecodeAddr === MMIO.SAFETY_CTRL)) {
      safetySelfTest  := core.io.dmem.req.data(0)
      safetyClear     := core.io.dmem.req.data(1)
      safetyWalkStart := core.io.dmem.req.data(2)
      safetyRestoreReq.foreach(_ := core.io.dmem.req.data(3))
    }

    val rawVecs = (core +: safetyShadows.map(_._2)).map(safetyVector)
    val vecW = rawVecs.head.getWidth

    val safetyWalkLegs =
      if (p.safetyWalkExt) safetyReplicaCount
      else safetyReplicaCount - 1
    val safetyWalkTarget = safetyWalkLegs * vecW
    val walkCntW = log2Ceil(vecW + maxLag)
    val safetyWalkBusy = RegInit(false.B)
    val safetyWalkDone = RegInit(false.B)
    val safetyWalkCnt  = RegInit(0.U(walkCntW.W))
    val safetyWalkPass = RegInit(0.U(log2Ceil(safetyWalkTarget + 1).W))
    val safetyWalkFirstFail = RegInit("hFFFF".U(16.W))

    val safetyWalkDevFail = if (p.safetyWalkExt) Some(RegInit(false.B)) else None

    val safetyWalkLeg = if (safetyWalkLegs > 1) Some(RegInit(0.U(log2Ceil(safetyWalkLegs).W))) else None
    val walkLegIdx = safetyWalkLeg.getOrElse(0.U)
    val walkInjOH = Mux(safetyWalkBusy && (safetyWalkCnt < vecW.U),
                        UIntToOH(safetyWalkCnt, vecW), 0.U(vecW.W))

    val legs = rawVecs.zipWithIndex.map { case (v, i) =>
      val vin = if (i < safetyWalkLegs) v ^ Mux(walkLegIdx === i.U, walkInjOH, 0.U(vecW.W)) else v
      ShiftRegister(vin, maxLag - i * p.lockstepDelay, 0.U(vecW.W), true.B)
    }
    val legsInj = legs.zipWithIndex.map { case (l, i) =>
      if (i == 1) l ^ Cat(0.U((vecW - 1).W), safetySelfTest) else l
    }

    val armed = !safetyShadows.last._1

    val safetyOutvotedSticky = if (p.safetyMode == SafetyMode.TripleVoting) Some(RegInit(0.U(3.W))) else None

    val resyncDeviant = if (p.safetyResync) Some(WireDefault(0.U(safetyReplicaCount.W))) else None

    val rsSuppress = if (p.safetyResync) Some(WireDefault(false.B)) else None

    val mmioGateStore = if (p.safetyMmioGate) Some(WireDefault(false.B)) else None
    val mmioGateAddr  = if (p.safetyMmioGate) Some(WireDefault(0.U(32.W))) else None
    val mmioGateData  = if (p.safetyMmioGate) Some(WireDefault(0.U(32.W))) else None

    val mmioGateBlockNow =
      if (p.safetyMmioGate && p.safetyMode == SafetyMode.DualLockstep)
        Some(WireDefault(false.B))
      else None

    val egressCheckSticky = if (p.safetyEgressCheck) Some(RegInit(false.B)) else None
    val egressDisagree    = if (p.safetyEgressCheck) Some(WireDefault(false.B)) else None

    val egressDisagreeRaw =
      if (p.safetyEgressCheck && p.safetyMode == SafetyMode.DualLockstep)
        Some(WireDefault(false.B))
      else None
    val mismatchNow = Wire(Bool())

    val storeGateDiverge = if (p.safetyStoreGate) Some(Wire(Bool())) else None

    val walkDivVecs = Wire(Vec(safetyWalkLegs, UInt(vecW.W)))

    val walkDeviantOH = if (p.safetyWalkExt) Some(Wire(UInt(safetyReplicaCount.W))) else None
    p.safetyMode match {
      case SafetyMode.DualLockstep =>

        val xorAB = legsInj(0) ^ legsInj(1)
        mismatchNow := armed && xorAB.orR
        walkDivVecs(0) := xorAB
        storeGateDiverge.foreach(_ := mismatchNow)

        if (p.safetyMmioGate) {
          val a0 = core.io.imem.addr.getWidth
          val lp = legsInj(0)
          val vw = lp.getWidth

          val eHi = vw - a0 - 1
          val eLo = vw - a0 - 69

          if (p.safetyEgressFi || p.safetyEgressCheck)
            require(eLo > 0, "egress slice must not reach lane 0 (self-test lane)")
          val egressFiEn  = if (p.safetyEgressFi) Some(RegInit(0.U(69.W))) else None
          val egressFiVal = if (p.safetyEgressFi) Some(RegInit(0.U(69.W))) else None
          egressFiEn.foreach(dontTouch(_))
          egressFiVal.foreach(dontTouch(_))
          val lpEff = egressFiEn.map { en =>
            val lpRawSlice = lp(eHi, eLo)
            val efi_observe_lpRaw = WireInit(lpRawSlice)
            dontTouch(efi_observe_lpRaw)
            (lpRawSlice & ~en) | (egressFiVal.get & en)
          }

          val dEn  = lpEff.map(_(68)).getOrElse(lp(vw - a0 - 1))
          val dAct = lpEff.map(_(67, 32)).getOrElse(lp(vw - a0 - 2, vw - a0 - 37))
          val dDat = lpEff.map(_(31, 0)).getOrElse(lp(vw - a0 - 38, vw - a0 - 69))
          mmioGateStore.get := dEn && (dAct(3, 3) === MemCmd.Write.asUInt)
          mmioGateAddr.get  := dAct(35, 4)
          mmioGateData.get  := dDat

          if (p.safetyEgressCheck) {
            val lpSh       = legsInj(1)(eHi, eLo)
            val lpConsumed = Cat(dEn, dAct, dDat)
            egressDisagreeRaw.get := armed && (lpSh ^ lpConsumed).orR

            val ec_observe_egress_disagree_raw = WireInit(egressDisagreeRaw.get)
            dontTouch(ec_observe_egress_disagree_raw)
          }
        }
      case SafetyMode.TripleVoting =>

        val voted = (legsInj(0) & legsInj(1)) | (legsInj(0) & legsInj(2)) | (legsInj(1) & legsInj(2))

        val voterFiEn  = if (p.safetyVoterFi) Some(RegInit(0.U(vecW.W))) else None
        val voterFiVal = if (p.safetyVoterFi) Some(RegInit(0.U(vecW.W))) else None
        voterFiEn.foreach(dontTouch(_))
        voterFiVal.foreach(dontTouch(_))
        val votedEff = voterFiEn.map(en => (voted & ~en) | (voterFiVal.get & en)).getOrElse(voted)

        val deviantFiEn  = if (p.safetyVoterFi) Some(RegInit(0.U(3.W))) else None
        val deviantFiVal = if (p.safetyVoterFi) Some(RegInit(0.U(3.W))) else None
        deviantFiEn.foreach(dontTouch(_))
        deviantFiVal.foreach(dontTouch(_))
        val deviant = deviantFiEn match {
          case Some(en) =>
            val deviantVote = VecInit(legsInj.map(l => (l ^ votedEff).orR)).asUInt

            val vfi_observe_votedRaw    = WireInit(voted);       dontTouch(vfi_observe_votedRaw)
            val vfi_observe_deviantVote = WireInit(deviantVote); dontTouch(vfi_observe_deviantVote)
            (deviantVote & ~en) | (deviantFiVal.get & en)
          case None => VecInit(legsInj.map(l => (l ^ votedEff).orR)).asUInt
        }
        mismatchNow := armed && deviant.orR
        when(safetyClear) { safetyOutvotedSticky.get := 0.U }

        if (p.safetyResync) {
          when(armed && deviant.orR && !rsSuppress.get) { safetyOutvotedSticky.get := safetyOutvotedSticky.get | deviant }
        } else {
          when(armed && deviant.orR) { safetyOutvotedSticky.get := safetyOutvotedSticky.get | deviant }
        }

        resyncDeviant.foreach(_ := Mux(armed, deviant, 0.U))
        walkDivVecs(0) := legsInj(0) ^ votedEff
        walkDivVecs(1) := legsInj(1) ^ votedEff

        if (p.safetyWalkExt) walkDivVecs(2) := legsInj(2) ^ votedEff
        walkDeviantOH.foreach(_ := deviant)
        storeGateDiverge.foreach(_ := armed && deviant(0))

        if (p.safetyMmioGate) {
          val a0 = core.io.imem.addr.getWidth
          val vw = votedEff.getWidth
          val vEn  = votedEff(vw - a0 - 1)
          val vAct = votedEff(vw - a0 - 2, vw - a0 - 37)
          val vDat = votedEff(vw - a0 - 38, vw - a0 - 69)
          mmioGateStore.get := vEn && (vAct(3, 3) === MemCmd.Write.asUInt)
          mmioGateAddr.get  := vAct(35, 4)
          mmioGateData.get  := vDat

          if (p.safetyEgressCheck) {
            val eHi = vw - a0 - 1
            val eLo = vw - a0 - 69
            val eSlices = legsInj.map(l => l(eHi, eLo))
            val dualMaj = (eSlices(0) | eSlices(1)) &
                          (eSlices(0) | eSlices(2)) &
                          (eSlices(1) | eSlices(2))
            egressDisagree.get := (dualMaj ^ votedEff(eHi, eLo)).orR

            val v2_observe_egress_disagree = WireInit(egressDisagree.get)
            dontTouch(v2_observe_egress_disagree)
          }
        }
      case SafetyMode.None =>
        mismatchNow := false.B
        walkDivVecs := DontCare
        storeGateDiverge.foreach(_ := false.B)
    }

    if (p.safetyMmioGate) {

      val gStore = (mmioGateBlockNow, p.safetyEgressBlock) match {
        case (Some(blk), true)  => armed && mmioGateStore.get && !blk && !egressDisagree.get
        case (Some(blk), false) => armed && mmioGateStore.get && !blk
        case (None,      true)  => armed && mmioGateStore.get && !egressDisagree.get
        case (None,      false) => armed && mmioGateStore.get
      }
      val gAddr  = mmioGateAddr.get

      if (p.mmioGated(MmioGateTarget.Gpo))    when(gStore && (gAddr === MMIO.GPO))            { gpoValReg := mmioGateData.get(15, 0) }
      if (p.mmioGated(MmioGateTarget.SimEnd)) when(gStore && (gAddr === MMIO.SIMULATION_END)) { simulation_end := mmioGateData.get(0).asBool }
      if (p.mmioGated(MmioGateTarget.SevenSeg))   sevenSegmentReg.foreach { r => when(gStore && (gAddr === MMIO.SEVENSEG))    { r := mmioGateData.get } }
      if (p.mmioGated(MmioGateTarget.SevenSegDp)) dpReg.foreach          { r => when(gStore && (gAddr === MMIO.SEVENSEG_DP)) { r := mmioGateData.get(3, 0).asBools } }

      val mmio_observe_commit = WireInit(gStore);           dontTouch(mmio_observe_commit)
      val mmio_observe_addr   = WireInit(gAddr);            dontTouch(mmio_observe_addr)
      val mmio_observe_data   = WireInit(mmioGateData.get); dontTouch(mmio_observe_data)

      mmioGateBlockNow.foreach { blk =>
        val mmio_observe_dcls_block = WireInit(blk); dontTouch(mmio_observe_dcls_block)
      }

      if (p.safetyMmioDropCounter) {

        val mgSinkHit = Seq(
          p.mmioGated(MmioGateTarget.Gpo)        -> (gAddr === MMIO.GPO),
          p.mmioGated(MmioGateTarget.SimEnd)     -> (gAddr === MMIO.SIMULATION_END),
          p.mmioGated(MmioGateTarget.SevenSeg)   -> (gAddr === MMIO.SEVENSEG),
          p.mmioGated(MmioGateTarget.SevenSegDp) -> (gAddr === MMIO.SEVENSEG_DP),
          p.mmioGated(MmioGateTarget.UartThr)    -> (gAddr === MMIO.UART16550_RBR),
          p.mmioGated(MmioGateTarget.SpiData)    -> (gAddr === MMIO.SPI_DATA),
          p.mmioGated(MmioGateTarget.I2cCmd)     -> (gAddr === MMIO.I2C_CMD),
          p.mmioGated(MmioGateTarget.UartScr)    -> (gAddr === MMIO.UART16550_SCR),
          p.mmioGated(MmioGateTarget.UartCfg)    -> ((gAddr === MMIO.UART16550_IER) ||
            (gAddr === MMIO.UART16550_IIR) || (gAddr === MMIO.UART16550_LCR) ||
            (gAddr === MMIO.UART16550_MCR)),
          p.mmioGated(MmioGateTarget.PlicCfg)    -> (gAddr(31, 24) === (A.PLIC_BASE >> 24).U),
          p.mmioGated(MmioGateTarget.AclintMsip) -> (gAddr === MMIO.MSIP)
        ).collect { case (true, hit) => hit }.reduce(_ || _)
        val mgDrop = armed && mmioGateStore.get && !gStore && mgSinkHit
        val mgDropSticky = RegInit(false.B)
        val mgDropCnt    = RegInit(0.U(16.W))
        when(mgDrop) { mgDropSticky := true.B }
        when(mgDrop && mgDropCnt =/= "hFFFF".U(16.W)) { mgDropCnt := mgDropCnt + 1.U }

        when(isMmioWriteRegion && isStore && (core.io.dmem.req.addr === MMIO.SAFETY_MG)) {
          mgDropSticky := mgDrop
          mgDropCnt    := 0.U
        }
        when(mmioDecodeAddr === MMIO.SAFETY_MG) {
          mmioVal := Cat(0.U(15.W), mgDropCnt, mgDropSticky)
        }
        val mg_observe_drop = WireInit(mgDrop)
        dontTouch(mg_observe_drop)
      }

      if (p.safetyMmioGateFifo) {
        when(gStore && (gAddr === MMIO.UART16550_RBR)) {
          uart16550.io.wen   := true.B
          uart16550.io.addr  := 0.U
          uart16550.io.wdata := mmioGateData.get(7, 0)
        }

        val mmio_observe_thr_commit = WireInit(gStore && (gAddr === MMIO.UART16550_RBR)); dontTouch(mmio_observe_thr_commit)
        val mmio_observe_thr_byte   = WireInit(mmioGateData.get(7, 0));                   dontTouch(mmio_observe_thr_byte)
      }

      spiDataGateCommit.foreach { c => c := gStore && (gAddr === MMIO.SPI_DATA) }
      spiDataGateByte.foreach   { b => b := mmioGateData.get(7, 0) }
      i2cCmdGateCommit.foreach  { c => c := gStore && (gAddr === MMIO.I2C_CMD) }
      i2cCmdGateBits.foreach    { b => b := mmioGateData.get(15, 8) ## mmioGateData.get(2, 0) }

      if (p.mmioGated(MmioGateTarget.SpiData)) {
        val mmio_observe_spi_commit = WireInit(gStore && (gAddr === MMIO.SPI_DATA)); dontTouch(mmio_observe_spi_commit)
        val mmio_observe_spi_byte   = WireInit(mmioGateData.get(7, 0));              dontTouch(mmio_observe_spi_byte)
      }
      if (p.mmioGated(MmioGateTarget.I2cCmd)) {
        val mmio_observe_i2c_commit = WireInit(gStore && (gAddr === MMIO.I2C_CMD));                       dontTouch(mmio_observe_i2c_commit)
        val mmio_observe_i2c_bits   = WireInit(mmioGateData.get(15, 8) ## mmioGateData.get(2, 0));        dontTouch(mmio_observe_i2c_bits)
      }

      if (p.mmioGated(MmioGateTarget.UartScr)) {
        when(gStore && (gAddr === MMIO.UART16550_SCR)) {
          uart16550.io.wen   := true.B
          uart16550.io.addr  := 7.U
          uart16550.io.wdata := mmioGateData.get(7, 0)
        }

        val mmio_observe_scr_commit = WireInit(gStore && (gAddr === MMIO.UART16550_SCR)); dontTouch(mmio_observe_scr_commit)
        val mmio_observe_scr_byte   = WireInit(mmioGateData.get(7, 0));                   dontTouch(mmio_observe_scr_byte)
      }
      if (p.mmioGated(MmioGateTarget.UartCfg)) {
        val gUartCfgCommit = gStore && (
          (gAddr === MMIO.UART16550_IER) || (gAddr === MMIO.UART16550_IIR) ||
          (gAddr === MMIO.UART16550_LCR) || (gAddr === MMIO.UART16550_MCR))
        when(gUartCfgCommit) {
          uart16550.io.wen   := true.B
          uart16550.io.addr  := gAddr(4, 2)
          uart16550.io.wdata := mmioGateData.get(7, 0)
        }

        val mmio_observe_uartcfg_commit = WireInit(gUartCfgCommit);         dontTouch(mmio_observe_uartcfg_commit)
        val mmio_observe_uartcfg_addr   = WireInit(gAddr(4, 2));            dontTouch(mmio_observe_uartcfg_addr)
        val mmio_observe_uartcfg_byte   = WireInit(mmioGateData.get(7, 0)); dontTouch(mmio_observe_uartcfg_byte)
      }

      if (p.mmioGated(MmioGateTarget.PlicCfg)) {
        val gPlicCommit = gStore && (gAddr(31, 24) === (A.PLIC_BASE >> 24).U)
        when(gPlicCommit) {
          plic.io.wen   := true.B
          plic.io.addr  := gAddr(21, 0)
          plic.io.wdata := mmioGateData.get
        }

        val mmio_observe_plic_commit = WireInit(gPlicCommit);        dontTouch(mmio_observe_plic_commit)
        val mmio_observe_plic_addr   = WireInit(gAddr(21, 0));       dontTouch(mmio_observe_plic_addr)
        val mmio_observe_plic_data   = WireInit(mmioGateData.get);   dontTouch(mmio_observe_plic_data)
      }
      if (p.mmioGated(MmioGateTarget.AclintMsip)) {
        val gMsipCommit = gStore && (gAddr === MMIO.MSIP)
        when(gMsipCommit) { aclint_msip := mmioGateData.get(0).asBool }

        val mmio_observe_msip_commit = WireInit(gMsipCommit);           dontTouch(mmio_observe_msip_commit)
        val mmio_observe_msip_bit    = WireInit(mmioGateData.get(0));   dontTouch(mmio_observe_msip_bit)
      }

      if (p.safetyMmioFwdStatusSet.nonEmpty) {
        val fwdReplicas = core +: safetyShadows.map(_._2)
        def pendingFor(target: UInt): UInt = {
          def pred(c: Core): Bool =
            c.io.dmem.en && (c.io.dmem.req.cmd === MemCmd.Write) && (c.io.dmem.req.addr === target)

          val hists: Seq[Seq[Bool]] = fwdReplicas.zipWithIndex.map { case (c, i) =>
            val depth = maxLag - 1 - i * p.lockstepDelay
            if (depth < 0) Seq.empty
            else Seq.iterate(pred(c), depth + 1)(s => RegNext(s, false.B))
          }
          val ages: Seq[Bool] = (0 until maxLag).map { j =>
            fwdReplicas.indices.flatMap { i =>
              val lag = i * p.lockstepDelay
              if (lag <= j && (j - lag) < hists(i).length) Some(hists(i)(j - lag)) else None
            }.reduce(_ || _)
          }
          val commitNow = gStore && (gAddr === target)
          PopCount(ages :+ commitNow)
        }
        if (p.mmioFwdStatus(MmioGateTarget.UartThr)) {
          uartTxPendingO4.get := pendingFor(MMIO.UART16550_RBR)

          val mmio_observe_stat_pend_uart = WireInit(uartTxPendingO4.get); dontTouch(mmio_observe_stat_pend_uart)
        }
        if (p.mmioFwdStatus(MmioGateTarget.SpiData)) {
          spiTxPendingO4.get := pendingFor(MMIO.SPI_DATA)
          val mmio_observe_stat_pend_spi = WireInit(spiTxPendingO4.get); dontTouch(mmio_observe_stat_pend_spi)
        }
        if (p.mmioFwdStatus(MmioGateTarget.I2cCmd)) {
          i2cTxPendingO4.get := pendingFor(MMIO.I2C_CMD)
          val mmio_observe_stat_pend_i2c = WireInit(i2cTxPendingO4.get); dontTouch(mmio_observe_stat_pend_i2c)
        }
      }
    }

    val walkLegDelay = VecInit((0 until safetyWalkLegs).map(i =>
      (maxLag - i * p.lockstepDelay).U(walkCntW.W)))(walkLegIdx)
    when(safetyWalkStart && !safetyWalkBusy) {
      safetyWalkBusy      := true.B
      safetyWalkDone      := false.B
      safetyWalkCnt       := 0.U
      safetyWalkPass      := 0.U
      safetyWalkFirstFail := "hFFFF".U
      safetyWalkLeg.foreach(_ := 0.U)
      safetyWalkDevFail.foreach(_ := false.B)
    }
    when(safetyWalkBusy) {
      val obsLane = safetyWalkCnt - walkLegDelay
      val laneOk = armed && (walkDivVecs(walkLegIdx) >> obsLane)(0)

      if (p.safetyWalkExt) {
        when((safetyWalkCnt >= walkLegDelay) && armed &&
             (walkDeviantOH.get =/= UIntToOH(walkLegIdx, safetyReplicaCount))) {
          safetyWalkDevFail.get := true.B
          when(safetyWalkFirstFail === "hFFFF".U) {
            safetyWalkFirstFail := Cat(1.U(1.W), Cat(walkLegIdx, obsLane).pad(15))
          }
        }
      }
      when(safetyWalkCnt >= walkLegDelay) {
        when(laneOk) {
          safetyWalkPass := safetyWalkPass + 1.U
        }.elsewhen(safetyWalkFirstFail === "hFFFF".U) {
          safetyWalkFirstFail := Cat(walkLegIdx, obsLane).pad(16)
        }
      }
      when(safetyWalkCnt === ((vecW - 1).U(walkCntW.W) + walkLegDelay)) {
        when(walkLegIdx === (safetyWalkLegs - 1).U) {
          safetyWalkBusy := false.B
          safetyWalkDone := true.B
        }.otherwise {
          safetyWalkLeg.foreach(r => r := r + 1.U)
          safetyWalkCnt := 0.U
        }
      }.otherwise {
        safetyWalkCnt := safetyWalkCnt + 1.U
      }
    }

    val safetyMismatchSticky = RegInit(false.B)

    val rbSuppress = if (p.safetyRollback) Some(WireDefault(false.B)) else None
    val stickyClrCond =
      if (p.safetyRollback) (safetyClear || rbSuppress.get)
      else if (p.safetyResync) (safetyClear || rsSuppress.get)
      else safetyClear
    val stickySetCond =
      if (p.safetyRollback) (mismatchNow && !rbSuppress.get)
      else if (p.safetyResync) (mismatchNow && !rsSuppress.get)
      else mismatchNow
    when(stickyClrCond) { safetyMismatchSticky := false.B }
    when(stickySetCond) { safetyMismatchSticky := true.B }

    egressCheckSticky.foreach { s =>
      when(safetyClear) { s := false.B }
      when(egressDisagree.get) { s := true.B }
    }

    if (p.safetyEgressCheck) {
      safetyErrIrq.get := safetyMismatchSticky || egressCheckSticky.get

      io.safetyMismatch.get := safetyMismatchSticky || egressCheckSticky.get
      io.safetyErrIRQ.get   := safetyMismatchSticky || egressCheckSticky.get
    } else {
      safetyErrIrq.get := safetyMismatchSticky

      io.safetyMismatch.get := safetyMismatchSticky
      io.safetyErrIRQ.get   := safetyMismatchSticky
    }
    io.safetyOutvoted.foreach(_ := safetyOutvotedSticky.get)

    when(mmioDecodeAddr === MMIO.SAFETY_STATUS) {

      if (p.safetyEgressCheck) {
        mmioVal := Cat(0.U(24.W),
                       egressCheckSticky.get,
                       safetyOutvotedSticky.getOrElse(0.U(3.W)),
                       0.U(2.W),
                       safetyMismatchSticky,
                       safetyMismatchSticky)
      } else {
        mmioVal := Cat(0.U(25.W),
                       safetyOutvotedSticky.getOrElse(0.U(3.W)),
                       0.U(2.W),
                       safetyMismatchSticky,
                       safetyMismatchSticky)
      }
    }

    when(mmioDecodeAddr === MMIO.SAFETY_WALK) {

      if (p.safetyWalkExt) {
        mmioVal := Cat(safetyWalkFirstFail,
                       safetyWalkBusy,
                       safetyWalkDone && (safetyWalkPass === safetyWalkTarget.U) &&
                         !safetyWalkDevFail.get,
                       safetyWalkDone,
                       safetyWalkPass.pad(13))
      } else {
        mmioVal := Cat(safetyWalkFirstFail,
                       safetyWalkBusy,
                       safetyWalkDone && (safetyWalkPass === safetyWalkTarget.U),
                       safetyWalkDone,
                       safetyWalkPass.pad(13))
      }
    }

    val safety_observe_mismatch = WireInit(safetyMismatchSticky)
    dontTouch(safety_observe_mismatch)
    safetyOutvotedSticky.foreach { ov =>
      val safety_observe_outvoted = WireInit(ov)
      dontTouch(safety_observe_outvoted)
    }

    if (p.safetyRollback) {
      val period = p.safetyCkptPeriod
      val cntW   = log2Ceil(period)
      val ckptCnt = RegInit(0.U(cntW.W))

      val intervalClean = RegInit(true.B)

      val ckptRegs   = Reg(Vec(32, UInt(32.W)))
      val ckptCsr    = Reg(chiselTypeOf(core.io.safetyCommit.get.csrState))
      val ckptPC     = RegInit(0.U(32.W))
      val ckptSeq    = RegInit(0.U(16.W))
      val ckptValid  = RegInit(false.B)
      val ckptFrozen = RegInit(false.B)

      val sc       = core.io.safetyCommit.get
      val COMMIT_D = maxLag + 1

      val RESTART_LAT = 3
      val archW   = CSR.archStateWidth(p.enableFExtension)
      val fpLow   = if (p.enableFExtension) 8 else 0
      val ckMc    = sc.csrState(fpLow + 63, fpLow) - RESTART_LAT.U
      val ckIr    = sc.csrState(fpLow + 127, fpLow + 64) + sc.wb_valid.asUInt
      val ckHi    = sc.csrState(archW - 1, fpLow + 128)
      val ckCsr   = if (p.enableFExtension) Cat(ckHi, ckIr, ckMc, sc.csrState(fpLow - 1, 0)) else Cat(ckHi, ckIr, ckMc)
      require(period > COMMIT_D + 1, s"safetyCkptPeriod $period must exceed the commit delay ${COMMIT_D + 1}")
      val stageRegs = Reg(Vec(32, UInt(32.W)))
      val stageCsr  = Reg(chiselTypeOf(sc.csrState))
      val stagePC   = Reg(UInt(32.W))
      val stagePend = RegInit(false.B)
      val stageAge  = RegInit(0.U(log2Ceil(COMMIT_D + 1).W))
      val stagePcOk = RegInit(true.B)
      val ckptPcRej = RegInit(0.U(8.W))
      val rbIdle    = Wire(Bool())

      val cleanNow         = armed && !mismatchNow && !safetyMismatchSticky
      val intervalCleanNow = intervalClean && cleanNow
      val atBoundary       = ckptCnt === (period - 1).U
      val captureReady     = sc.mem_valid.get && !stagePend && rbIdle
      val captureNow       = atBoundary && intervalCleanNow && captureReady

      when(mismatchNow || safetyMismatchSticky) { intervalClean := false.B }

      val stagePcOkNow = WireDefault(stagePcOk)
      safetyShadows.zipWithIndex.foreach { case ((_, sh), j) =>
        val lag = (j + 1) * p.lockstepDelay
        val shc = sh.io.safetyCommit.get
        when(stagePend && stageAge === lag.U &&
             (shc.mem_pc.get =/= stagePC || !shc.mem_valid.get)) { stagePcOkNow := false.B }
      }

      val commitClean = armed && intervalClean && !safetyMismatchSticky
      when(stagePend) {
        stagePcOk := stagePcOkNow
        when(stageAge === COMMIT_D.U) {
          stagePend := false.B
          when(commitClean && stagePcOkNow && rbIdle) {
            ckptRegs  := stageRegs
            ckptCsr   := stageCsr
            ckptPC    := stagePC
            ckptSeq   := ckptSeq + 1.U
            ckptValid := true.B
          }.otherwise {
            ckptFrozen := true.B
            when(!stagePcOkNow) { ckptPcRej := ckptPcRej + 1.U }
          }
        }.otherwise {
          stageAge := stageAge + 1.U
        }
      }

      when(atBoundary) {
        when(!intervalCleanNow) {
          ckptCnt    := 0.U
          ckptFrozen := true.B

          intervalClean := true.B
        }.elsewhen(captureReady) {
          ckptCnt   := 0.U
          stageRegs := sc.gprs.get
          when(sc.wp0_wen && sc.wp0_waddr =/= 0.U) { stageRegs(sc.wp0_waddr) := sc.wp0_wdata }
          stageCsr  := ckCsr
          stagePC   := sc.mem_pc.get
          stagePend := true.B
          stageAge  := 1.U
          stagePcOk := true.B
          intervalClean := true.B
        }

      }.otherwise {
        ckptCnt := ckptCnt + 1.U
      }

      when(safetyClear) { ckptFrozen := false.B }

      when(mmioDecodeAddr === MMIO.SAFETY_CKPT) {
        mmioVal := Cat(0.U(14.W), ckptFrozen, ckptValid, ckptSeq)
      }

      when(mmioDecodeAddr === MMIO.SAFETY_CKPT_PC) {
        mmioVal := ckptPC
      }

      val ckpt_observe_seq    = WireInit(ckptSeq);         dontTouch(ckpt_observe_seq)
      val ckpt_observe_valid  = WireInit(ckptValid);       dontTouch(ckpt_observe_valid)
      val ckpt_observe_frozen = WireInit(ckptFrozen);      dontTouch(ckpt_observe_frozen)
      val ckpt_observe_pc     = WireInit(ckptPC);          dontTouch(ckpt_observe_pc)
      val ckpt_observe_csr    = WireInit(ckptCsr);         dontTouch(ckpt_observe_csr)
      val ckpt_observe_regs   = WireInit(ckptRegs.asUInt); dontTouch(ckpt_observe_regs)
      val ckpt_observe_staged = WireInit(captureNow);      dontTouch(ckpt_observe_staged)
      val ckpt_observe_pcrej  = WireInit(ckptPcRej);       dontTouch(ckpt_observe_pcrej)

      val RESYNC_W = 32
      val MAXRETRY = 3
      val rbPhase     = RegInit(0.U(2.W))
      val rbCnt       = RegInit(0.U(6.W))
      val rbWait      = RegInit(0.U(8.W))
      val rbRetry     = RegInit(0.U(4.W))
      val rbEscalated = RegInit(false.B)
      val rbRollbacks = RegInit(0.U(16.W))

      val rbTrigger = (safetyMismatchSticky || safetyRestoreReq.get) && ckptValid

      switch(rbPhase) {
        is(0.U) {
          when(rbTrigger && !rbEscalated) {
            when(rbRetry === MAXRETRY.U) {
              rbEscalated := true.B
            }.otherwise {
              rbPhase := 1.U
              rbCnt   := 1.U
            }
          }
        }
        is(1.U) {
          when(rbCnt === 31.U) {
            rbPhase := 2.U
            rbWait  := RESYNC_W.U
            rbCnt   := 0.U
          }.otherwise {
            rbCnt := rbCnt + 1.U
          }
        }
        is(2.U) {
          when(rbWait === 0.U) {
            rbPhase     := 0.U
            rbRetry     := rbRetry + 1.U
            rbRollbacks := rbRollbacks + 1.U
          }.otherwise {
            rbWait := rbWait - 1.U
          }
        }
      }

      val ckptSeqPrev = RegNext(ckptSeq, 0.U(16.W))
      when(ckptSeq =/= ckptSeqPrev) { rbRetry := 0.U }

      val rbActive = rbPhase === 1.U
      rbIdle := rbPhase === 0.U
      rbSuppress.get := (rbPhase === 1.U) || (rbPhase === 2.U)

      val rbCmd = Wire(new RollbackRestoreIO(CSR.archStateWidth(p.enableFExtension)))
      rbCmd.gprEn   := rbActive
      rbCmd.gprAddr := rbCnt
      rbCmd.gprData := ckptRegs(rbCnt)
      rbCmd.csrEn   := rbActive
      rbCmd.csr     := ckptCsr
      rbCmd.pcEn    := rbActive
      rbCmd.pc      := ckptPC
      rbCmd.flush   := rbActive
      core.io.rbRestore.get := rbCmd
      dmem.bufFlush.foreach(_ := rbActive)

      safetyShadows.zipWithIndex.foreach { case ((_, sh), j) =>
        val lag = (j + 1) * p.lockstepDelay
        sh.io.rbRestore.foreach(_ := safetyDelay(rbCmd, lag))
      }

      val ckpt_observe_restoring = WireInit(rbActive);     dontTouch(ckpt_observe_restoring)
      val ckpt_observe_escalated = WireInit(rbEscalated);  dontTouch(ckpt_observe_escalated)
      val ckpt_observe_rollbacks = WireInit(rbRollbacks);  dontTouch(ckpt_observe_rollbacks)
      val ckpt_observe_retry     = WireInit(rbRetry);      dontTouch(ckpt_observe_retry)
    }

    if (p.safetyResync) {
      val nRep   = safetyReplicaCount
      val srcW   = log2Ceil(nRep)
      val devOH  = resyncDeviant.get

      val scrubOH  = if (p.safetyRegfileScrub) Some(WireDefault(0.U(nRep.W))) else None
      val devOHeff = if (p.safetyRegfileScrub) devOH | scrubOH.get else devOH
      val devAny   = if (p.safetyRegfileScrub) devOHeff.orR else devOH.orR

      val repCommit = core.io.safetyCommit.get +: safetyShadows.map(_._2.io.safetyCommit.get)
      val repRegs   = VecInit(repCommit.map(_.gprs.get))

      val repFpRegs = if (p.enableFExtension) Some(VecInit(repCommit.map(_.fpRegs.get))) else None

      val repInflight = if (p.safetyResyncInflight) Some(VecInit(repCommit.map(_.inflight.get))) else None
      val repCsr    = VecInit(repCommit.map(_.csrState))
      val repPC     = VecInit(core.io.imem.addr +: safetyShadows.map(_._2.io.imem.addr))

      val repPCarch = VecInit((core.io.imem.addr +: safetyShadows.map(_._2.io.imem.addr))
        .map(a => safetyDelay(a, p.safetyResyncPcDelay)))

      val archW  = CSR.archStateWidth(p.enableFExtension)
      val fpLow  = if (p.enableFExtension) 8 else 0
      val repCsrArch = VecInit(repCommit.map { c =>
        val rest = safetyDelay(c.csrState, p.safetyResyncCsrDelay)
        val mcyc = safetyDelay(c.csrState, p.safetyResyncMcycleDelay)
        if (fpLow == 0) Cat(rest(archW - 1, 64), mcyc(63, 0))
        else            Cat(rest(archW - 1, fpLow + 64), mcyc(fpLow + 63, fpLow), rest(fpLow - 1, 0))
      })

      val devAccum = RegInit(0.U(nRep.W))

      if (p.safetyWalkRsGate) {
        when(!safetyWalkBusy) {
          when(!devAny) { devAccum := 0.U }.otherwise { devAccum := devAccum | devOHeff }
        }
      } else {
        when(!devAny) { devAccum := 0.U }.otherwise { devAccum := devAccum | devOHeff }
      }
      val devSet = devAccum | devOHeff
      val devMulti = PopCount(devSet) >= 2.U

      val healthyMask = (~devSet)(nRep - 1, 0)
      val srcIdx = PriorityEncoder(healthyMask)

      val safetyResyncReq = RegInit(false.B)
      safetyResyncReq := false.B
      when(isMmioWriteRegion && isStore && (dmemDecodeAddr === MMIO.SAFETY_CTRL)) {
        safetyResyncReq := core.io.dmem.req.data(3)
      }

      val debCnt = RegInit(0.U(log2Ceil(p.safetyResyncDebounce + 1).W))

      if (p.safetyWalkRsGate) {
        when(!safetyWalkBusy) {
          when(devAny) {
            when(debCnt =/= p.safetyResyncDebounce.U) { debCnt := debCnt + 1.U }
          }.otherwise {
            debCnt := 0.U
          }
        }
      } else {
        when(devAny) {
          when(debCnt =/= p.safetyResyncDebounce.U) { debCnt := debCnt + 1.U }
        }.otherwise {
          debCnt := 0.U
        }
      }
      val debounced = debCnt === p.safetyResyncDebounce.U

      val rsPhase     = RegInit(0.U(2.W))
      val rsDeviant   = RegInit(0.U(nRep.W))
      val rsSource    = RegInit(0.U(srcW.W))
      val rsValid     = RegInit(false.B)
      val rsDouble    = RegInit(false.B)
      val rsEscalated = RegInit(false.B)
      val rsCount     = RegInit(0.U(16.W))
      val rsRetry     = RegInit(0.U(4.W))
      val rsBusy      = rsPhase =/= 0.U

      if (p.safetyRegfileScrub) {
        val scrubIncohVec = VecInit(repCommit.map(_.scrubIncoh.get)).asUInt
        val scrubSuppress = rsBusy || safetyClear || safetyWalkBusy
        val scrubSticky   = RegInit(0.U(nRep.W))
        when(scrubSuppress) { scrubSticky := 0.U }
          .otherwise        { scrubSticky := scrubSticky | scrubIncohVec }
        scrubOH.get := Mux(scrubSuppress, 0.U, scrubSticky)

        val scrubFirstFailValid = RegInit(false.B)
        val scrubFirstFailRep   = RegInit(0.U(nRep.W))
        val scrubFirstFailReg   = RegInit(0.U(5.W))
        when(!scrubFirstFailValid && scrubIncohVec.orR && !scrubSuppress) {
          scrubFirstFailValid := true.B
          scrubFirstFailRep   := scrubIncohVec
          scrubFirstFailReg   := repCommit.head.scrubIdx.get
        }
        when(safetyClear) { scrubFirstFailValid := false.B }
        dontTouch(scrubFirstFailValid); dontTouch(scrubFirstFailRep); dontTouch(scrubFirstFailReg)
      }

      if (p.regfileScrubSelfCheck) {
        val scrubStFailVec    = VecInit(repCommit.map(_.scrubStFail.get)).asUInt
        val scrubStFailSticky = RegInit(0.U(nRep.W))
        when(safetyClear) { scrubStFailSticky := 0.U }
          .otherwise      { scrubStFailSticky := scrubStFailSticky | scrubStFailVec }
        val scrubsc_observe_fail = WireInit(scrubStFailSticky)
        dontTouch(scrubsc_observe_fail)
      }

      if (p.safetyRegfileEcc) {
        val rfEccCorrVec = VecInit(repCommit.map(_.rfEccCorrectable.get)).asUInt
        val rfEccUncVec  = VecInit(repCommit.map(_.rfEccUncorrectable.get)).asUInt
        val rfEccCorrCnt      = RegInit(0.U(16.W))
        val rfEccUncorrSticky = RegInit(0.U(nRep.W))
        when(rfEccCorrVec.orR && rfEccCorrCnt =/= "hFFFF".U(16.W)) { rfEccCorrCnt := rfEccCorrCnt + 1.U }
        rfEccUncorrSticky := rfEccUncorrSticky | rfEccUncVec
        when(safetyClear) { rfEccCorrCnt := 0.U; rfEccUncorrSticky := 0.U }
        dontTouch(rfEccCorrCnt); dontTouch(rfEccUncorrSticky)
        val rfecc_observe_corr = WireInit(rfEccCorrVec);  dontTouch(rfecc_observe_corr)
        val rfecc_observe_uncorr = WireInit(rfEccUncVec); dontTouch(rfecc_observe_uncorr)
      }

      val rsAge       = RegInit(0.U(8.W))
      val rsWait      = RegInit(0.U(9.W))
      val rsRegs = Reg(Vec(32, UInt(32.W)))

      val rsFpRegs = if (p.enableFExtension) Some(Reg(Vec(32, UInt(33.W)))) else None

      val rsInflight = if (p.safetyResyncInflight) Some(Reg(new InflightSnapshot(p.enableFExtension))) else None
      val rsCsr  = Reg(chiselTypeOf(core.io.safetyCommit.get.csrState))
      val rsPC   = RegInit(0.U(32.W))

      val rsArmed = RegInit(true.B)
      when(!devAny) { rsArmed := true.B; rsRetry := 0.U }

      when(safetyClear) {
        rsValid     := false.B
        rsDouble    := false.B
        rsEscalated := false.B
      }

      val captureReady =
        if (p.enableFExtension && p.safetyResyncFpuGate) {
          val repFpuBusy = VecInit(repCommit.map(_.fpu_busy.get))
          val srcFpuBusy = repFpuBusy(srcIdx)
          val window = (0 to p.safetyResyncPcDelay)
            .map(d => ShiftRegister(srcFpuBusy, d, false.B, true.B)).reduce(_ || _)
          !window
        } else true.B

      val rsTrigger =
        if (p.safetyWalkRsGate)
          (debounced || safetyResyncReq) && rsArmed && !rsEscalated && captureReady && !safetyWalkBusy
        else
          (debounced || safetyResyncReq) && rsArmed && !rsEscalated && captureReady

      val wbActive   = rsDeviant.orR
      val devIdx     = PriorityEncoder(rsDeviant)
      val lagDev     = devIdx   * p.lockstepDelay.U
      val lagSrc     = rsSource * p.lockstepDelay.U
      val deltaPos   = Mux(lagDev >= lagSrc, lagDev - lagSrc, 0.U)
      val MIN_RELEASE = 1

      val deltaAdj   = Mux(deltaPos > p.safetyResyncPcDelay.U,
                           deltaPos - p.safetyResyncPcDelay.U, 0.U)

      val releaseAge = if (p.safetyResyncInflight)
                         (if (p.safetyResyncInflightReleaseAdj == 0) deltaPos
                          else Mux(deltaPos > p.safetyResyncInflightReleaseAdj.U,
                                   deltaPos - p.safetyResyncInflightReleaseAdj.U, 0.U))
                       else Mux(deltaAdj >= MIN_RELEASE.U, deltaAdj, MIN_RELEASE.U)
      val SETTLE     = maxLag + 16
      val MAXRESYNC  = 8

      val QHALT      = 8

      val releaseGateOpen =
        if (p.enableFExtension && p.safetyResyncReleaseGate) {
          val srcBusy = VecInit(repCommit.map(_.fpu_busy.get))(rsSource)
          val win = (0 to p.safetyResyncPcDelay)
            .map(d => ShiftRegister(srcBusy, d, false.B, true.B)).reduce(_ || _)
          !win
        } else true.B
      val releaseNow =
        if (p.enableFExtension && p.safetyResyncReleaseGate) (rsAge >= releaseAge) && releaseGateOpen
        else (rsAge === releaseAge)

      switch(rsPhase) {
        is(0.U) {
          when(rsTrigger) {
            when(devMulti) {

              rsDouble    := true.B
              rsEscalated := true.B
              rsArmed     := false.B
            }.otherwise {

              rsDeviant := devSet
              rsSource  := srcIdx
              rsRegs    := repRegs(srcIdx)
              if (p.enableFExtension) { rsFpRegs.get := repFpRegs.get(srcIdx) }

              rsCsr     := (if (p.safetyResyncInflight) repCsr(srcIdx)    else repCsrArch(srcIdx))
              rsPC      := (if (p.safetyResyncInflight) repPC(srcIdx)     else repPCarch(srcIdx))
              if (p.safetyResyncInflight) { rsInflight.get := repInflight.get(srcIdx) }
              rsValid   := true.B
              rsCount   := rsCount + 1.U
              rsArmed   := false.B
              rsAge     := 0.U
              rsPhase   := 1.U
            }
          }
        }
        is(1.U) {
          if (p.safetyResyncQuiesceAll) {

            when(rsAge === (QHALT - 1).U) {
              rsPhase := 2.U
              rsWait  := SETTLE.U
              rsAge   := 0.U
            }.otherwise {
              rsAge := rsAge + 1.U
            }
          } else if (p.enableFExtension && p.safetyResyncReleaseGate) {

            when(releaseNow) {
              rsPhase := 2.U
              rsWait  := SETTLE.U
              rsAge   := 0.U
            }.elsewhen(rsAge < releaseAge) {
              rsAge := rsAge + 1.U
            }
          } else {
            when(rsAge === releaseAge) {
              rsPhase := 2.U
              rsWait  := SETTLE.U
              rsAge   := 0.U
            }.otherwise {
              rsAge := rsAge + 1.U
            }
          }
        }
        is(2.U) {
          when(rsWait === 0.U) {

            when(devAny) {
              when(rsRetry === MAXRESYNC.U) {
                rsEscalated := true.B
                rsPhase := 0.U
                rsValid := false.B
              }.otherwise {
                rsRetry := rsRetry + 1.U
                rsArmed := true.B
                rsPhase := 0.U
              }
            }.otherwise {
              rsPhase := 0.U
              rsValid := false.B
            }
          }.otherwise {
            rsWait := rsWait - 1.U
          }
        }
      }

      rsSuppress.get := (rsPhase === 1.U) || (rsPhase === 2.U)

      val flushDev =
        if (p.enableFExtension && p.safetyResyncReleaseGate)
          (rsPhase === 1.U) && wbActive && !releaseNow
        else
          (rsPhase === 1.U) && wbActive && (rsAge < releaseAge)
      val wbCmd = Wire(new RollbackRestoreIO(CSR.archStateWidth(p.enableFExtension), true, p.enableFExtension, p.safetyResyncInflight))
      wbCmd.gprEn   := false.B
      wbCmd.gprAddr := 0.U
      wbCmd.gprData := 0.U
      wbCmd.parEn.get   := flushDev
      wbCmd.parData.get := rsRegs
      wbCmd.csrEn   := flushDev
      wbCmd.csr     := rsCsr
      wbCmd.pcEn    := flushDev
      wbCmd.pc      := rsPC
      wbCmd.flush   := flushDev
      if (p.enableFExtension) {

        wbCmd.fpParEn.get   := (if (p.safetyResyncFpRestore) flushDev else false.B)
        wbCmd.fpParData.get := rsFpRegs.get
      }
      if (p.safetyResyncInflight) {

        wbCmd.inflightEn.get := flushDev
        wbCmd.inflight.get   := rsInflight.get
      }

      val rsIdle = Wire(new RollbackRestoreIO(CSR.archStateWidth(p.enableFExtension), true, p.enableFExtension, p.safetyResyncInflight))
      rsIdle.gprEn := false.B; rsIdle.gprAddr := 0.U; rsIdle.gprData := 0.U
      rsIdle.parEn.get := false.B; rsIdle.parData.get := VecInit(Seq.fill(32)(0.U(32.W)))
      rsIdle.csrEn := false.B; rsIdle.csr := 0.U
      rsIdle.pcEn  := false.B; rsIdle.pc  := 0.U
      rsIdle.flush := false.B
      if (p.safetyResyncInflight) {
        rsIdle.inflightEn.get := false.B
        rsIdle.inflight.get   := 0.U.asTypeOf(new InflightSnapshot(p.enableFExtension))
      }
      if (p.enableFExtension) {
        rsIdle.fpParEn.get := false.B
        rsIdle.fpParData.get := VecInit(Seq.fill(32)(0.U(33.W)))
      }

      if (p.safetyResyncQuiesceAll) {

        val quiesceActive = (rsPhase === 1.U) && wbActive
        def quiesceCmdWith(en: Bool) = {
          val c = Wire(new RollbackRestoreIO(CSR.archStateWidth(p.enableFExtension), true, p.enableFExtension, p.safetyResyncInflight))
          c.gprEn   := false.B
          c.gprAddr := 0.U
          c.gprData := 0.U
          c.parEn.get   := en
          c.parData.get := rsRegs
          c.csrEn   := en
          c.csr     := rsCsr
          c.pcEn    := en
          c.pc      := rsPC
          c.flush   := en
          if (p.enableFExtension) {
            c.fpParEn.get   := (if (p.safetyResyncFpRestore) en else false.B)
            c.fpParData.get := rsFpRegs.get
          }
          c
        }
        core.io.rbRestore.get := quiesceCmdWith(quiesceActive)
        safetyShadows.zipWithIndex.foreach { case ((_, sh), j) =>
          val lag = (j + 1) * p.lockstepDelay
          sh.io.rbRestore.get := quiesceCmdWith(safetyDelay(quiesceActive, lag))
        }

        dmem.bufFlush.foreach(_ := quiesceActive)
      } else {

        core.io.rbRestore.get := Mux(wbActive && (devIdx === 0.U), wbCmd, rsIdle)
        safetyShadows.zipWithIndex.foreach { case ((_, sh), j) =>
          sh.io.rbRestore.get := Mux(wbActive && (devIdx === (j + 1).U), wbCmd, rsIdle)
        }

        if (p.safetyResyncInflight) dmem.bufFlush.foreach(_ := flushDev && (devIdx === 0.U))
        else                        dmem.bufFlush.foreach(_ := false.B)
      }

      when(mmioDecodeAddr === MMIO.SAFETY_RESYNC) {
        mmioVal := Cat(0.U(4.W), rsCount, rsEscalated, rsDouble, rsBusy, rsValid,
                       0.U(2.W), rsSource.pad(2), 0.U(1.W), rsDeviant.pad(3))
      }

      when(mmioDecodeAddr === MMIO.SAFETY_RESYNC_PC) { mmioVal := rsPC }

      val rs_observe_devlive   = WireInit(devOH);                    dontTouch(rs_observe_devlive)
      val rs_observe_deviant   = WireInit(rsDeviant);                dontTouch(rs_observe_deviant)
      val rs_observe_source    = WireInit(rsSource);                 dontTouch(rs_observe_source)
      val rs_observe_valid     = WireInit(rsValid);                  dontTouch(rs_observe_valid)
      val rs_observe_double    = WireInit(rsDouble);                 dontTouch(rs_observe_double)
      val rs_observe_escalated = WireInit(rsEscalated);              dontTouch(rs_observe_escalated)
      val rs_observe_count     = WireInit(rsCount);                  dontTouch(rs_observe_count)
      val rs_observe_busy      = WireInit(rsBusy);                   dontTouch(rs_observe_busy)
      val rs_observe_pc        = WireInit(rsPC);                     dontTouch(rs_observe_pc)
      val rs_observe_csr       = WireInit(rsCsr);                    dontTouch(rs_observe_csr)
      val rs_observe_regs      = WireInit(rsRegs.asUInt);            dontTouch(rs_observe_regs)

      val rs_observe_phase     = WireInit(rsPhase);                 dontTouch(rs_observe_phase)
      val rs_observe_age       = WireInit(rsAge);                   dontTouch(rs_observe_age)
      val rs_observe_release   = WireInit(releaseAge);              dontTouch(rs_observe_release)
      val rs_observe_flushdev  = WireInit(flushDev);                dontTouch(rs_observe_flushdev)
      val rs_observe_walken    = WireInit(flushDev);                dontTouch(rs_observe_walken)
      val rs_observe_suppress  = WireInit(rsSuppress.get);          dontTouch(rs_observe_suppress)
      val rs_observe_retry     = WireInit(rsRetry);                 dontTouch(rs_observe_retry)

      if (p.safetyResyncQuiesceAll) {
        val haltCnt = RegInit(0.U(16.W))
        when(rsPhase === 0.U) { haltCnt := 0.U }
          .elsewhen(rsPhase === 1.U && wbActive) { haltCnt := haltCnt + 1.U }
        val rs_observe_haltcycles = WireInit(haltCnt); dontTouch(rs_observe_haltcycles)
      }

      if (p.enableFExtension) {
        val obsBusyVec = VecInit(repCommit.map(_.fpu_busy.get))
        val obsSrcBusy = obsBusyVec(srcIdx)
        val rs_observe_fpubusy  = WireInit(obsSrcBusy);   dontTouch(rs_observe_fpubusy)
        val rs_observe_capready = WireInit(captureReady); dontTouch(rs_observe_capready)
        val capDuringFpu = RegInit(false.B)
        when(rsPhase === 0.U && rsTrigger && !devMulti) { capDuringFpu := obsSrcBusy }
        val rs_observe_capfpu   = WireInit(capDuringFpu); dontTouch(rs_observe_capfpu)
        val fpuSeenPending = RegInit(false.B)
        when(!devAny) { fpuSeenPending := false.B }
          .elsewhen(obsSrcBusy) { fpuSeenPending := true.B }
        val rs_observe_fpuseen  = WireInit(fpuSeenPending); dontTouch(rs_observe_fpuseen)

        val rs_observe_relgate  = WireInit(releaseGateOpen); dontTouch(rs_observe_relgate)
        val relHeld = RegInit(0.U(16.W))
        when(rsPhase === 0.U) { relHeld := 0.U }
          .elsewhen(rsPhase === 1.U && (rsAge >= releaseAge) && !releaseGateOpen) { relHeld := relHeld + 1.U }
        val rs_observe_relheld  = WireInit(relHeld); dontTouch(rs_observe_relheld)
        val fpRestoreFired = RegInit(false.B)
        when(!rsValid && rsPhase === 0.U) { fpRestoreFired := false.B }
          .elsewhen(wbCmd.fpParEn.get && wbActive) { fpRestoreFired := true.B }
        val rs_observe_fprestore = WireInit(fpRestoreFired); dontTouch(rs_observe_fprestore)
      }
    }

    if (p.safetyStoreGate || mmioGateBlockNow.isDefined) {
      val sgTestWindow = safetySelfTest ||
        (0 to maxLag).map(d => ShiftRegister(safetyWalkBusy, d, false.B, true.B)).reduce(_ || _)

      val blkFiEn  = if (p.safetyEgressFi) Some(RegInit(false.B)) else None
      val blkFiVal = if (p.safetyEgressFi) Some(RegInit(false.B)) else None
      blkFiEn.foreach(dontTouch(_))
      blkFiVal.foreach(dontTouch(_))
      val blkVerdict =
        blkFiEn.map(en => (mismatchNow && !en) || (blkFiVal.get && en)).getOrElse(mismatchNow)
      mmioGateBlockNow.foreach(_ := blkVerdict && !sgTestWindow)

      egressDisagreeRaw.foreach { raw =>
        egressDisagree.get := raw && !sgTestWindow && !rbSuppress.getOrElse(false.B)
      }
      if (p.safetyStoreGate) {
        dmem.gateBlock.get := storeGateDiverge.get && !sgTestWindow

        val sgDropSticky = RegInit(false.B)
        val sgDropCnt    = RegInit(0.U(16.W))
        val sgDrop = dmem.gateDrop.get
        when(sgDrop) { sgDropSticky := true.B }
        when(sgDrop && sgDropCnt =/= "hFFFF".U(16.W)) { sgDropCnt := sgDropCnt + 1.U }
        when(isMmioWriteRegion && isStore && (dmemDecodeAddr === MMIO.SAFETY_SG)) {
          sgDropSticky := sgDrop
          sgDropCnt    := 0.U
        }
        when(mmioDecodeAddr === MMIO.SAFETY_SG) {
          mmioVal := Cat(0.U(15.W), sgDropCnt, sgDropSticky)
        }
        val sg_observe_drop = WireInit(sgDrop)
        dontTouch(sg_observe_drop)
        val sg_observe_block = WireInit(dmem.gateBlock.get)
        dontTouch(sg_observe_block)
      }
    }
  }

  if (p.withMemEcc) {
    val memEccCorrAny = dmem.eccCorr.get   || imem.map(_.eccCorr.get).getOrElse(false.B)
    val memEccUncAny  = dmem.eccUncorr.get || imem.map(_.eccUncorr.get).getOrElse(false.B)
    val memEccUncorrSticky = RegInit(false.B)
    val memEccCorrCnt      = RegInit(0.U(16.W))
    when(memEccCorrAny && memEccCorrCnt =/= "hFFFF".U(16.W)) { memEccCorrCnt := memEccCorrCnt + 1.U }
    when(memEccUncAny) { memEccUncorrSticky := true.B }
    when(isMmioWriteRegion && isStore && (dmemDecodeAddr === MMIO.MEM_ECC)) {
      memEccCorrCnt      := Mux(memEccCorrAny, 1.U, 0.U)
      memEccUncorrSticky := memEccUncAny
    }
    memEccFaultIrq.get := memEccUncorrSticky
    when(mmioDecodeAddr === MMIO.MEM_ECC) {
      mmioVal := Cat(0.U(15.W), memEccCorrCnt, memEccUncorrSticky)
    }
    val memecc_observe_corr = WireInit(memEccCorrAny)
    dontTouch(memecc_observe_corr)
    val memecc_observe_uncorr_sticky = WireInit(memEccUncorrSticky)
    dontTouch(memecc_observe_uncorr_sticky)

    val memEccFaultAddr = RegInit(0.U(32.W))
    imem.foreach { im =>
      when(im.eccUncorr.get) { memEccFaultAddr := RegNext(core.io.imem.addr) }
    }
    when(mmioDecodeAddr === MMIO.MEM_ECC_ADDR) { mmioVal := memEccFaultAddr }

    when(imem_wrEn_reg && !bootMode) { scrubArmedReg.get := false.B }
    when(isMmioWriteRegion && isStore && (dmemDecodeAddr === MMIO.IMEM_SCRUB_ADDR)) {
      imemWrPtr := core.io.dmem.req.data(30, 2)
      scrubArmedReg.get := true.B
    }
    val scrub_observe_armed = WireInit(scrubArmedReg.get)
    dontTouch(scrub_observe_armed)
  }
}

object TopLevel extends App {

  val (params, outDir, isBare) = args.toList match {
    case Nil =>
      (TopParams(), new File("generated"), true)
    case name :: Nil =>
      TopParams.all.get(name) match {
        case Some(p) =>
          (p, new File(s"generated/$name"), false)
        case None =>
          System.err.println(s"TopLevel: unknown config '$name'.")
          System.err.println(
            s"Valid configs: ${TopParams.all.keys.toSeq.sorted.mkString(", ")}"
          )
          sys.exit(1)
      }
    case _ =>
      System.err.println("Usage: runMain sacris_v.TopLevel [<config-name>]")
      System.err.println(
        s"Valid configs: ${TopParams.all.keys.toSeq.sorted.mkString(", ")}"
      )
      sys.exit(1)
  }

  outDir.mkdirs()

  val chiselArgs: Array[String] =
    if (isBare) Array.empty else Array("--target-dir", outDir.getPath)

  ChiselStage.emitSystemVerilogFile(
    new TopLevel(params),
    chiselArgs,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )

  val dtsPath = new File(outDir, "sacris_v.dts")
  val pw = new PrintWriter(dtsPath)
  pw.write(DtsGenerator.generate(params))
  pw.close()
  println(s"DTS written to ${dtsPath.getPath}")
}
