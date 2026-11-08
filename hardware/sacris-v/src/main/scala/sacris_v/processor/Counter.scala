// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Free-running system counter, readable over MMIO.

package sacris_v.processor

import chisel3._

class SysCounterIO extends Bundle {
  val counter0 = Output(UInt(64.W))
}

class SysCounter extends Module {
  val io = IO(new SysCounterIO)
  private val cnt0 = RegInit(0.asUInt(64.W))
  cnt0 := cnt0 + 1.U
  io.counter0 := cnt0
}
