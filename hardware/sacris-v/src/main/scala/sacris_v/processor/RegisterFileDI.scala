// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Integer register file with 4 read and 2 write ports for dual issue.

package sacris_v.processor

import chisel3._

class RegisterFileDI_IO extends Bundle {
   val rsA1_addr = Input(UInt(5.W))
   val rsA1_data = Output(UInt(32.W))
   val rsA2_addr = Input(UInt(5.W))
   val rsA2_data = Output(UInt(32.W))
   val rsB1_addr = Input(UInt(5.W))
   val rsB1_data = Output(UInt(32.W))
   val rsB2_addr = Input(UInt(5.W))
   val rsB2_data = Output(UInt(32.W))

   val wp0_waddr = Input(UInt(5.W))
   val wp0_wdata = Input(UInt(32.W))
   val wp0_wen   = Input(Bool())

   val wp1_waddr = Input(UInt(5.W))
   val wp1_wdata = Input(UInt(32.W))
   val wp1_wen   = Input(Bool())
}

class RegisterFileDI extends Module {
   val io = IO(new RegisterFileDI_IO)

   val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

   when (io.wp1_wen && io.wp1_waddr =/= 0.U) {
      regs(io.wp1_waddr) := io.wp1_wdata
   }
   when (io.wp0_wen && io.wp0_waddr =/= 0.U &&
         !(io.wp1_wen && io.wp1_waddr === io.wp0_waddr)) {
      regs(io.wp0_waddr) := io.wp0_wdata
   }

   private def readPort(addr: UInt): UInt = {
      val wp1_hit = io.wp1_wen && io.wp1_waddr =/= 0.U && io.wp1_waddr === addr
      val wp0_hit = io.wp0_wen && io.wp0_waddr =/= 0.U && io.wp0_waddr === addr
      Mux(addr === 0.U, 0.U,
         Mux(wp1_hit, io.wp1_wdata,
            Mux(wp0_hit, io.wp0_wdata,
               regs(addr))))
   }

   io.rsA1_data := readPort(io.rsA1_addr)
   io.rsA2_data := readPort(io.rsA2_addr)
   io.rsB1_data := readPort(io.rsB1_addr)
   io.rsB2_data := readPort(io.rsB2_addr)
}
