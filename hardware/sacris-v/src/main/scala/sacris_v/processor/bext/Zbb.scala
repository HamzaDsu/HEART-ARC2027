// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Zbb unit: count, rotate, byte and min/max operations.

package bext

import chisel3._
import chisel3.util._

class ZbbUnit extends Module {
  val io = IO(new Bundle {
    val rs1    = Input(UInt(32.W))
    val rs2    = Input(UInt(32.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val rs2sel = Input(UInt(5.W))
    val itype  = Input(Bool())
    val out    = Output(new BExtResult)
  })

  import BExtOpcodes._

  val result = Wire(UInt(32.W))
  val valid  = Wire(Bool())
  result := 0.U
  valid  := false.B

  def clz32(x: UInt): UInt = {
    val w = Wire(UInt(6.W))
    w := MuxCase(32.U(6.W), Seq(
      x(31)->0.U(6.W),  x(30)->1.U(6.W),  x(29)->2.U(6.W),  x(28)->3.U(6.W),
      x(27)->4.U(6.W),  x(26)->5.U(6.W),  x(25)->6.U(6.W),  x(24)->7.U(6.W),
      x(23)->8.U(6.W),  x(22)->9.U(6.W),  x(21)->10.U(6.W), x(20)->11.U(6.W),
      x(19)->12.U(6.W), x(18)->13.U(6.W), x(17)->14.U(6.W), x(16)->15.U(6.W),
      x(15)->16.U(6.W), x(14)->17.U(6.W), x(13)->18.U(6.W), x(12)->19.U(6.W),
      x(11)->20.U(6.W), x(10)->21.U(6.W), x(9) ->22.U(6.W), x(8) ->23.U(6.W),
      x(7) ->24.U(6.W), x(6) ->25.U(6.W), x(5) ->26.U(6.W), x(4) ->27.U(6.W),
      x(3) ->28.U(6.W), x(2) ->29.U(6.W), x(1) ->30.U(6.W), x(0) ->31.U(6.W),
    ))
    w
  }

  def ctz32(x: UInt): UInt = clz32(Reverse(x))

  def cpop32(x: UInt): UInt = {
    val s0 = Wire(Vec(32, UInt(6.W)))
    for (i <- 0 until 32) s0(i) := Cat(0.U(5.W), x(i))
    val s1 = Wire(Vec(16, UInt(6.W)))
    for (i <- 0 until 16) s1(i) := (s0(2*i) + s0(2*i+1))(5,0)
    val s2 = Wire(Vec(8, UInt(6.W)))
    for (i <- 0 until 8)  s2(i) := (s1(2*i) + s1(2*i+1))(5,0)
    val s3 = Wire(Vec(4, UInt(6.W)))
    for (i <- 0 until 4)  s3(i) := (s2(2*i) + s2(2*i+1))(5,0)
    val s4 = Wire(Vec(2, UInt(6.W)))
    for (i <- 0 until 2)  s4(i) := (s3(2*i) + s3(2*i+1))(5,0)
    val res = Wire(UInt(6.W))
    res := (s4(0) + s4(1))(5,0)
    res
  }

  def rol32(x: UInt, s: UInt): UInt = { val sh=s(4,0); ((x<<sh)|(x>>(32.U-sh)))(31,0) }
  def ror32(x: UInt, s: UInt): UInt = { val sh=s(4,0); ((x>>sh)|(x<<(32.U-sh)))(31,0) }

  def rev8_32(x: UInt): UInt = Cat(x(7,0), x(15,8), x(23,16), x(31,24))

  def orcb32(x: UInt): UInt = {
    val b = (0 until 4).map(i => Mux(x(8*i+7,8*i)=/=0.U, 0xFF.U(8.W), 0x00.U(8.W)))
    Cat(b(3),b(2),b(1),b(0))
  }

  when(io.funct7 === F7_ANDN_ORN_XNOR) {
    switch(io.funct3) {
      is("b111".U) { result := io.rs1 & ~io.rs2;   valid := true.B }
      is("b110".U) { result := io.rs1 | ~io.rs2;   valid := true.B }
      is("b100".U) { result := ~(io.rs1 ^ io.rs2); valid := true.B }
    }
  }

  when(io.funct7 === F7_CLZ_CTZ_CPOP && io.funct3 === "b001".U && io.itype) {
    switch(io.rs2sel) {
      is("b00000".U) {
        val r = Wire(UInt(32.W)); r := Cat(0.U(26.W), clz32(io.rs1))
        result := r; valid := true.B
      }
      is("b00001".U) {
        val r = Wire(UInt(32.W)); r := Cat(0.U(26.W), ctz32(io.rs1))
        result := r; valid := true.B
      }
      is("b00010".U) {
        val r = Wire(UInt(32.W)); r := Cat(0.U(26.W), cpop32(io.rs1))
        result := r; valid := true.B
      }
      is("b00100".U) {
        val sb = Wire(UInt(1.W)); sb := io.rs1(7).asUInt
        result := Cat(Fill(24, sb), io.rs1(7,0)); valid := true.B
      }
      is("b00101".U) {
        val sb = Wire(UInt(1.W)); sb := io.rs1(15).asUInt
        result := Cat(Fill(16, sb), io.rs1(15,0)); valid := true.B
      }
    }
  }

  when(io.funct7 === F7_ZEXT_H && io.funct3 === "b100".U && io.rs2sel === 0.U) {
    result := Cat(0.U(16.W), io.rs1(15,0)); valid := true.B
  }

  when(io.funct7 === F7_MAX) {
    val rs1s = io.rs1.asSInt; val rs2s = io.rs2.asSInt
    switch(io.funct3) {
      is("b100".U) { result := Mux(rs1s < rs2s,      io.rs1, io.rs2); valid := true.B }
      is("b101".U) { result := Mux(io.rs1 < io.rs2,  io.rs1, io.rs2); valid := true.B }
      is("b110".U) { result := Mux(rs1s >= rs2s,     io.rs1, io.rs2); valid := true.B }
      is("b111".U) { result := Mux(io.rs1 >= io.rs2, io.rs1, io.rs2); valid := true.B }
    }
  }

  when(io.funct7 === F7_ROT && !io.itype) {
    switch(io.funct3) {
      is("b001".U) { result := rol32(io.rs1, io.rs2); valid := true.B }
      is("b101".U) { result := ror32(io.rs1, io.rs2); valid := true.B }
    }
  }

  when(io.funct7 === F7_ROT && io.itype && io.funct3 === "b101".U) {
    result := ror32(io.rs1, io.rs2sel); valid := true.B
  }

  when(io.funct7 === F7_REV8_ORCB && io.funct3 === "b101".U
       && io.rs2sel === "b11000".U) {
    result := rev8_32(io.rs1); valid := true.B
  }

  when(io.funct7 === "b0010100".U && io.funct3 === "b101".U
       && io.rs2sel === "b00111".U) {
    result := orcb32(io.rs1); valid := true.B
  }

  io.out.result := result
  io.out.valid  := valid
}
