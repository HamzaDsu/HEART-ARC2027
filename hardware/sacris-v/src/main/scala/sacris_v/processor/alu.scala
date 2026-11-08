// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Integer ALU.

package sacris_v.processor

import chisel3._
import chisel3.util._
import sacris_v.processor.Constants._

class AluIo extends Bundle {

    val op1_data: UInt = Input(UInt(32.W))
    val op2_data: UInt = Input(UInt(32.W))
    val alu_func = Input(AluOp.Type())
    val out: UInt = Output(UInt(32.W))
}

class ALU extends Module {
  val io = IO(new AluIo())
  val alu_shamt     = io.op2_data(4,0).asUInt
  io.out := 0.U
  switch(io.alu_func) {
    is(AluOp.ADD) {io.out := (io.op1_data + io.op2_data).asUInt}
    is(AluOp.SUB) {io.out := (io.op1_data - io.op2_data).asUInt}
    is(AluOp.AND) {io.out := (io.op1_data & io.op2_data).asUInt}
    is(AluOp.OR) {io.out := (io.op1_data | io.op2_data).asUInt}
    is(AluOp.XOR) {io.out := (io.op1_data ^ io.op2_data).asUInt}
    is(AluOp.SLT) {io.out := (io.op1_data.asSInt < io.op2_data.asSInt).asUInt}
    is(AluOp.SLTU) {io.out := (io.op1_data < io.op2_data).asUInt}
    is(AluOp.SLL) {io.out := ((io.op1_data << alu_shamt)(32-1, 0)).asUInt}
    is(AluOp.SRA) {io.out := (io.op1_data.asSInt >> alu_shamt).asUInt}
    is(AluOp.SRL) {io.out := (io.op1_data >> alu_shamt).asUInt}
    is(AluOp.COPY_1) {io.out := io.op1_data}
    is(AluOp.COPY_2) {io.out := io.op2_data}

  }
}
