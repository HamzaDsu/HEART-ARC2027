// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// BootROM with a 64-bit fetch port for the dual-issue frontend.

package sacris_v.periphery

import chisel3._
import chisel3.util.{Cat, RegEnable, log2Ceil}
import sacris_v.common.BootRomPortIO64

class BootRom64(fileName: String, size: Int, registeredData: Boolean = false) extends Module {
  require(size % 2 == 0, s"BootRom64 requires even size (32-bit words); got $size")
  val io = IO(Flipped(new BootRomPortIO64()))

  val waitReq = if (registeredData) Some(IO(Output(Bool()))) else None

  val flush = if (registeredData) Some(IO(Input(Bool()))) else None

  val hexLines = scala.io.Source.fromFile(fileName).getLines().toSeq
  val initData = hexLines.map(line => java.lang.Long.parseLong(line.trim, 16).U(32.W))
  val padded32 = initData ++ Seq.fill(size - initData.length)(0.U(32.W))

  val packed64 = padded32.grouped(2).toSeq.map { pair =>
    Cat(pair(1), pair(0))
  }
  val rom = VecInit(packed64)

  val depth64 = size / 2
  val romAddrBits = log2Ceil(depth64)
  val rdAddr = io.addr(30, 3)

  if (registeredData) {
    val serving    = RegInit(false.B)
    when(io.en) { serving := !serving } .otherwise { serving := false.B }

    when(flush.get) { serving := false.B }
    val rdAddr_reg = RegEnable(rdAddr(romAddrBits - 1, 0), io.en && !serving)
    io.data       := RegNext(rom(rdAddr_reg))

    waitReq.get   := !serving
  } else {
    val rdAddr_reg = RegEnable(rdAddr(romAddrBits - 1, 0), io.en)
    io.data := rom(rdAddr_reg)
  }
}
