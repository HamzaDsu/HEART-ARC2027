// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// SPI master with MMIO register interface.

package sacris_v.periphery

import chisel3._
import chisel3.util._

class SpiMaster(clkHz: Int, spiBaud: Int) extends Module {
  val io = IO(new Bundle {
    val sclk     = Output(Bool())
    val mosi     = Output(Bool())
    val miso     = Input(Bool())

    val tx_valid = Input(Bool())
    val tx_ready = Output(Bool())
    val tx_data  = Input(UInt(8.W))

    val rx_valid = Output(Bool())
    val rx_data  = Output(UInt(8.W))

    val busy     = Output(Bool())
  })

  val HALF_PERIOD = (clkHz / (2 * spiBaud)).max(1)
  val cntWidth    = log2Ceil(HALF_PERIOD + 1).max(1)

  val sIdle :: sLow :: sHigh :: Nil = Enum(3)
  val state   = RegInit(sIdle)
  val shiftReg = RegInit(0.U(8.W))
  val bitCnt   = RegInit(0.U(3.W))
  val clkCnt   = RegInit(0.U(cntWidth.W))
  val sclkReg  = RegInit(false.B)
  val mosiReg  = RegInit(false.B)

  val rxShiftReg  = RegInit(0.U(8.W))
  val rxValidReg  = RegInit(false.B)
  val misoSampled = RegInit(false.B)

  io.sclk     := sclkReg
  io.mosi     := mosiReg
  io.tx_ready := (state === sIdle)
  io.busy     := (state =/= sIdle)
  io.rx_valid := rxValidReg
  io.rx_data  := rxShiftReg

  rxValidReg := false.B

  switch(state) {
    is(sIdle) {
      sclkReg := false.B
      when(io.tx_valid) {
        shiftReg := io.tx_data
        bitCnt   := 7.U
        mosiReg  := io.tx_data(7)
        clkCnt   := (HALF_PERIOD - 1).U
        misoSampled := false.B
        state    := sLow
      }
    }

    is(sLow) {

      sclkReg := false.B
      misoSampled := false.B
      when(clkCnt === 0.U) {
        sclkReg := true.B
        clkCnt  := (HALF_PERIOD - 1).U
        state   := sHigh
      }.otherwise {
        clkCnt := clkCnt - 1.U
      }
    }

    is(sHigh) {

      sclkReg := true.B

      when(!misoSampled) {
        rxShiftReg  := rxShiftReg(6, 0) ## io.miso
        misoSampled := true.B
      }

      when(clkCnt === 0.U) {

        shiftReg := shiftReg << 1
        when(bitCnt === 0.U) {
          sclkReg    := false.B
          rxValidReg := true.B
          state      := sIdle
        }.otherwise {
          bitCnt  := bitCnt - 1.U
          mosiReg := shiftReg(6)
          clkCnt  := (HALF_PERIOD - 1).U
          sclkReg := false.B
          state   := sLow
        }
      }.otherwise {
        clkCnt := clkCnt - 1.U
      }
    }
  }
}
