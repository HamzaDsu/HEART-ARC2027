// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Pipelined multiplier used by the M extension.

package mdu

import chisel3._
import chisel3.util._

class HWMultiplier(splitPartials: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val reqValid  = Input(Bool())
    val op        = Input(UInt(2.W))
    val rs1       = Input(UInt(32.W))
    val rs2       = Input(UInt(32.W))
    val respValid = Output(Bool())
    val result    = Output(UInt(32.W))
  })

  val aSigned = !(io.op(1) && io.op(0))
  val bSigned = !io.op(1)

  val aExt = Mux(aSigned, Cat(io.rs1(31), io.rs1), Cat(0.U(1.W), io.rs1)).asSInt
  val bExt = Mux(bSigned, Cat(io.rs2(31), io.rs2), Cat(0.U(1.W), io.rs2)).asSInt

  val s1_valid = RegNext(io.reqValid, false.B)
  val s1_a     = RegEnable(aExt, io.reqValid)
  val s1_b     = RegEnable(bExt, io.reqValid)
  val s1_op    = RegEnable(io.op, io.reqValid)

  val s2_valid = RegNext(s1_valid, false.B)

  val s2_prod = if (splitPartials) {
    val bLo    = s1_b(16, 0)
    val bHi    = s1_b(32, 17).asSInt
    val s2_pLo = RegEnable(s1_a * bLo.zext, s1_valid)
    val s2_pHi = RegEnable(s1_a * bHi,      s1_valid)
    (s2_pLo + (s2_pHi << 17)).asUInt
  } else {
    RegEnable((s1_a * s1_b).asUInt, s1_valid)
  }

  val s2_op = RegEnable(s1_op, s1_valid)

  val s3_valid = RegNext(s2_valid, false.B)
  val s3_isHigh = s2_op =/= 0.U
  val s3_sel   = Mux(s3_isHigh, s2_prod(63, 32), s2_prod(31, 0))
  val s3_res   = RegEnable(s3_sel, s2_valid)

  io.respValid := s3_valid
  io.result    := s3_res
}
