// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// One DMA channel: descriptor registers, address generation, DREQ pacing.

package sacris_v.periphery

import chisel3._
import chisel3.util._
import sacris_v.AddressMap
import sacris_v.common.MemType

class DmaChannel extends Module {

  val io = IO(new Bundle {

    val regAddr  = Input(UInt(5.W))
    val regWdata = Input(UInt(32.W))
    val regWen   = Input(Bool())
    val regRen   = Input(Bool())
    val regRdata = Output(UInt(32.W))

    val arbWant   = Output(Bool())
    val arbGrant  = Input(Bool())
    val arbAddr   = Output(UInt(32.W))
    val arbWdata  = Output(UInt(32.W))
    val arbWmask  = Output(UInt(4.W))
    val arbWr     = Output(Bool())
    val arbRdata  = Input(UInt(32.W))

    val mmioWant  = Output(Bool())
    val mmioGrant = Input(Bool())
    val mmioAddr  = Output(UInt(32.W))
    val mmioWdata = Output(UInt(32.W))
    val mmioWmask = Output(UInt(4.W))
    val mmioWr    = Output(Bool())
    val mmioRdata = Input(UInt(32.W))

    val arbMemTyp = Output(MemType.Type())

    val irq = Output(Bool())

    val dreq = Input(Bool())
  })

  val sIdle :: sReadIssue :: sReadWait :: sWriteIssue :: sWriteWait :: sFinish :: sError :: sAbort :: Nil = Enum(8)
  val state = RegInit(sIdle)

  val srcAddr   = RegInit(0.U(32.W))
  val dstAddr   = RegInit(0.U(32.W))
  val xferCount = RegInit(0.U(32.W))

  val ctrlWidth  = RegInit(0.U(2.W))
  val ctrlSrcInc = RegInit(false.B)
  val ctrlDstInc = RegInit(false.B)
  val ctrlPaced  = RegInit(false.B)

  val busy  = RegInit(false.B)
  val done  = RegInit(false.B)
  val error = RegInit(false.B)

  val irqEnDone  = RegInit(false.B)
  val irqEnError = RegInit(false.B)
  val irqDonePending  = RegInit(false.B)
  val irqErrorPending = RegInit(false.B)

  val srcCurrent = RegInit(0.U(32.W))
  val dstCurrent = RegInit(0.U(32.W))
  val remaining  = RegInit(0.U(32.W))
  val readData   = RegInit(0.U(32.W))

  private val A = AddressMap

  def isDmaMemRange(addr: UInt): Bool = addr(31, 24) === (A.DMA_MEM_BASE >> 24).U
  def isMmioRange(addr: UInt): Bool   = addr >= A.MMIO_BASE.U && !isDmemRange(addr)
  def isDmemRange(addr: UInt): Bool   = addr < A.MMIO_BASE.U && !isDmaMemRange(addr)
  def isImemRange(addr: UInt): Bool   = addr(31).asBool

  def isForbidden(addr: UInt): Bool   = isDmemRange(addr) || isImemRange(addr)

  val unitBytes = MuxLookup(ctrlWidth, 1.U)(Seq(
    0.U -> 1.U,
    1.U -> 2.U,
    2.U -> 4.U
  ))

  def misaligned(addr: UInt): Bool = {
    MuxLookup(ctrlWidth, false.B)(Seq(
      0.U -> false.B,
      1.U -> addr(0).asBool,
      2.U -> (addr(1, 0) =/= 0.U)
    ))
  }

  def wmaskForAddr(addr: UInt): UInt = {
    val byteOff = addr(1, 0)
    MuxLookup(ctrlWidth, "b0001".U(4.W))(Seq(
      0.U -> (1.U(4.W) << byteOff),
      1.U -> Mux(byteOff(1), "b1100".U(4.W), "b0011".U(4.W)),
      2.U -> "b1111".U(4.W)
    ))
  }

