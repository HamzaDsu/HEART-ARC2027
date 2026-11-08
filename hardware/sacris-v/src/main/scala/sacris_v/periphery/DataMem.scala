// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Data memory with byte enables, optional SECDED ECC and optional second (DMA) port.

package sacris_v.periphery

import sacris_v.TopParams
import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import sacris_v.common.{MemCmd, MemPortIO, MemType}

class UsesMem(memoryDepth: Int, path: Option[String] = None) extends Module {
  private val addrWidth = log2Ceil(memoryDepth)

  val io = IO(new Bundle {
    val address   = Input(UInt(addrWidth.W))
    val in_value  = Input(UInt(32.W))
    val wr        = Input(Bool())
    val wrMask    = Input(UInt(4.W))
    val out_value = Output(UInt(32.W))
  })

  val memory = SyncReadMem(memoryDepth, Vec(4, UInt(8.W)))
    path.foreach { p =>
    loadMemoryFromFileInline(memory, p)
  }

  val rdVec = memory.read(io.address)

  io.out_value := rdVec(3) ## rdVec(2) ## rdVec(1) ## rdVec(0)

  val wrVec = Wire(Vec(4, UInt(8.W)))
  for (i <- 0 until 4) {
    wrVec(i) := io.in_value(i * 8 + 7, i * 8)
  }

  val wrMaskVec = io.wrMask.asBools
  when(io.wr) {
    memory.write(io.address, wrVec, wrMaskVec)
  }
}

object Secded {
  def rFor(k: Int): Int = { var r = 0; while ((1 << r) < k + r + 1) r += 1; r }
  def checkWidth(k: Int): Int = rFor(k) + 1
  private def dataPositions(k: Int): Seq[Int] =
    (1 to k + rFor(k)).filter(p => (p & (p - 1)) != 0)

  def encode(d: UInt, k: Int): UInt = {
    val r = rFor(k)
    val pos = dataPositions(k)
    val par = (0 until r).map { j =>
      pos.zipWithIndex.collect { case (p, i) if (p & (1 << j)) != 0 => d(i) }
        .reduce(_ ^ _)
    }
    val overall = d.asBools.reduce(_ ^ _) ^ par.reduce(_ ^ _)
    Cat(overall +: par.reverse)
  }

  def decode(d: UInt, c: UInt, k: Int): (UInt, Bool, Bool) = {
    val r = rFor(k)
    val pos = dataPositions(k)
    val syn = Cat((0 until r).map { j =>
      c(j) ^ pos.zipWithIndex.collect { case (p, i) if (p & (1 << j)) != 0 => d(i) }
        .reduce(_ ^ _)
    }.reverse)
    val overall = d.asBools.reduce(_ ^ _) ^ c.asBools.reduce(_ ^ _)
    val flip = pos.zipWithIndex.map { case (p, i) =>
      Mux(syn === p.U, (BigInt(1) << i).U(k.W), 0.U(k.W))
    }.reduce(_ | _)
    (Mux(overall, d ^ flip, d), overall, !overall && syn =/= 0.U)
  }
}

class UsesMemDual(memoryDepth: Int, withEcc: Boolean) extends Module {
  private val addrWidth = log2Ceil(memoryDepth)

  val io = IO(new Bundle {
    val raddr  = Input(UInt(addrWidth.W))
    val rdata  = Output(Vec(4, UInt(8.W)))
    val waddr  = Input(UInt(addrWidth.W))
    val wdata  = Input(Vec(4, UInt(8.W)))
    val wr     = Input(Bool())
    val wrMask = Input(UInt(4.W))
    val corr   = if (withEcc) Some(Output(UInt(4.W))) else None
    val uncorr = if (withEcc) Some(Output(UInt(4.W))) else None
  })

  val memory = SyncReadMem(memoryDepth, Vec(4, UInt(8.W)))
  val rdVec = memory.read(io.raddr)
  when(io.wr) {
    memory.write(io.waddr, io.wdata, io.wrMask.asBools)
  }

