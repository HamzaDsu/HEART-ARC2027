// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// BootROM initialised from the bootloader hex file; 1- or 2-cycle read.

package sacris_v.periphery

import chisel3._
import chisel3.util.{RegEnable, log2Ceil}
import sacris_v.common.BootRomPortIO

class BootRom(fileName: String, size: Int, registeredData: Boolean = false) extends Module {
  val io = IO(Flipped(new BootRomPortIO()))

  val waitReq = if (registeredData) Some(IO(Output(Bool()))) else None

  val flush = if (registeredData) Some(IO(Input(Bool()))) else None

  val hexLines = scala.io.Source.fromFile(fileName).getLines().toSeq
  val initData = hexLines.map(line => java.lang.Long.parseLong(line.trim, 16).U(32.W))
  val padded = initData ++ Seq.fill(size - initData.length)(0.U(32.W))
  val rom = VecInit(padded)

  val rdAddr = io.addr(30, 2)
  val romAddrBits = log2Ceil(size)

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
