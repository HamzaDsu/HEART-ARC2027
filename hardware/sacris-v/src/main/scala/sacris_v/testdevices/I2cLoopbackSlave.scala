// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Simulation-only I2C slave looped back to the I2C master.

package sacris_v.testdevices

import chisel3._
import chisel3.util._

class I2cLoopbackSlave(slaveAddr: Int = 0x50) extends Module {
  require(slaveAddr >= 0 && slaveAddr < 128, s"slaveAddr must be 7-bit, got $slaveAddr")

  val io = IO(new Bundle {
    val sda_in  = Input(Bool())
    val sda_out = Output(Bool())
    val sda_oe  = Output(Bool())
    val scl     = Input(Bool())

    val lastAddr    = Output(UInt(8.W))
    val regPointer  = Output(UInt(8.W))
    val matchedAddr = Output(Bool())
  })

  val sIdle :: sAddr :: sAddrAck :: sRegAddr :: sRegAddrAck :: sWriteData :: sWriteDataAck :: sReadData :: sReadAck :: Nil = Enum(9)
  val state = RegInit(sIdle)

  val sclPrev = RegNext(io.scl, true.B)
  val sdaPrev = RegNext(io.sda_in, true.B)
  val sclRise = io.scl && !sclPrev
  val sclFall = !io.scl && sclPrev

  val startCond = io.scl && sclPrev && !io.sda_in && sdaPrev
  val stopCond  = io.scl && sclPrev && io.sda_in && !sdaPrev

  val shiftReg   = RegInit(0.U(8.W))
  val bitCnt     = RegInit(0.U(4.W))
  val addrMatch  = RegInit(false.B)
  val isRead     = RegInit(false.B)
  val regPtr     = RegInit(0.U(8.W))
  val gotRegAddr = RegInit(false.B)
  val lastAddrR  = RegInit(0.U(8.W))
  val sdaOeReg   = RegInit(false.B)

  val regs = RegInit(VecInit((0 until 256).map(i => ((0x40 + i) & 0xFF).U(8.W))))

  io.sda_out  := false.B
  io.sda_oe   := sdaOeReg
  io.lastAddr    := lastAddrR
  io.regPointer  := regPtr
  io.matchedAddr := addrMatch

  when(stopCond) {
    state   := sIdle
    sdaOeReg := false.B
    gotRegAddr := false.B
  }

  when(startCond) {
    state    := sAddr
    bitCnt   := 0.U
    shiftReg := 0.U
    sdaOeReg := false.B
    addrMatch := false.B
  }

  when(!startCond && !stopCond) {
    switch(state) {
      is(sIdle) {
        sdaOeReg := false.B
      }

      is(sAddr) {

        when(sclRise) {
          shiftReg := shiftReg(6, 0) ## io.sda_in
          bitCnt   := bitCnt + 1.U
          when(bitCnt === 7.U) {
            val fullByte = shiftReg(6, 0) ## io.sda_in
            lastAddrR := fullByte
            val addr7 = fullByte(7, 1)
            isRead := fullByte(0)
            addrMatch := (addr7 === slaveAddr.U)
            state := sAddrAck
          }
        }
      }

      is(sAddrAck) {

        when(sclFall) {
          sdaOeReg := addrMatch
        }

        when(sclRise) {

        }

        when(sclFall && sdaOeReg) {

          sdaOeReg := false.B
          when(addrMatch) {
            when(isRead) {

              val firstByte = regs(regPtr)
              shiftReg := firstByte
              sdaOeReg := !firstByte(7)
              bitCnt   := 0.U
              state    := sReadData
            }.otherwise {
              when(gotRegAddr) {

                bitCnt := 0.U
                state  := sWriteData
              }.otherwise {

                bitCnt := 0.U
                state  := sRegAddr
              }
            }
          }.otherwise {
            state := sIdle
          }
        }
      }

      is(sRegAddr) {
        when(sclRise) {
          shiftReg := shiftReg(6, 0) ## io.sda_in
          bitCnt   := bitCnt + 1.U
          when(bitCnt === 7.U) {
            val regAddrByte = shiftReg(6, 0) ## io.sda_in
            regPtr     := regAddrByte
            gotRegAddr := true.B
            state      := sRegAddrAck
          }
        }
      }

      is(sRegAddrAck) {
        when(sclFall) {
          sdaOeReg := true.B
        }
        when(sclRise && sdaOeReg) {

        }
        when(sclFall && sdaOeReg) {
          sdaOeReg := false.B
          bitCnt   := 0.U
          state    := sWriteData
        }
      }

      is(sWriteData) {
        when(sclRise) {
          shiftReg := shiftReg(6, 0) ## io.sda_in
          bitCnt   := bitCnt + 1.U
          when(bitCnt === 7.U) {
            val dataByte = shiftReg(6, 0) ## io.sda_in
            regs(regPtr) := dataByte
            regPtr := regPtr + 1.U
            state  := sWriteDataAck
          }
        }
      }

      is(sWriteDataAck) {
        when(sclFall) {
          sdaOeReg := true.B
        }
        when(sclFall && sdaOeReg) {
          sdaOeReg := false.B
          bitCnt   := 0.U
          state    := sWriteData
        }
      }

      is(sReadData) {

        when(sclFall) {
          sdaOeReg := !shiftReg(7)
        }
        when(sclRise) {
          shiftReg := shiftReg << 1
          bitCnt   := bitCnt + 1.U
          when(bitCnt === 7.U) {
            state := sReadAck
          }
        }
      }

      is(sReadAck) {

        when(sclFall) {
          sdaOeReg := false.B
        }
        when(sclRise) {

          val masterAck = !io.sda_in
          when(masterAck) {

            regPtr   := regPtr + 1.U
            shiftReg := regs(regPtr + 1.U)
            bitCnt   := 0.U
            state    := sReadData
          }.otherwise {

            state := sIdle
          }
        }
      }
    }
  }
}