  def positionWdata(data: UInt, addr: UInt): UInt = {
    val byteOff = addr(1, 0)
    MuxLookup(ctrlWidth, data(7, 0) << (byteOff ## 0.U(3.W)))(Seq(
      0.U -> (data(7, 0) << (byteOff ## 0.U(3.W))),
      1.U -> Mux(byteOff(1), Cat(data(15, 0), 0.U(16.W)), Cat(0.U(16.W), data(15, 0))),
      2.U -> data
    ))
  }

  def extractRdata(data: UInt, addr: UInt): UInt = {
    val byteOff = addr(1, 0)
    MuxLookup(ctrlWidth, 0.U(32.W))(Seq(
      0.U -> ((data >> (byteOff ## 0.U(3.W))) & 0xFF.U),
      1.U -> Mux(byteOff(1), (data >> 16.U) & 0xFFFF.U, data & 0xFFFF.U),
      2.U -> data
    ))
  }

  io.arbWant  := false.B
  io.arbAddr  := 0.U
  io.arbWdata := 0.U
  io.arbWmask := 0.U
  io.arbWr    := false.B

  io.mmioWant  := false.B
  io.mmioAddr  := 0.U
  io.mmioWdata := 0.U
  io.mmioWmask := 0.U
  io.mmioWr    := false.B

  io.arbMemTyp := MuxLookup(ctrlWidth, MemType.W)(Seq(
    0.U -> MemType.B,
    1.U -> MemType.H,
    2.U -> MemType.W
  ))

  io.irq := (irqDonePending && irqEnDone) || (irqErrorPending && irqEnError)

  val abortReq = RegInit(false.B)

  val readFromDmaMem = RegInit(false.B)

  switch(state) {
    is(sIdle) {

    }

    is(sReadIssue) {
      when(abortReq) {
        state := sAbort
      }.elsewhen(ctrlPaced && !io.dreq) {

      }.elsewhen(isForbidden(srcCurrent)) {
        state := sError
      }.elsewhen(misaligned(srcCurrent)) {
        state := sError
      }.elsewhen(isDmaMemRange(srcCurrent)) {

        io.arbWant  := true.B
        io.arbAddr  := srcCurrent
        io.arbWr    := false.B
        io.arbWmask := "b1111".U
        when(io.arbGrant) {
          readFromDmaMem := true.B
          state := sReadWait
        }
      }.elsewhen(isMmioRange(srcCurrent)) {

        io.mmioWant := true.B
        io.mmioAddr := srcCurrent
        io.mmioWr   := false.B
        when(io.mmioGrant) {
          readFromDmaMem := false.B
          state := sReadWait
        }
      }.otherwise {
        state := sError
      }
    }

    is(sReadWait) {
      when(readFromDmaMem) {
        readData := io.arbRdata
      }.otherwise {
        readData := extractRdata(io.mmioRdata, srcCurrent)
      }
      state := sWriteIssue
    }

    is(sWriteIssue) {
      when(abortReq) {
        state := sAbort
      }.elsewhen(isForbidden(dstCurrent)) {
        state := sError
      }.elsewhen(misaligned(dstCurrent)) {
        state := sError
      }.elsewhen(isDmaMemRange(dstCurrent)) {
        io.arbWant  := true.B
        io.arbAddr  := dstCurrent
        io.arbWdata := readData
        io.arbWmask := wmaskForAddr(dstCurrent)
        io.arbWr    := true.B
        when(io.arbGrant) {
          state := sWriteWait
        }
      }.elsewhen(isMmioRange(dstCurrent)) {
        io.mmioWant  := true.B
        io.mmioAddr  := dstCurrent
        io.mmioWdata := positionWdata(readData, dstCurrent)
        io.mmioWmask := wmaskForAddr(dstCurrent)
        io.mmioWr    := true.B
        when(io.mmioGrant) {
          state := sWriteWait
        }
      }.otherwise {
        state := sError
      }
    }

    is(sWriteWait) {
      when(ctrlSrcInc) { srcCurrent := srcCurrent + unitBytes }
      when(ctrlDstInc) { dstCurrent := dstCurrent + unitBytes }
      remaining := remaining - 1.U

      when(remaining === 1.U) {
        state := sFinish
      }.otherwise {
        state := sReadIssue
      }
    }

    is(sFinish) {
      busy := false.B
      done := true.B
      remaining := 0.U
      irqDonePending := true.B
      state := sIdle
    }

    is(sError) {
      busy  := false.B
      error := true.B
      irqErrorPending := true.B
      state := sIdle
    }

    is(sAbort) {
      busy := false.B
      abortReq := false.B
      state := sIdle
    }
  }

  val rdataReg = RegInit(0.U(32.W))

  when(io.regRen) {
    val offset = io.regAddr
    rdataReg := 0.U
    switch(offset) {
      is(0x00.U) { rdataReg := srcAddr }
      is(0x04.U) { rdataReg := dstAddr }
      is(0x08.U) {
        rdataReg := Mux(busy, remaining, Mux(remaining =/= 0.U, remaining, xferCount))
      }
      is(0x0C.U) {
        rdataReg := Cat(0.U(25.W), ctrlPaced, ctrlDstInc, ctrlSrcInc, ctrlWidth, 0.U(2.W))
      }
      is(0x10.U) {
        rdataReg := Cat(0.U(29.W), error, done, busy)
      }
      is(0x14.U) {
        rdataReg := Cat(0.U(30.W), irqEnError, irqEnDone)
      }
      is(0x18.U) {
        rdataReg := Cat(0.U(30.W), irqErrorPending, irqDonePending)
      }
    }
  }
  io.regRdata := rdataReg

  when(io.regWen) {
    val offset = io.regAddr
    switch(offset) {
      is(0x00.U) { when(!busy) { srcAddr := io.regWdata } }
      is(0x04.U) { when(!busy) { dstAddr := io.regWdata } }
      is(0x08.U) { when(!busy) { xferCount := io.regWdata } }
      is(0x0C.U) {
        when(!busy) {
          ctrlWidth  := io.regWdata(3, 2)
          ctrlSrcInc := io.regWdata(4).asBool
          ctrlDstInc := io.regWdata(5).asBool
          ctrlPaced  := io.regWdata(6).asBool
        }
        when(io.regWdata(0).asBool && !busy) {
          srcCurrent := srcAddr
          dstCurrent := dstAddr
          remaining  := xferCount
          busy       := true.B
          done       := false.B
          error      := false.B
          irqDonePending  := false.B
          irqErrorPending := false.B
          state      := sReadIssue
        }
        when(io.regWdata(1).asBool && busy) {
          abortReq := true.B
        }
      }
      is(0x14.U) {
        irqEnDone  := io.regWdata(0).asBool
        irqEnError := io.regWdata(1).asBool
      }
      is(0x18.U) {
        when(io.regWdata(0).asBool) { irqDonePending  := false.B }
        when(io.regWdata(1).asBool) { irqErrorPending := false.B }
      }
    }
  }
}
