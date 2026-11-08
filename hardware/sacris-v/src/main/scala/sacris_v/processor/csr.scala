// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Control and status registers, trap entry/return, interrupt handling.

package sacris_v.processor

import chisel3._
import chisel3.util._

class CsrRegs extends Bundle {
  val mtvec = UInt(32.W)
  val mcause = UInt(32.W)
  val testReg = UInt(32.W)
  val mstatus = UInt(32.W)
  val mepc = UInt(32.W)
  val mscratch = UInt(32.W)
  val mie = UInt(32.W)
}

object CSR {

  def archStateWidth(enableFExtension: Boolean): Int =
    8 * 32 + 64 + 64 + (if (enableFExtension) 8 else 0)
}

class CsrIO(enableFExtension: Boolean = false, exposeArchState: Boolean = false,
            exposeRestore: Boolean = false) extends Bundle {
  val sysCnt0 = Input(UInt(64.W))
  val rs1 = Input(UInt(32.W))
  val instruction = Input(UInt(32.W))

  val timerInterrupt = Input(Bool())
  val softwareInterrupt = Input(Bool())
  val externalInterrupt = Input(Bool())
  val faultInterrupt = Input(Bool())

  val syncException      = Input(Bool())
  val syncExceptionCause = Input(UInt(32.W))

  val interrupt_pc  = Input(UInt(32.W))
  val exception_pc  = Input(UInt(32.W))

  val mret = Input(Bool())

  val mip_mtip = Input(Bool())
  val mip_msip = Input(Bool())
  val mip_meip = Input(Bool())

  val instRetire = Input(Bool())

  val mtval_value = Input(UInt(32.W))

  val fp_excFlags       = if (enableFExtension) Some(Input(UInt(5.W)))  else None
  val fp_excFlags_valid = if (enableFExtension) Some(Input(Bool()))     else None

  val frm_out           = if (enableFExtension) Some(Output(UInt(3.W))) else None

  val out = Output(UInt(32.W))
  val o = Output(new CsrRegs)

  val archState = if (exposeArchState) Some(Output(UInt(CSR.archStateWidth(enableFExtension).W))) else None

  val restoreEn   = if (exposeRestore) Some(Input(Bool())) else None
  val restoreArch = if (exposeRestore) Some(Input(UInt(CSR.archStateWidth(enableFExtension).W))) else None
}

