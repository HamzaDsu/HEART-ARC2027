// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Splits the 64-bit fetch window into the two issue slots.

package sacris_v.processor

import chisel3._

class FetchSplitter extends Module {
  val io = IO(new Bundle {

    val fetch_data  = Input(UInt(64.W))
    val fetch_valid = Input(Bool())
    val fetch_pc    = Input(UInt(32.W))

    val slotA_inst  = Output(UInt(32.W))
    val slotA_valid = Output(Bool())
    val slotA_pc    = Output(UInt(32.W))

    val slotB_inst  = Output(UInt(32.W))
    val slotB_valid = Output(Bool())
    val slotB_pc    = Output(UInt(32.W))
  })

  val lowHalf  = io.fetch_data(31, 0)
  val highHalf = io.fetch_data(63, 32)
  val bit2     = io.fetch_pc(2)

  io.slotA_inst  := Mux(bit2, highHalf, lowHalf)
  io.slotA_valid := io.fetch_valid
  io.slotA_pc    := io.fetch_pc

  io.slotB_inst  := highHalf
  io.slotB_valid := io.fetch_valid && !bit2
  io.slotB_pc    := io.fetch_pc + 4.U
}
