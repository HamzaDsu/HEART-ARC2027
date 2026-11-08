// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Optional branch-event counters for simulation (withBranchObserve).

package sacris_v.processor

import chisel3._

class BranchObserveEvents extends Bundle {
   val cbranch_redirect   = Bool()
   val jalr_mispredict      = Bool()
   val jalr_id_suppressed   = Bool()
   val branches_total       = Bool()
   val jalr_redirect_total  = Bool()
   val cbranches_total      = Bool()
}

class BranchObserveCounters extends Bundle {
   val cbranch_redirect   = UInt(64.W)
   val jalr_mispredict      = UInt(64.W)
   val jalr_id_suppressed   = UInt(64.W)
   val branches_total       = UInt(64.W)
   val jalr_redirect_total  = UInt(64.W)
   val cbranches_total      = UInt(64.W)
}

class BranchObserve extends Module {
   val io = IO(new Bundle {
      val events   = Input(new BranchObserveEvents)
      val counters = Output(new BranchObserveCounters)
   })

   private def satInc(c: UInt, ev: Bool): UInt = {
      val maxed = c.andR
      Mux(ev && !maxed, c + 1.U, c)
   }

   val cnt_cbranch_redirect  = RegInit(0.U(64.W))
   val cnt_jalr_mispredict     = RegInit(0.U(64.W))
   val cnt_jalr_id_suppressed  = RegInit(0.U(64.W))
   val cnt_branches_total      = RegInit(0.U(64.W))
   val cnt_jalr_redirect_total = RegInit(0.U(64.W))
   val cnt_cbranches_total     = RegInit(0.U(64.W))

   cnt_cbranch_redirect  := satInc(cnt_cbranch_redirect,  io.events.cbranch_redirect)
   cnt_jalr_mispredict     := satInc(cnt_jalr_mispredict,     io.events.jalr_mispredict)
   cnt_jalr_id_suppressed  := satInc(cnt_jalr_id_suppressed,  io.events.jalr_id_suppressed)
   cnt_branches_total      := satInc(cnt_branches_total,      io.events.branches_total)
   cnt_jalr_redirect_total := satInc(cnt_jalr_redirect_total, io.events.jalr_redirect_total)
   cnt_cbranches_total     := satInc(cnt_cbranches_total,     io.events.cbranches_total)

   io.counters.cbranch_redirect  := cnt_cbranch_redirect
   io.counters.jalr_mispredict     := cnt_jalr_mispredict
   io.counters.jalr_id_suppressed  := cnt_jalr_id_suppressed
   io.counters.branches_total      := cnt_branches_total
   io.counters.jalr_redirect_total := cnt_jalr_redirect_total
   io.counters.cbranches_total     := cnt_cbranches_total
}