class CSR(enableFExtension: Boolean = false, exposeArchState: Boolean = false,
          exposeRestore: Boolean = false) extends Module {
  val io: CsrIO = IO(new CsrIO(enableFExtension, exposeArchState, exposeRestore))
  val csrRegs = RegInit(0.U.asTypeOf(new CsrRegs))

  val mtvecReg = RegInit(0.U(32.W))
  val mepcReg  = RegInit(0.U(32.W))

  val mtvalReg = RegInit(0.U(32.W))

  val minstretReg = RegInit(0.U(64.W))

  val mcycleReg = RegInit(0.U(64.W))

  private val rs      = io.instruction(19, 15)
  private val csrAddr = io.instruction(31, 20)
  private val funct3  = io.instruction(14, 12)

  val isInterrupt = io.timerInterrupt || io.softwareInterrupt || io.externalInterrupt || io.faultInterrupt
  val isTrap      = isInterrupt || io.syncException

  val isCSRW = funct3(1, 0) === "b01".U
  val isCSRS = funct3(1, 0) === "b10".U
  val isCSRC = funct3(1, 0) === "b11".U

  val csrWriteEn = io.instruction(6, 0) === "b1110011".asUInt &&
    (isCSRW || ((isCSRS || isCSRC) && rs =/= 0.U))

  val minstretWritten = csrWriteEn && (csrAddr === 0xB02.U || csrAddr === 0xB82.U)
  val mcycleWritten   = csrWriteEn && (csrAddr === 0xB00.U || csrAddr === 0xB80.U)
  val suppressInstret = RegNext(minstretWritten, false.B)
  val suppressMcycle  = RegNext(mcycleWritten,   false.B)

  val minstretFwd = Mux(io.instRetire && !suppressInstret, minstretReg + 1.U, minstretReg)
  val mcycleFwd   = Mux(!suppressMcycle, mcycleReg + 1.U, mcycleReg)

  val fflagsReg = if (enableFExtension) Some(RegInit(0.U(5.W))) else None
  val frmReg    = if (enableFExtension) Some(RegInit(0.U(3.W))) else None

  val fflagsReadFwd = if (enableFExtension) {
    Mux(io.fp_excFlags_valid.get, fflagsReg.get | io.fp_excFlags.get, fflagsReg.get)
  } else 0.U(5.W)

  val fpReadArms = if (enableFExtension) Seq(
    (csrAddr === 0x001.U) -> Cat(0.U(27.W), fflagsReadFwd),
    (csrAddr === 0x002.U) -> Cat(0.U(29.W), frmReg.get),
    (csrAddr === 0x003.U) -> Cat(0.U(24.W), frmReg.get, fflagsReadFwd),
  ) else Seq.empty

  val csrReadValue = MuxCase(0.U, Seq(
    (csrAddr === 0xf14.U) -> 0.U(32.W),
    (csrAddr === 0x301.U) -> "h40001102".U(32.W),
    (csrAddr === 0x343.U) -> mtvalReg,
    (csrAddr === 0xf12.U) -> csrRegs.testReg,
    (csrAddr === 0x45.U)  -> io.sysCnt0(31, 0),
    (csrAddr === 0x305.U) -> mtvecReg,
    (csrAddr === 0x342.U) -> csrRegs.mcause,
    (csrAddr === 0x341.U) -> mepcReg,
    (csrAddr === 0x300.U) -> Cat(csrRegs.mstatus(31, 13), "b11".U(2.W), csrRegs.mstatus(10, 0)),
    (csrAddr === 0x340.U) -> csrRegs.mscratch,
    (csrAddr === 0x304.U) -> csrRegs.mie,
    (csrAddr === 0x344.U) -> Cat(0.U(20.W), io.mip_meip, 0.U(3.W), io.mip_mtip, 0.U(3.W), io.mip_msip, 0.U(3.W)),
    (csrAddr === 0xC02.U) -> minstretFwd(31, 0),
    (csrAddr === 0xC82.U) -> minstretFwd(63, 32),
    (csrAddr === 0xB02.U) -> minstretFwd(31, 0),
    (csrAddr === 0xB82.U) -> minstretFwd(63, 32),
    (csrAddr === 0xC00.U) -> mcycleFwd(31, 0),
    (csrAddr === 0xC80.U) -> mcycleFwd(63, 32),
    (csrAddr === 0xB00.U) -> mcycleFwd(31, 0),
    (csrAddr === 0xB80.U) -> mcycleFwd(63, 32)
  ) ++ fpReadArms)

  val csrWriteValue = MuxCase(io.rs1, Seq(
    isCSRS -> (csrReadValue | io.rs1),
    isCSRC -> (csrReadValue & ~io.rs1)
  ))

  when(!suppressMcycle) {
    mcycleReg := mcycleReg + 1.U
  }

  when(io.instRetire && !suppressInstret) {
    minstretReg := minstretReg + 1.U
  }

  if (enableFExtension) {
    when(io.fp_excFlags_valid.get) {
      fflagsReg.get := fflagsReg.get | io.fp_excFlags.get
    }
  }

  when(csrWriteEn) {
    when(csrAddr === 0xf12.U) { csrRegs.testReg  := csrWriteValue }
    when(csrAddr === 0x305.U) { mtvecReg         := Cat(csrWriteValue(31, 2), 0.U(2.W)) }
    when(csrAddr === 0x342.U) { csrRegs.mcause   := csrWriteValue }
    when(csrAddr === 0x300.U) { csrRegs.mstatus  := Cat(csrWriteValue(31, 13), "b11".U(2.W), csrWriteValue(10, 0)) }
    when(csrAddr === 0x341.U) { mepcReg          := csrWriteValue }
    when(csrAddr === 0x340.U) { csrRegs.mscratch := csrWriteValue }
    when(csrAddr === 0x304.U) { csrRegs.mie      := csrWriteValue }
    when(csrAddr === 0x343.U) { mtvalReg         := csrWriteValue }

    when(csrAddr === 0xB02.U) { minstretReg := Cat(minstretReg(63, 32), csrWriteValue(31, 0)) }
    when(csrAddr === 0xB82.U) { minstretReg := Cat(csrWriteValue(31, 0), minstretReg(31, 0)) }

    when(csrAddr === 0xB00.U) { mcycleReg := Cat(mcycleReg(63, 32), csrWriteValue(31, 0)) }
    when(csrAddr === 0xB80.U) { mcycleReg := Cat(csrWriteValue(31, 0), mcycleReg(31, 0)) }

    if (enableFExtension) {

      when(csrAddr === 0x001.U) { fflagsReg.get := csrWriteValue(4, 0) }

      when(csrAddr === 0x002.U) { frmReg.get    := csrWriteValue(2, 0) }

      when(csrAddr === 0x003.U) {
        fflagsReg.get := csrWriteValue(4, 0)
        frmReg.get    := csrWriteValue(7, 5)
      }
    }
  }

  if (enableFExtension) {
    io.frm_out.get := frmReg.get
  }

  when(io.mret && !isTrap) {
    csrRegs.mstatus := Cat(
      csrRegs.mstatus(31, 8),
      1.U(1.W),
      csrRegs.mstatus(6, 4),
      csrRegs.mstatus(7),
      csrRegs.mstatus(2, 0)
    )
  }

  when(isTrap) {
    csrRegs.mstatus := Cat(
      csrRegs.mstatus(31, 8),
      csrRegs.mstatus(3),
      csrRegs.mstatus(6, 4),
      0.U(1.W),
      csrRegs.mstatus(2, 0)
    )

    mepcReg := Mux(isInterrupt, io.interrupt_pc, io.exception_pc)

    mtvalReg := Mux(isInterrupt, 0.U, io.mtval_value)

    csrRegs.mcause := MuxCase(io.syncExceptionCause, Seq(
      io.timerInterrupt    -> Cat(1.U(1.W), 0.U(24.W), 7.U(7.W)),
      io.softwareInterrupt -> Cat(1.U(1.W), 0.U(24.W), 3.U(7.W)),
      io.externalInterrupt -> Cat(1.U(1.W), 0.U(24.W), 11.U(7.W)),
      io.faultInterrupt    -> Cat(1.U(1.W), 0.U(24.W), 2.U(7.W))
    ))
  }

  io.out := csrReadValue

  io.o <> csrRegs

  io.o.mtvec := mtvecReg
  io.o.mepc  := mepcReg

  io.archState.foreach { as =>
    val intState = Cat(
      csrRegs.mstatus, csrRegs.mie, mtvecReg, mepcReg,
      csrRegs.mcause, csrRegs.mscratch, mtvalReg, csrRegs.testReg,
      minstretReg, mcycleReg
    )
    as := (if (enableFExtension) Cat(intState, frmReg.get, fflagsReg.get) else intState)
  }

  io.restoreEn.foreach { ren =>
    val v = io.restoreArch.get
    val fpLow = if (enableFExtension) 8 else 0
    when(ren) {
      mcycleReg        := v(fpLow + 63,  fpLow + 0)
      minstretReg      := v(fpLow + 127, fpLow + 64)
      csrRegs.testReg  := v(fpLow + 159, fpLow + 128)
      mtvalReg         := v(fpLow + 191, fpLow + 160)
      csrRegs.mscratch := v(fpLow + 223, fpLow + 192)
      csrRegs.mcause   := v(fpLow + 255, fpLow + 224)
      mepcReg          := v(fpLow + 287, fpLow + 256)
      mtvecReg         := v(fpLow + 319, fpLow + 288)
      csrRegs.mie      := v(fpLow + 351, fpLow + 320)
      csrRegs.mstatus  := v(fpLow + 383, fpLow + 352)

      csrRegs.mtvec    := v(fpLow + 319, fpLow + 288)
      csrRegs.mepc     := v(fpLow + 287, fpLow + 256)
      if (enableFExtension) {
        frmReg.get    := v(7, 5)
        fflagsReg.get := v(4, 0)
      }
    }
  }
}