  if (!withEcc) {
    io.rdata := rdVec
  } else {
    val checkMem = SyncReadMem(memoryDepth, Vec(4, UInt(5.W)))
    val ckWr = Wire(Vec(4, UInt(5.W)))
    for (i <- 0 until 4) ckWr(i) := Secded.encode(io.wdata(i), 8)
    when(io.wr) {
      checkMem.write(io.waddr, ckWr, io.wrMask.asBools)
    }
    val ckVec = checkMem.read(io.raddr)
    val corrV = Wire(Vec(4, Bool()))
    val uncV  = Wire(Vec(4, Bool()))
    for (i <- 0 until 4) {
      val (cd, co, un) = Secded.decode(rdVec(i), ckVec(i), 8)
      io.rdata(i) := cd
      corrV(i) := co
      uncV(i)  := un
    }
    io.corr.get   := corrV.asUInt
    io.uncorr.get := uncV.asUInt
  }
}

class DMem(size: Int, path: Option[String] = None, gateDepth: Int = 0, withEcc: Boolean = false,
           withRestoreFlush: Boolean = false) extends Module {
  val io = IO(Flipped(new MemPortIO(32)))

  val gateBlock = if (gateDepth > 0) Some(IO(Input(Bool()))) else None
  val gateDrop  = if (gateDepth > 0) Some(IO(Output(Bool()))) else None
  val eccCorr   = if (withEcc) Some(IO(Output(Bool()))) else None
  val eccUncorr = if (withEcc) Some(IO(Output(Bool()))) else None

  val bufFlush  = if (withRestoreFlush && gateDepth > 0) Some(IO(Input(Bool()))) else None

  if (gateDepth == 0 && !withEcc) {

  val memoryByteOutputDistributer = Module(new MemoryByteOutputDistributer)
  val memoryByteInputDistributer  = Module(new MemoryByteInputDistributer)
  val memory                      = Module(new UsesMem(size, path))

  val typeReg = RegInit(MemType.X)
  val addrReg = RegInit(0.U(2.W))
  val enReg   = RegInit(false.B)

  addrReg := io.req.addr(1, 0)
  typeReg := io.req.typ
  enReg   := io.en

  val addrMsb = log2Ceil(size) + 1
  memory.io.address := io.req.addr(addrMsb, 2)

  memory.io.wrMask := "b1111".U
  switch(io.req.typ) {
    is(MemType.B)  { memory.io.wrMask := UIntToOH(io.req.addr(1, 0)) }
    is(MemType.BU) { memory.io.wrMask := UIntToOH(io.req.addr(1, 0)) }

    is(MemType.H)  {
      when(io.req.addr(1) === 0.U) { memory.io.wrMask := "b0011".U }
        .otherwise                 { memory.io.wrMask := "b1100".U }
    }
    is(MemType.HU) {
      when(io.req.addr(1) === 0.U) { memory.io.wrMask := "b0011".U }
        .otherwise                 { memory.io.wrMask := "b1100".U }
    }

    is(MemType.W)  { memory.io.wrMask := "b1111".U }
    is(MemType.WU) { memory.io.wrMask := "b1111".U }

  }

  io.data := 0.U
  when(enReg) {
    io.data := memoryByteOutputDistributer.io.out_value4rd.asUInt
  }

  val isStore = io.en && (io.req.cmd === MemCmd.Write)
  memory.io.wr := isStore

  memoryByteInputDistributer.io.in_value(0) := io.req.data(7, 0)
  memoryByteInputDistributer.io.in_value(1) := io.req.data(15, 8)
  memoryByteInputDistributer.io.in_value(2) := io.req.data(23, 16)
  memoryByteInputDistributer.io.in_value(3) := io.req.data(31, 24)
  memoryByteInputDistributer.io.address     := io.req.addr(1, 0)
  memoryByteInputDistributer.io.opTyp       := io.req.typ

  memory.io.in_value := 0.U
  when(io.en) {
    memory.io.in_value := memoryByteInputDistributer.io.out_value.asUInt
  }

  memoryByteOutputDistributer.io.address := addrReg
  memoryByteOutputDistributer.io.opTyp   := typeReg
  memoryByteOutputDistributer.io.in_value(0) := memory.io.out_value(7, 0)
  memoryByteOutputDistributer.io.in_value(1) := memory.io.out_value(15, 8)
  memoryByteOutputDistributer.io.in_value(2) := memory.io.out_value(23, 16)
  memoryByteOutputDistributer.io.in_value(3) := memory.io.out_value(31, 24)

  } else {

    val typeReg = RegInit(MemType.X)
    val addrReg = RegInit(0.U(2.W))
    val enReg   = RegInit(false.B)
    val rdReg   = RegInit(false.B)
    addrReg := io.req.addr(1, 0)
    typeReg := io.req.typ
    enReg   := io.en
    rdReg   := io.en && (io.req.cmd === MemCmd.Read)

    val addrMsb = log2Ceil(size) + 1
    val wordW   = addrMsb - 1
    val memory  = Module(new UsesMemDual(size, withEcc))
    memory.io.raddr := io.req.addr(addrMsb, 2)

    val inDist = Module(new MemoryByteInputDistributer)
    inDist.io.in_value(0) := io.req.data(7, 0)
    inDist.io.in_value(1) := io.req.data(15, 8)
    inDist.io.in_value(2) := io.req.data(23, 16)
    inDist.io.in_value(3) := io.req.data(31, 24)
    inDist.io.address := io.req.addr(1, 0)
    inDist.io.opTyp   := io.req.typ

    val wrMask = WireDefault("b1111".U(4.W))
    switch(io.req.typ) {
      is(MemType.B)  { wrMask := UIntToOH(io.req.addr(1, 0)) }
      is(MemType.BU) { wrMask := UIntToOH(io.req.addr(1, 0)) }
      is(MemType.H)  { wrMask := Mux(io.req.addr(1) === 0.U, "b0011".U, "b1100".U) }
      is(MemType.HU) { wrMask := Mux(io.req.addr(1) === 0.U, "b0011".U, "b1100".U) }
      is(MemType.W)  { wrMask := "b1111".U }
      is(MemType.WU) { wrMask := "b1111".U }
    }

    val isStore = io.en && (io.req.cmd === MemCmd.Write)
    val stWord  = io.req.addr(addrMsb, 2)

    val fwdMaskC = Wire(Vec(4, Bool()))
    val fwdDataC = Wire(Vec(4, UInt(8.W)))
    fwdMaskC := VecInit(Seq.fill(4)(false.B))
    fwdDataC := VecInit(Seq.fill(4)(0.U(8.W)))

    if (gateDepth == 0) {

      memory.io.wr     := isStore
      memory.io.waddr  := stWord
      memory.io.wdata  := inDist.io.out_value
      memory.io.wrMask := wrMask
    } else {
      val bufVld  = RegInit(VecInit(Seq.fill(gateDepth)(false.B)))
      val bufAddr = Reg(Vec(gateDepth, UInt(wordW.W)))
      val bufData = Reg(Vec(gateDepth, Vec(4, UInt(8.W))))
      val bufMask = Reg(Vec(gateDepth, UInt(4.W)))
      bufVld(0)  := isStore
      bufAddr(0) := stWord
      bufData(0) := inDist.io.out_value
      bufMask(0) := wrMask
      for (i <- 1 until gateDepth) {
        bufVld(i)  := bufVld(i - 1)
        bufAddr(i) := bufAddr(i - 1)
        bufData(i) := bufData(i - 1)
        bufMask(i) := bufMask(i - 1)
      }

      bufFlush.foreach { fl =>
        when(fl) { for (i <- 0 until gateDepth) bufVld(i) := false.B }
      }

      val relVld = bufVld(gateDepth - 1)
      val commit = relVld && !gateBlock.get
      memory.io.wr     := commit
      memory.io.waddr  := bufAddr(gateDepth - 1)
      memory.io.wdata  := bufData(gateDepth - 1)
      memory.io.wrMask := bufMask(gateDepth - 1)
      gateDrop.get := relVld && gateBlock.get

      val sg_observe_commit       = WireInit(commit)
      val sg_observe_commit_addr  = WireInit(bufAddr(gateDepth - 1))
      val sg_observe_commit_data  = WireInit(bufData(gateDepth - 1).asUInt)
      val sg_observe_commit_mask  = WireInit(bufMask(gateDepth - 1))
      dontTouch(sg_observe_commit)
      dontTouch(sg_observe_commit_addr)
      dontTouch(sg_observe_commit_data)
      dontTouch(sg_observe_commit_mask)

      for (i <- gateDepth - 1 to 0 by -1) {
        when(bufVld(i) && bufAddr(i) === memory.io.raddr) {
          for (b <- 0 until 4) {
            when(bufMask(i)(b)) {
              fwdMaskC(b) := true.B
              fwdDataC(b) := bufData(i)(b)
            }
          }
        }
      }
    }

    val rdNow = io.en && (io.req.cmd === MemCmd.Read)
    val fwdMaskR = RegNext(Mux(rdNow, fwdMaskC.asUInt, 0.U(4.W)), 0.U(4.W))
    val fwdDataR = RegNext(fwdDataC)

    val mergedByte = Wire(Vec(4, UInt(8.W)))
    for (b <- 0 until 4) {
      mergedByte(b) := Mux(fwdMaskR(b), fwdDataR(b), memory.io.rdata(b))
    }

    val outDist = Module(new MemoryByteOutputDistributer)
    outDist.io.address  := addrReg
    outDist.io.opTyp    := typeReg
    outDist.io.in_value := mergedByte

    io.data := 0.U
    when(enReg) {
      io.data := outDist.io.out_value4rd.asUInt
    }

    if (withEcc) {

      val live = Mux(rdReg, ~fwdMaskR, 0.U(4.W))
      eccCorr.get   := (memory.io.corr.get   & live).orR
      eccUncorr.get := (memory.io.uncorr.get & live).orR
    }
  }
}

