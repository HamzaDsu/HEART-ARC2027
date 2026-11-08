// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Multiply/divide unit of the M extension.

package mdu

import chisel3._
import chisel3.util._

class RV32M_Radix4_MDU(
  divStepsPerCycle:  Int     = 1,
  registeredOutput:  Boolean = true,
  splitPartials:     Boolean = false
) extends Module {

  require(divStepsPerCycle >= 1 && divStepsPerCycle <= 16,
    s"divStepsPerCycle=$divStepsPerCycle muss zwischen 1 und 16 liegen")
  require(16 % divStepsPerCycle == 0,
    s"16 muss durch divStepsPerCycle=$divStepsPerCycle teilbar sein (erlaubt: 1,2,4,8,16)")

  val io = IO(new Bundle {
    val reqValid  = Input(Bool())
    val op        = Input(UInt(3.W))
    val rs1       = Input(UInt(32.W))
    val rs2       = Input(UInt(32.W))

    val stall     = Output(Bool())
    val respValid = Output(Bool())
    val result    = Output(UInt(32.W))
  })

  object Op {
    val MUL    = "b000".U(3.W)
    val MULH   = "b001".U(3.W)
    val MULHSU = "b010".U(3.W)
    val MULHU  = "b011".U(3.W)
    val DIV    = "b100".U(3.W)
    val DIVU   = "b101".U(3.W)
    val REM    = "b110".U(3.W)
    val REMU   = "b111".U(3.W)
  }

  private def abs32(x: UInt): UInt = Mux(x(31), (~x).asUInt + 1.U, x)

  val DivItersLocal = 16 / divStepsPerCycle
  val IterBitsLocal = log2Ceil(DivItersLocal + 1)

  val busy        = RegInit(false.B)
  val resultPhase = RegInit(false.B)
  val iter        = RegInit(0.U(IterBitsLocal.W))
  val opReg       = Reg(UInt(3.W))
  val operandA    = Reg(UInt(32.W))

  val divNegQuo   = Reg(Bool())
  val divNegRem   = Reg(Bool())
  val remReg      = Reg(UInt(34.W))
  val quoReg      = Reg(UInt(32.W))
  val divD1Reg    = Reg(UInt(34.W))
  val divD2Reg    = Reg(UInt(34.W))
  val divD3Reg    = Reg(UInt(34.W))
  val divIsSpecial  = RegInit(false.B)
  val divSpecialQuo = Reg(UInt(32.W))
  val divSpecialRem = Reg(UInt(32.W))

  val hwMul = Module(new HWMultiplier(splitPartials))

  val mulStall1 = RegInit(false.B)
  val mulStall2 = RegInit(false.B)

  val divIterDone = (DivItersLocal - 1).U

  val mulInFlight = mulStall1 || mulStall2 || hwMul.io.respValid
  val idle = if (registeredOutput) {
    !busy && !resultPhase && !mulInFlight
  } else {
    !busy && !mulInFlight
  }
  val acceptReq      = io.reqValid && idle
  val acceptReqIsMul = acceptReq && !io.op(2)
  val acceptReqIsDiv = acceptReq &&  io.op(2)

  hwMul.io.reqValid := acceptReqIsMul
  hwMul.io.op       := io.op(1, 0)
  hwMul.io.rs1      := io.rs1
  hwMul.io.rs2      := io.rs2

  mulStall1 := acceptReqIsMul
  mulStall2 := mulStall1

  when(acceptReqIsDiv) {
    busy  := true.B
    iter  := 0.U
    opReg := io.op

    val aNeg = io.rs1(31)
    val bNeg = io.rs2(31)
    val divSigned     = (io.op === Op.DIV) || (io.op === Op.REM)
    val divisorIsZero = (io.rs2 === 0.U)

    val aAbsDiv = Mux(divSigned && aNeg, abs32(io.rs1), io.rs1)
    val bAbsDiv = Mux(divSigned && bNeg, abs32(io.rs2), io.rs2)

    operandA  := aAbsDiv
    divNegQuo := Mux(divSigned, aNeg ^ bNeg, false.B)
    divNegRem := Mux(divSigned, aNeg, false.B)

    remReg := 0.U
    quoReg := 0.U

    val d34 = Cat(0.U(2.W), bAbsDiv)
    divD1Reg := d34
    divD2Reg := Cat(0.U(1.W), bAbsDiv, 0.U(1.W))
    divD3Reg := (d34 +& (d34 << 1))(33, 0)

    val intMin         = "h80000000".U(32.W)
    val minusOne       = "hffffffff".U(32.W)
    val overflowSigned = divSigned && (io.rs1 === intMin) && (io.rs2 === minusOne)

    divIsSpecial  := divisorIsZero || overflowSigned
    divSpecialQuo := Mux(divisorIsZero, "hffffffff".U, intMin)
    divSpecialRem := Mux(divisorIsZero, io.rs1, 0.U)
  }

  def divStepHW(rem: UInt, quo: UInt, dividendAbs: UInt, globalIdx: UInt,
                d1: UInt, d2: UInt, d3: UInt): (UInt, UInt) = {
    val idxFromMsb = 15.U - globalIdx
    val next2      = (dividendAbs >> (idxFromMsb << 1))(1, 0)
    val remShift   = ((rem << 2) | next2)(33, 0)

    val ge3 = remShift >= d3
    val ge2 = remShift >= d2
    val ge1 = remShift >= d1

    val qDigit  = Wire(UInt(2.W))
    val remNext = Wire(UInt(34.W))

    when(ge3) {
      qDigit  := 3.U
      remNext := (remShift - d3)(33, 0)
    }.elsewhen(ge2) {
      qDigit  := 2.U
      remNext := (remShift - d2)(33, 0)
    }.elsewhen(ge1) {
      qDigit  := 1.U
      remNext := (remShift - d1)(33, 0)
    }.otherwise {
      qDigit  := 0.U
      remNext := remShift
    }

    val quoNext = ((quo << 2) | qDigit)(31, 0)
    (remNext, quoNext)
  }

  def divChainHW(rem: UInt, quo: UInt, dividendAbs: UInt, fsmIter: UInt,
                 d1: UInt, d2: UInt, d3: UInt): (UInt, UInt) = {
    var r = rem
    var q = quo
    for (s <- 0 until divStepsPerCycle) {
      val globalIdx = fsmIter * divStepsPerCycle.U + s.U
      val (rNext, qNext) = divStepHW(r, q, dividendAbs, globalIdx, d1, d2, d3)
      r = rNext
      q = qNext
    }
    (r, q)
  }

  def computeDivResult(quo: UInt, rem: UInt): UInt = {
    val quoU = Mux(divIsSpecial, divSpecialQuo, quo)
    val remU = Mux(divIsSpecial, divSpecialRem, rem(31, 0))

    val quoSigned = Mux(divNegQuo, (~quoU).asUInt + 1.U, quoU)
    val remSigned = Mux(divNegRem, (~remU).asUInt + 1.U, remU)

    val quoFinal = Mux(divIsSpecial, divSpecialQuo, quoSigned)
    val remFinal = Mux(divIsSpecial, divSpecialRem, remSigned)

    Mux(opReg === Op.DIV || opReg === Op.DIVU, quoFinal, remFinal)
  }

  val divChainRem = WireDefault(remReg)
  val divChainQuo = WireDefault(quoReg)
  val divResultComb = Wire(UInt(32.W))
  divResultComb := 0.U

  when(busy) {
    when(!divIsSpecial) {
      val (rOut, qOut) = divChainHW(remReg, quoReg, operandA, iter,
                                     divD1Reg, divD2Reg, divD3Reg)
      divChainRem := rOut
      divChainQuo := qOut
      remReg      := rOut
      quoReg      := qOut
    }

    when(iter === divIterDone) {
      busy := false.B
      iter := 0.U

      if (registeredOutput) {
        resultPhase := true.B
      } else {
        divResultComb := computeDivResult(divChainQuo, divChainRem)
      }
    }.otherwise {
      iter := iter + 1.U
    }
  }

  if (registeredOutput) {
    when(resultPhase) {
      resultPhase   := false.B
      divResultComb := computeDivResult(quoReg, remReg)
    }
  }

  val divRespValid =
    if (registeredOutput) resultPhase
    else busy && (iter === divIterDone)
  val divStallRaw =
    if (registeredOutput) busy
    else busy && !(iter === divIterDone)

  io.stall     := divStallRaw || mulStall1 || mulStall2
  io.respValid := divRespValid || hwMul.io.respValid
  io.result    := Mux(hwMul.io.respValid, hwMul.io.result, divResultComb)
}
