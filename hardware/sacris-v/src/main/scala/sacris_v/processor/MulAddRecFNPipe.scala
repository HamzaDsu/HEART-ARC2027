// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Pipelined fused multiply-add wrapper around HardFloat.

package sacris_v.processor

import chisel3._
import chisel3.util._
import hardfloat._

class MulAddRecFNPipe(val latency: Int, val expWidth: Int, val sigWidth: Int) extends Module {
  require(latency == 1 || latency == 2,
    s"MulAddRecFNPipe latency must be 1 or 2, got $latency (see research doc §4 Class B)")

  val io = IO(new Bundle {
    val op             = Input(UInt(2.W))
    val a              = Input(UInt((expWidth + sigWidth + 1).W))
    val b              = Input(UInt((expWidth + sigWidth + 1).W))
    val c              = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode   = Input(UInt(3.W))
    val detectTininess = Input(UInt(1.W))
    val out            = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val preMul = Module(new MulAddRecFNToRaw_preMul(expWidth, sigWidth))
  preMul.io.op := io.op
  preMul.io.a  := io.a
  preMul.io.b  := io.b
  preMul.io.c  := io.c
  val mulAddResult0 =
    (preMul.io.mulAddA * preMul.io.mulAddB) +& preMul.io.mulAddC

  val toPostMulSig      = if (latency == 2) RegNext(preMul.io.toPostMul) else preMul.io.toPostMul
  val mulAddResultSig   = if (latency == 2) RegNext(mulAddResult0)        else mulAddResult0
  val roundingModeSig   = if (latency == 2) RegNext(io.roundingMode)      else io.roundingMode
  val detectTininessSig = if (latency == 2) RegNext(io.detectTininess)    else io.detectTininess

  val postMul = Module(new MulAddRecFNToRaw_postMul(expWidth, sigWidth))
  postMul.io.fromPreMul   := toPostMulSig
  postMul.io.mulAddResult := mulAddResultSig
  postMul.io.roundingMode := roundingModeSig

  val round = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  round.io.invalidExc     := postMul.io.invalidExc
  round.io.infiniteExc    := false.B
  round.io.in             := postMul.io.rawOut
  round.io.roundingMode   := roundingModeSig
  round.io.detectTininess := detectTininessSig

  io.out            := round.io.out
  io.exceptionFlags := round.io.exceptionFlags
}
