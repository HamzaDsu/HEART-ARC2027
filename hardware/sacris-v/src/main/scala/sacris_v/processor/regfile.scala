// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Integer register file (2R/1W) with optional scrub and ECC ports for safety builds.

package sacris_v.processor

import chisel3._
import sacris_v.periphery.Secded

class RFileIo(val withParallelRestore: Boolean = false, val withScrubScan: Boolean = false, val withEcc: Boolean = false) extends Bundle()
{
   val rs1_addr = Input(UInt(5.W))
   val rs1_data = Output(UInt(32.W))
   val rs2_addr = Input(UInt(5.W))
   val rs2_data = Output(UInt(32.W))

   val waddr    = Input(UInt(5.W))
   val wdata    = Input(UInt(32.W))
   val wen      = Input(Bool())

   val parEn    = if (withParallelRestore) Some(Input(Bool()))               else None
   val parData  = if (withParallelRestore) Some(Input(Vec(32, UInt(32.W))))  else None

   val scrubAddr = if (withScrubScan) Some(Input(UInt(5.W)))   else None
   val scrubData = if (withScrubScan) Some(Output(UInt(32.W))) else None

   val eccCorrectable   = if (withEcc) Some(Output(Bool())) else None
   val eccUncorrectable = if (withEcc) Some(Output(Bool())) else None
}

class RegisterFile(val withParallelRestore: Boolean = false, val withScrubScan: Boolean = false, val withEcc: Boolean = false) extends Module
{
   val io = IO(new RFileIo(withParallelRestore, withScrubScan, withEcc))
   val regfile = Mem(32, UInt(32.W))

   if (withScrubScan) { io.scrubData.get := regfile(io.scrubAddr.get) }
   when (io.wen && (io.waddr =/= 0.U))
   {
      regfile(io.waddr) := io.wdata
   }

   if (withParallelRestore) {
      when (io.parEn.get) {
         for (i <- 1 until 32) { regfile(i.U) := io.parData.get(i) }
      }
   }

   val wb_hit_rs1 = io.wen && (io.waddr =/= 0.U) && (io.waddr === io.rs1_addr)
   val wb_hit_rs2 = io.wen && (io.waddr =/= 0.U) && (io.waddr === io.rs2_addr)

   io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U,
                    Mux(wb_hit_rs1, io.wdata, regfile(io.rs1_addr)))
   io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U,
                    Mux(wb_hit_rs2, io.wdata, regfile(io.rs2_addr)))

   if (withEcc) {
      val checkMem = Mem(32, UInt(Secded.checkWidth(32).W))

      when (io.wen && (io.waddr =/= 0.U))
      {
         checkMem(io.waddr) := Secded.encode(io.wdata, 32)
      }

      if (withParallelRestore) {
         when (io.parEn.get) {
            for (i <- 1 until 32) { checkMem(i.U) := Secded.encode(io.parData.get(i), 32) }
         }
      }

      val (rs1Corrected, rs1SingleBit, rs1DoubleBit) =
         Secded.decode(regfile(io.rs1_addr), checkMem(io.rs1_addr), 32)
      val (rs2Corrected, rs2SingleBit, rs2DoubleBit) =
         Secded.decode(regfile(io.rs2_addr), checkMem(io.rs2_addr), 32)

      io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U,
                       Mux(wb_hit_rs1, io.wdata, rs1Corrected))
      io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U,
                       Mux(wb_hit_rs2, io.wdata, rs2Corrected))

      val rs1FromMem = (io.rs1_addr =/= 0.U) && !wb_hit_rs1
      val rs2FromMem = (io.rs2_addr =/= 0.U) && !wb_hit_rs2
      io.eccCorrectable.get   := (rs1FromMem && rs1SingleBit) || (rs2FromMem && rs2SingleBit)
      io.eccUncorrectable.get := (rs1FromMem && rs1DoubleBit) || (rs2FromMem && rs2DoubleBit)

      val restoreBusy = if (withParallelRestore) io.parEn.get else false.B
      when (rs1FromMem && rs1SingleBit && !restoreBusy) {
         regfile(io.rs1_addr)  := rs1Corrected
         checkMem(io.rs1_addr) := Secded.encode(rs1Corrected, 32)
      }
      when (rs2FromMem && rs2SingleBit && !restoreBusy) {
         regfile(io.rs2_addr)  := rs2Corrected
         checkMem(io.rs2_addr) := Secded.encode(rs2Corrected, 32)
      }
   }
}
