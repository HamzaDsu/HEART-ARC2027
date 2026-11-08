// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// DMA controller: channel bank, MMIO register file, memory and MMIO masters.

package sacris_v.periphery

import chisel3._
import chisel3.util._
import sacris_v.common.MemType

class DmaController(dmaMemAddrWidth: Int, numChannels: Int = 1) extends Module {
  require(numChannels >= 1 && numChannels <= 4,
    s"dmaChannels must be in 1..4, got $numChannels")

  private val regAddrWidth = if (numChannels == 1) 5 else log2Ceil(numChannels * 256)

  val io = IO(new Bundle {

    val regAddr  = Input(UInt(regAddrWidth.W))
    val regWdata = Input(UInt(32.W))
    val regWen   = Input(Bool())
    val regRen   = Input(Bool())
    val regRdata = Output(UInt(32.W))

    val dmaMemValid = Output(Bool())
    val dmaMemAddr  = Output(UInt(32.W))
    val dmaMemWdata = Output(UInt(32.W))
    val dmaMemWmask = Output(UInt(4.W))
    val dmaMemWr    = Output(Bool())
    val dmaMemStall = Input(Bool())
    val dmaMemRdata = Input(UInt(32.W))

    val mmioValid = Output(Bool())
    val mmioAddr  = Output(UInt(32.W))
    val mmioWdata = Output(UInt(32.W))
    val mmioWmask = Output(UInt(4.W))
    val mmioWr    = Output(Bool())
    val mmioRdata = Input(UInt(32.W))
    val mmioReady = Input(Bool())

    val dmaMemTyp = Output(MemType.Type())

    val irq = Output(Vec(numChannels, Bool()))

    val dreq = Input(Vec(numChannels, Bool()))
  })

  val channels = Seq.fill(numChannels)(Module(new DmaChannel()))

  for (i <- 0 until numChannels) {
    channels(i).io.dreq := io.dreq(i)
    io.irq(i) := channels(i).io.irq
  }

  if (numChannels == 1) {

    channels(0).io.regAddr  := io.regAddr
    channels(0).io.regWdata := io.regWdata
    channels(0).io.regWen   := io.regWen
    channels(0).io.regRen   := io.regRen
    io.regRdata := channels(0).io.regRdata
  } else {

    val chSel  = io.regAddr(regAddrWidth - 1, 8)
    val regOff = io.regAddr(4, 0)

    for (ch <- channels) {
      ch.io.regAddr  := regOff
      ch.io.regWdata := io.regWdata
      ch.io.regWen   := false.B
      ch.io.regRen   := false.B
    }

    for (i <- 0 until numChannels) {
      when(chSel === i.U) {
        channels(i).io.regWen := io.regWen
        channels(i).io.regRen := io.regRen
      }
    }

    val chSelReg = RegNext(chSel, 0.U)
    io.regRdata := channels(0).io.regRdata
    for (i <- 0 until numChannels) {
      when(chSelReg === i.U) {
        io.regRdata := channels(i).io.regRdata
      }
    }
  }

  val arbRrPtr = RegInit(0.U(log2Ceil(numChannels).max(1).W))

  io.dmaMemValid := false.B
  io.dmaMemAddr  := 0.U
  io.dmaMemWdata := 0.U
  io.dmaMemWmask := 0.U
  io.dmaMemWr    := false.B
  io.dmaMemTyp   := MemType.W

  for (ch <- channels) {
    ch.io.arbGrant := false.B
    ch.io.arbRdata := io.dmaMemRdata
  }

  if (numChannels == 1) {

    val ch = channels(0)
    io.dmaMemValid := ch.io.arbWant
    io.dmaMemAddr  := ch.io.arbAddr
    io.dmaMemWdata := ch.io.arbWdata
    io.dmaMemWmask := ch.io.arbWmask
    io.dmaMemWr    := ch.io.arbWr
    io.dmaMemTyp   := ch.io.arbMemTyp
    ch.io.arbGrant := ch.io.arbWant && !io.dmaMemStall
  } else {

    val arbSel    = Wire(UInt(log2Ceil(numChannels).W))
    val arbAnyReq = Wire(Bool())
    arbSel    := 0.U
    arbAnyReq := false.B

    for (ptr <- 0 until numChannels) {
      when(arbRrPtr === ptr.U) {
        var taken = false.B
        for (offset <- 0 until numChannels) {
          val idx = (ptr + offset) % numChannels
          val canGrant = channels(idx).io.arbWant && !taken
          when(canGrant) {
            arbSel    := idx.U
            arbAnyReq := true.B
          }
          taken = taken || channels(idx).io.arbWant
        }
      }
    }

    when(arbAnyReq) {
      io.dmaMemValid := true.B

      for (i <- 0 until numChannels) {
        when(arbSel === i.U) {
          io.dmaMemAddr  := channels(i).io.arbAddr
          io.dmaMemWdata := channels(i).io.arbWdata
          io.dmaMemWmask := channels(i).io.arbWmask
          io.dmaMemWr    := channels(i).io.arbWr
          io.dmaMemTyp   := channels(i).io.arbMemTyp
        }
      }

      when(!io.dmaMemStall) {
        arbRrPtr := Mux(arbSel === (numChannels - 1).U, 0.U, arbSel +& 1.U)
        for (i <- 0 until numChannels) {
          when(arbSel === i.U) {
            channels(i).io.arbGrant := true.B
          }
        }
      }
    }
  }

  val mmioRrPtr = RegInit(0.U(log2Ceil(numChannels).max(1).W))

  io.mmioValid := false.B
  io.mmioAddr  := 0.U
  io.mmioWdata := 0.U
  io.mmioWmask := 0.U
  io.mmioWr    := false.B

  for (ch <- channels) {
    ch.io.mmioGrant := false.B
    ch.io.mmioRdata := io.mmioRdata
  }

  if (numChannels == 1) {

    val ch = channels(0)
    io.mmioValid := ch.io.mmioWant
    io.mmioAddr  := ch.io.mmioAddr
    io.mmioWdata := ch.io.mmioWdata
    io.mmioWmask := ch.io.mmioWmask
    io.mmioWr    := ch.io.mmioWr
    ch.io.mmioGrant := ch.io.mmioWant && io.mmioReady
  } else {

    val mmioSel    = Wire(UInt(log2Ceil(numChannels).W))
    val mmioAnyReq = Wire(Bool())
    mmioSel    := 0.U
    mmioAnyReq := false.B

    for (ptr <- 0 until numChannels) {
      when(mmioRrPtr === ptr.U) {
        var taken = false.B
        for (offset <- 0 until numChannels) {
          val idx = (ptr + offset) % numChannels
          val canGrant = channels(idx).io.mmioWant && !taken
          when(canGrant) {
            mmioSel    := idx.U
            mmioAnyReq := true.B
          }
          taken = taken || channels(idx).io.mmioWant
        }
      }
    }

    when(mmioAnyReq) {
      io.mmioValid := true.B

      for (i <- 0 until numChannels) {
        when(mmioSel === i.U) {
          io.mmioAddr  := channels(i).io.mmioAddr
          io.mmioWdata := channels(i).io.mmioWdata
          io.mmioWmask := channels(i).io.mmioWmask
          io.mmioWr    := channels(i).io.mmioWr
        }
      }

      when(io.mmioReady) {
        mmioRrPtr := Mux(mmioSel === (numChannels - 1).U, 0.U, mmioSel +& 1.U)
        for (i <- 0 until numChannels) {
          when(mmioSel === i.U) {
            channels(i).io.mmioGrant := true.B
          }
        }
      }
    }
  }
}
