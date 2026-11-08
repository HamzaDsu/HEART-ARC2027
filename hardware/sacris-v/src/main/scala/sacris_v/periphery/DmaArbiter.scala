// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Two-port arbiter in front of the DMA memory.

package sacris_v.periphery

import chisel3._
import chisel3.util._
import sacris_v.common.{MemCmd, MemReq, MemType}

class DmaArbiter(numPorts: Int = 2) extends Module {
  require(numPorts >= 1 && numPorts <= 4, "DmaArbiter supports 1..4 ports")

  val io = IO(new Bundle {

    val portReq = Input(Vec(numPorts, new MemReq(32)))
    val portEn  = Input(Vec(numPorts, Bool()))

    val grant = Output(Vec(numPorts, Bool()))
    val stall = Output(Vec(numPorts, Bool()))

    val memReq = Output(new MemReq(32))
    val memEn  = Output(Bool())
  })

  for (i <- 0 until numPorts) {
    io.grant(i) := false.B
    io.stall(i) := false.B
  }
  io.memReq := 0.U.asTypeOf(new MemReq(32))
  io.memEn  := false.B

  val rrPtr = RegInit(0.U(log2Ceil(numPorts).W))

  val selPort = Wire(UInt(log2Ceil(numPorts).W))
  val anyGrant = Wire(Bool())
  selPort  := 0.U
  anyGrant := false.B

  for (ptr <- 0 until numPorts) {
    when(rrPtr === ptr.U) {

      var taken = false.B
      for (offset <- 0 until numPorts) {
        val idx = (ptr + offset) % numPorts
        val canGrant = io.portEn(idx) && !taken
        when(canGrant) {
          selPort  := idx.U
          anyGrant := true.B
        }
        taken = taken || io.portEn(idx)
      }
    }
  }

  when(anyGrant) {
    io.memEn := true.B

    rrPtr := Mux(selPort === (numPorts - 1).U, 0.U, selPort +& 1.U)

    io.memReq := io.portReq(selPort)

    for (i <- 0 until numPorts) {
      io.grant(i) := selPort === i.U
      io.stall(i) := io.portEn(i) && selPort =/= i.U
    }
  }
}
