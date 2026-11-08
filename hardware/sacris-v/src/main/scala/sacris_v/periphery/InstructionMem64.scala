// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Instruction memory with a 64-bit fetch port for the dual-issue frontend.

package sacris_v.periphery

import chisel3._
import chisel3.util.Cat
import chisel3.util.experimental.loadMemoryFromFileInline
import sacris_v.common.InstructionRamPortIO64

class InstructionMem64(size: Int, path: Option[String] = None,
                      openReadEnable: Boolean = false) extends Module {
  require(size % 2 == 0, s"InstructionMem64 requires even size (32-bit words); got $size")
  val io = IO(Flipped(new InstructionRamPortIO64()))

  val depth64 = size / 2
  val memory = SyncReadMem(depth64, Vec(2, UInt(32.W)))
  path.foreach { p =>
    loadMemoryFromFileInline(memory, p)
  }

  io.data := DontCare
  if (openReadEnable) {

    when (io.wr) {
      val entryAddr = io.waddr(30, 1)
      val laneSel   = io.waddr(0)
      val mask      = VecInit(Seq(!laneSel, laneSel))
      val wdataVec  = VecInit(Seq.fill(2)(io.data_in))
      memory.write(entryAddr, wdataVec, mask)
    }
    val rdEntry = memory.read(io.addr(30, 3), true.B)
    io.data := Cat(rdEntry(1), rdEntry(0))
  } else {
  when (io.wr) {
    val entryAddr = io.waddr(30, 1)
    val laneSel   = io.waddr(0)
    val mask      = VecInit(Seq(!laneSel, laneSel))
    val wdataVec  = VecInit(Seq.fill(2)(io.data_in))
    memory.write(entryAddr, wdataVec, mask)
  } .otherwise {
    val rdEntry = memory.read(io.addr(30, 3), io.en)
    io.data := Cat(rdEntry(1), rdEntry(0))
  }
  }
}
