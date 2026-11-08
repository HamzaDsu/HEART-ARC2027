// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Simulation-only SPI slave looped back to the SPI master.

package sacris_v.testdevices

import chisel3._
import chisel3.util._

class SpiLoopbackSlave extends Module {

  val io = IO(new Bundle {
    val cs   = Input(Bool())
    val sclk = Input(Bool())
    val mosi = Input(Bool())
    val miso = Output(Bool())

    val lastRx = Output(UInt(8.W))
  })

  val sclkPrev = RegNext(io.sclk, false.B)
  val sclkRise = io.sclk && !sclkPrev
  val sclkFall = !io.sclk && sclkPrev

  val rxShift = RegInit(0.U(8.W))
  val txShift = RegInit(0.U(8.W))
  val bitCnt  = RegInit(0.U(3.W))
  val lastRx  = RegInit(0.U(8.W))

  io.lastRx := lastRx

  val csPrev = RegNext(io.cs, true.B)

  when(io.cs) {

    rxShift := 0.U
    bitCnt  := 0.U
    io.miso := false.B
  }.otherwise {

    io.miso := txShift(7)

    when(csPrev && !io.cs) {
      bitCnt := 0.U
    }

    when(sclkRise) {
      rxShift := rxShift(6, 0) ## io.mosi
      bitCnt  := bitCnt + 1.U

      when(bitCnt === 7.U) {

        val completeByte = rxShift(6, 0) ## io.mosi
        lastRx  := completeByte
        txShift := completeByte
        bitCnt  := 0.U
      }
    }

    when(sclkFall && bitCnt =/= 0.U) {
      txShift := txShift << 1
    }
  }
}
