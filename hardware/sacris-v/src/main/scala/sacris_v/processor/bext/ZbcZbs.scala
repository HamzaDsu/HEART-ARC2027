// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Zbc (carry-less multiply) and Zbs (single-bit) units.

package bext

import chisel3._
import chisel3.util._
import chisel3.util.Reverse

class ZbcUnit extends Module {
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

  val product = Wire(UInt(64.W))
  val partials = Wire(Vec(32, UInt(64.W)))

  for (i <- 0 until 32) {
    partials(i) := Mux(io.rs2(i), io.rs1.asUInt << i.U, 0.U(64.W))
  }

  product := partials.reduce(_ ^ _)

  when(io.funct7 === F7_CLMUL) {
    switch(io.funct3) {
      is(F3_CLMUL)  {
        result := product(31, 0)
        valid  := true.B
      }
      is(F3_CLMULH) {
        result := product(63, 32)
        valid  := true.B
      }
      is(F3_CLMULR) {

        result := product(62, 31)
        valid  := true.B
      }
    }
  }

  io.out.result := result
  io.out.valid  := valid
}

class ZbsUnit extends Module {
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

  val shamt = io.rs2(4, 0)
  val mask  = 1.U(32.W) << shamt

  when(io.funct7 === F7_BCLR && io.funct3 === F3_BCLR) {
    result := io.rs1 & ~mask
    valid  := true.B
  }

  when(io.funct7 === F7_BEXT && io.funct3 === F3_BEXT) {
    result := (io.rs1 >> shamt)(0).asUInt
    valid  := true.B
  }

  when(io.funct7 === F7_BINV && io.funct3 === F3_BINV) {
    result := io.rs1 ^ mask
    valid  := true.B
  }

  when(io.funct7 === F7_BSET && io.funct3 === F3_BSET) {
    result := io.rs1 | mask
    valid  := true.B
  }

  io.out.result := result
  io.out.valid  := valid
}
