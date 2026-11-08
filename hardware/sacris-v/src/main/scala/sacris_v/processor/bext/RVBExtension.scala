// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// B extension: opcode tables, result bundle, the BExt ALU and the Zba unit.

package bext

import chisel3._
import chisel3.util._
import chisel3.util.MuxCase

case class BExtConfig(
  enableZba: Boolean = true,
  enableZbb: Boolean = true,
  enableZbc: Boolean = true,
  enableZbs: Boolean = true
)

object BExtOpcodes {

  val SH1ADD   = "b0010000".U(7.W)
  val SH2ADD   = "b0010000".U(7.W)
  val SH3ADD   = "b0010000".U(7.W)
  val F3_SH1ADD = "b010".U(3.W)
  val F3_SH2ADD = "b100".U(3.W)
  val F3_SH3ADD = "b110".U(3.W)

  val F7_ANDN_ORN_XNOR = "b0100000".U(7.W)
  val F7_CLZ_CTZ_CPOP  = "b0110000".U(7.W)
  val F7_MAX           = "b0000101".U(7.W)
  val F7_MIN           = "b0000101".U(7.W)
  val F7_ROT           = "b0110000".U(7.W)
  val F7_REV8_ORCB     = "b0110100".U(7.W)
  val F7_ZEXT_H        = "b0000100".U(7.W)

  val F3_ANDN  = "b111".U(3.W)
  val F3_ORN   = "b110".U(3.W)
  val F3_XNOR  = "b100".U(3.W)
  val F3_CLZ   = "b001".U(3.W)
  val F3_CTZ   = "b001".U(3.W)
  val F3_CPOP  = "b001".U(3.W)
  val F3_MAX   = "b110".U(3.W)
  val F3_MAXU  = "b111".U(3.W)
  val F3_MIN   = "b100".U(3.W)
  val F3_MINU  = "b101".U(3.W)
  val F3_ROL   = "b001".U(3.W)
  val F3_ROR   = "b101".U(3.W)
  val F3_SEXT_B = "b001".U(3.W)
  val F3_SEXT_H = "b001".U(3.W)

  val F7_CLMUL  = "b0000101".U(7.W)
  val F3_CLMUL  = "b001".U(3.W)
  val F3_CLMULH = "b011".U(3.W)
  val F3_CLMULR = "b010".U(3.W)

  val F7_BCLR  = "b0100100".U(7.W)
  val F7_BEXT  = "b0100100".U(7.W)
  val F7_BINV  = "b0110100".U(7.W)
  val F7_BSET  = "b0010100".U(7.W)
  val F3_BCLR  = "b001".U(3.W)
  val F3_BEXT  = "b101".U(3.W)
  val F3_BINV  = "b001".U(3.W)
  val F3_BSET  = "b001".U(3.W)
}

class BExtResult extends Bundle {
  val result = UInt(32.W)
  val valid  = Bool()
}

class BExtALU(cfg: BExtConfig = BExtConfig()) extends Module {
  val io = IO(new Bundle {
    val rs1    = Input(UInt(32.W))
    val rs2    = Input(UInt(32.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val rs2sel = Input(UInt(5.W))
    val itype  = Input(Bool())
    val out    = Output(new BExtResult)
  })

  val result = Wire(UInt(32.W))
  val valid  = Wire(Bool())
  result := 0.U
  valid  := false.B

  if (cfg.enableZba) {
    val zba = Module(new ZbaUnit)
    zba.io.rs1    := io.rs1
    zba.io.rs2    := io.rs2
    zba.io.funct3 := io.funct3
    zba.io.funct7 := io.funct7
    when(zba.io.out.valid) {
      result := zba.io.out.result
      valid  := true.B
    }
  }

  if (cfg.enableZbb) {
    val zbb = Module(new ZbbUnit)
    zbb.io.rs1    := io.rs1
    zbb.io.rs2    := io.rs2
    zbb.io.funct3 := io.funct3
    zbb.io.funct7 := io.funct7
    zbb.io.rs2sel := io.rs2sel
    zbb.io.itype  := io.itype
    when(zbb.io.out.valid) {
      result := zbb.io.out.result
      valid  := true.B
    }
  }

  if (cfg.enableZbc) {
    val zbc = Module(new ZbcUnit)
    zbc.io.rs1    := io.rs1
    zbc.io.rs2    := io.rs2
    zbc.io.funct3 := io.funct3
    zbc.io.funct7 := io.funct7
    when(zbc.io.out.valid) {
      result := zbc.io.out.result
      valid  := true.B
    }
  }

  if (cfg.enableZbs) {
    val zbs = Module(new ZbsUnit)
    zbs.io.rs1    := io.rs1
    zbs.io.rs2    := io.rs2
    zbs.io.funct3 := io.funct3
    zbs.io.funct7 := io.funct7
    when(zbs.io.out.valid) {
      result := zbs.io.out.result
      valid  := true.B
    }
  }

  io.out.result := result
  io.out.valid  := valid
}

class ZbaUnit extends Module {
  val io = IO(new Bundle {
    val rs1    = Input(UInt(32.W))
    val rs2    = Input(UInt(32.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val out    = Output(new BExtResult)
  })

  import BExtOpcodes._

  val result = Wire(UInt(32.W))
  val valid  = Wire(Bool())
  result := 0.U
  valid  := false.B

  when(io.funct7 === SH1ADD) {
    switch(io.funct3) {
      is(F3_SH1ADD) { result := io.rs2 + (io.rs1 << 1.U); valid := true.B }
      is(F3_SH2ADD) { result := io.rs2 + (io.rs1 << 2.U); valid := true.B }
      is(F3_SH3ADD) { result := io.rs2 + (io.rs1 << 3.U); valid := true.B }
    }
  }

  io.out.result := result
  io.out.valid  := valid
}
