// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// I2C master with MMIO register interface.

package sacris_v.periphery

import chisel3._
import chisel3.util._

class I2cMaster(clkHz: Int, i2cBaud: Int) extends Module {
  val io = IO(new Bundle {

    val scl     = Output(Bool())
    val sda_out = Output(Bool())
    val sda_oe  = Output(Bool())
    val sda_in  = Input(Bool())

    val cmd_valid = Input(Bool())
    val cmd_ready = Output(Bool())
    val cmd_bits  = Input(UInt(11.W))

    val rx_valid = Output(Bool())
    val rx_bits  = Output(UInt(8.W))

    val busy = Output(Bool())
    val ack  = Output(Bool())
  })

  val HALF = (clkHz / (2 * i2cBaud)).max(2)
  val cW   = log2Ceil(HALF + 1).max(1)

  val CMD_NOP       = 0.U(3.W)
  val CMD_START     = 1.U(3.W)
  val CMD_STOP      = 2.U(3.W)
  val CMD_WRITE     = 3.U(3.W)
  val CMD_READ_ACK  = 4.U(3.W)
  val CMD_READ_NACK = 5.U(3.W)

  val states = Enum(15)
  val sIdle     = states(0)
  val sStart0   = states(1)
  val sStart1   = states(2)
  val sStart2   = states(3)
  val sStop0    = states(4)
  val sStop1    = states(5)
  val sStop2    = states(6)
  val sWrLow    = states(7)
  val sWrHigh   = states(8)
  val sAckLow   = states(9)
  val sAckHigh  = states(10)
  val sRdLow    = states(11)
  val sRdHigh   = states(12)
  val sTxAckLow = states(13)
  val sTxAckHigh = states(14)

  val state    = RegInit(sIdle)
  val divCnt   = RegInit(0.U(cW.W))
  val bitCnt   = RegInit(0.U(4.W))
  val shiftReg = RegInit(0.U(8.W))
  val rxShift  = RegInit(0.U(8.W))
  val sclReg   = RegInit(true.B)
  val sdaReg   = RegInit(false.B)
  val sdaOeReg = RegInit(false.B)
  val ackReg   = RegInit(false.B)
  val nackBit  = RegInit(false.B)
  val rxPulse  = RegInit(false.B)

  io.scl      := sclReg
  io.sda_out  := sdaReg
  io.sda_oe   := sdaOeReg
  io.cmd_ready := (state === sIdle)
  io.busy     := (state =/= sIdle)
  io.ack      := ackReg
  io.rx_valid := rxPulse
  io.rx_bits  := rxShift

  rxPulse := false.B

  val divDone = (divCnt === 0.U)
  when(!divDone) {
    divCnt := divCnt - 1.U
  }

  switch(state) {

    is(sIdle) {
      when(io.cmd_valid) {
        val cmd  = io.cmd_bits(2, 0)
        val data = io.cmd_bits(10, 3)
        switch(cmd) {
          is(CMD_START) {

            sdaOeReg := false.B
            sclReg   := true.B
            divCnt   := (HALF - 1).U
            state    := sStart0
          }
          is(CMD_STOP) {

            sdaOeReg := true.B
            sdaReg   := false.B
            sclReg   := false.B
            divCnt   := (HALF - 1).U
            state    := sStop0
          }
          is(CMD_WRITE) {
            shiftReg := data
            bitCnt   := 7.U
            sdaOeReg := true.B
            sdaReg   := Mux(data(7), false.B, false.B)
            sclReg   := false.B
            divCnt   := (HALF - 1).U
            state    := sWrLow
          }
          is(CMD_READ_ACK) {
            bitCnt   := 7.U
            nackBit  := false.B
            sdaOeReg := false.B
            sclReg   := false.B
            divCnt   := (HALF - 1).U
            state    := sRdLow
          }
          is(CMD_READ_NACK) {
            bitCnt   := 7.U
            nackBit  := true.B
            sdaOeReg := false.B
            sclReg   := false.B
            divCnt   := (HALF - 1).U
            state    := sRdLow
          }
        }
      }
    }

    is(sStart0) {

      sclReg   := true.B
      sdaOeReg := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sStart1
      }
    }
    is(sStart1) {

      sclReg   := true.B
      sdaOeReg := true.B
      sdaReg   := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sStart2
      }
    }
    is(sStart2) {

      sclReg   := false.B
      sdaOeReg := true.B
      sdaReg   := false.B
      when(divDone) {
        state := sIdle
      }
    }

    is(sStop0) {

      sclReg   := false.B
      sdaOeReg := true.B
      sdaReg   := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sStop1
      }
    }
    is(sStop1) {

      sclReg   := true.B
      sdaOeReg := true.B
      sdaReg   := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sStop2
      }
    }
    is(sStop2) {

      sclReg   := true.B
      sdaOeReg := false.B
      when(divDone) {
        state := sIdle
      }
    }

    is(sWrLow) {
      sclReg   := false.B
      sdaOeReg := true.B

      sdaOeReg := !shiftReg(7)
      sdaReg   := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sWrHigh
      }
    }
    is(sWrHigh) {
      sclReg   := true.B
      sdaOeReg := !shiftReg(7)
      sdaReg   := false.B
      when(divDone) {
        shiftReg := shiftReg << 1
        when(bitCnt === 0.U) {

          sclReg   := false.B
          sdaOeReg := false.B
          divCnt   := (HALF - 1).U
          state    := sAckLow
        }.otherwise {
          bitCnt := bitCnt - 1.U
          sclReg := false.B
          divCnt := (HALF - 1).U
          state  := sWrLow
        }
      }
    }

    is(sAckLow) {
      sclReg   := false.B
      sdaOeReg := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sAckHigh
      }
    }
    is(sAckHigh) {
      sclReg   := true.B
      sdaOeReg := false.B
      when(divDone) {
        ackReg := io.sda_in
        sclReg := false.B
        state  := sIdle
      }
    }

    is(sRdLow) {
      sclReg   := false.B
      sdaOeReg := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sRdHigh
      }
    }
    is(sRdHigh) {
      sclReg   := true.B
      sdaOeReg := false.B
      when(divDone) {
        rxShift := rxShift(6, 0) ## io.sda_in
        when(bitCnt === 0.U) {

          sclReg   := false.B
          sdaOeReg := !nackBit
          sdaReg   := false.B
          divCnt   := (HALF - 1).U
          state    := sTxAckLow
        }.otherwise {
          bitCnt := bitCnt - 1.U
          sclReg := false.B
          divCnt := (HALF - 1).U
          state  := sRdLow
        }
      }
    }

    is(sTxAckLow) {
      sclReg   := false.B
      sdaOeReg := !nackBit
      sdaReg   := false.B
      when(divDone) {
        divCnt := (HALF - 1).U
        state  := sTxAckHigh
      }
    }
    is(sTxAckHigh) {
      sclReg   := true.B
      sdaOeReg := !nackBit
      sdaReg   := false.B
      when(divDone) {
        rxPulse := true.B
        sclReg  := false.B
        state   := sIdle
      }
    }
  }
}
