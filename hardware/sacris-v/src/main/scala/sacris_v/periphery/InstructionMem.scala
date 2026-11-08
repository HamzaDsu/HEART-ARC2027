// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Instruction memory with a 32-bit fetch port.

package sacris_v.periphery

import chisel3._
import chisel3.util.experimental.loadMemoryFromFileInline
import sacris_v.common.InstructionRamPortIO

class InstructionMem(size: Int, path: Option[String] = None, withEcc: Boolean = false,
                    openReadEnable: Boolean = false) extends Module {
  val io = IO(Flipped(new InstructionRamPortIO()))

  val eccCorr   = if (withEcc) Some(IO(Output(Bool()))) else None
  val eccUncorr = if (withEcc) Some(IO(Output(Bool()))) else None

  val memory = SyncReadMem(size, UInt(32.W))
  path.foreach{ p =>
    loadMemoryFromFileInline(memory, p)

  }

  if (!withEcc) {

  if (openReadEnable) {

    when(io.wr) {
      memory.write(io.waddr(30, 0), io.data_in)
    }
    io.data := memory.read(io.addr(30, 2), true.B)
  } else {

  io.data := DontCare
  when(io.wr) {
    memory.write(io.waddr(30, 0),io.data_in)
  }.otherwise{
    io.data := memory.read(io.addr(30, 2), io.en)
  }

  }

  } else {
    val checkMem = SyncReadMem(size, UInt(Secded.checkWidth(32).W))
    val rdQual = RegNext(io.en && !io.wr, false.B)

    when(io.wr) {
      memory.write(io.waddr(30, 0), io.data_in)
      checkMem.write(io.waddr(30, 0), Secded.encode(io.data_in, 32))
    }
    val d = memory.read(io.addr(30, 2), if (openReadEnable) true.B else io.en)
    val c = checkMem.read(io.addr(30, 2), if (openReadEnable) true.B else io.en)
    val (corrected, corr, uncorr) = Secded.decode(d, c, 32)
    io.data := corrected
    eccCorr.get   := corr && rdQual
    eccUncorr.get := uncorr && rdQual
  }
}
