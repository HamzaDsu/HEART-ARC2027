// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Memory request/response bundles and port interfaces shared by core and memories.

package sacris_v.common

import chisel3._
import chisel3.util._

object MemCmd extends ChiselEnum {
   val Read, Write = Value
}

object MemType extends ChiselEnum {
   val X, B, H, W, D, BU, HU, WU = Value
}

object MemPortId {
   final val DPORT = 0
   final val IPORT = 1
}

class BootRomPortIO extends Bundle {
   val addr = Output(UInt(32.W))
   val en   = Output(Bool())
   val data = Input(UInt(32.W))
}

class InstructionRamPortIO extends Bundle {
   val addr = Output(UInt(32.W))
   val en   = Output(Bool())
   val data = Input(UInt(32.W))

   val wr   = Output(Bool())
   val waddr = Output(UInt(32.W))
   val data_in = Output(UInt(32.W))
}

class BootRomPortIO64 extends Bundle {
   val addr = Output(UInt(32.W))
   val en   = Output(Bool())
   val data = Input(UInt(64.W))
}

class InstructionRamPortIO64 extends Bundle {
   val addr = Output(UInt(32.W))
   val en   = Output(Bool())
   val data = Input(UInt(64.W))

   val wr   = Output(Bool())
   val waddr = Output(UInt(32.W))
   val data_in = Output(UInt(32.W))
}

class MemReq(dataWidth: Int) extends Bundle {
   val addr = UInt(32.W)
   val data = UInt(dataWidth.W)
   val cmd  = MemCmd.Type()
   val typ  = MemType.Type()

   def tlSize: UInt = {
      val sz = WireDefault(0.U(2.W))
      switch(typ) {
         is(MemType.B)  { sz := 0.U }
         is(MemType.BU) { sz := 0.U }
         is(MemType.H)  { sz := 1.U }
         is(MemType.HU) { sz := 1.U }
         is(MemType.W)  { sz := 2.U }
         is(MemType.WU) { sz := 2.U }
         is(MemType.D)  { sz := 3.U }
      }
      sz
   }
}

class MemPortIO(dataWidth: Int) extends Bundle {
   val req  = Output(new MemReq(dataWidth))
   val en   = Output(Bool())
   val data = Input(UInt(dataWidth.W))
}