class MemoryByteInputDistributer extends Module {
  val io = IO(new Bundle {
    val in_value  = Input(Vec(4, UInt(8.W)))
    val address   = Input(UInt(2.W))
    val opTyp     = Input(MemType.Type())
    val out_value = Output(Vec(4, UInt(8.W)))
  })

  io.out_value := VecInit(Seq.fill(4)(0.U(8.W)))

  switch(io.opTyp) {
    is(MemType.B)  { io.out_value(io.address) := io.in_value(0) }
    is(MemType.BU) { io.out_value(io.address) := io.in_value(0) }

    is(MemType.H)  {
      io.out_value(io.address)       := io.in_value(0)
      io.out_value(io.address + 1.U) := io.in_value(1)
    }
    is(MemType.HU) {
      io.out_value(io.address)       := io.in_value(0)
      io.out_value(io.address + 1.U) := io.in_value(1)
    }

    is(MemType.W)  { io.out_value := io.in_value }
    is(MemType.WU) { io.out_value := io.in_value }
  }
}

class MemoryByteOutputDistributer extends Module {
  val io = IO(new Bundle {
    val in_value    = Input(Vec(4, UInt(8.W)))
    val address     = Input(UInt(2.W))
    val opTyp       = Input(MemType.Type())
    val out_value4rd = Output(Vec(4, UInt(8.W)))
  })

  io.out_value4rd := VecInit(Seq.fill(4)(0.U(8.W)))

  switch(io.opTyp) {
    is(MemType.B)  { io.out_value4rd(0) := io.in_value(io.address) }
    is(MemType.BU) { io.out_value4rd(0) := io.in_value(io.address) }

    is(MemType.H)  {
      when(io.address(1) === 0.U) {
        io.out_value4rd(0) := io.in_value(0)
        io.out_value4rd(1) := io.in_value(1)
      }.otherwise {
        io.out_value4rd(0) := io.in_value(2)
        io.out_value4rd(1) := io.in_value(3)
      }
    }
    is(MemType.HU) {
      when(io.address(1) === 0.U) {
        io.out_value4rd(0) := io.in_value(0)
        io.out_value4rd(1) := io.in_value(1)
      }.otherwise {
        io.out_value4rd(0) := io.in_value(2)
        io.out_value4rd(1) := io.in_value(3)
      }
    }

    is(MemType.W)  { io.out_value4rd := io.in_value }
    is(MemType.WU) { io.out_value4rd := io.in_value }
  }
}
