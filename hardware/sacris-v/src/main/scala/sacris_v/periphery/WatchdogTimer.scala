// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Watchdog timer with MMIO registers; raises faultIRQ on expiry.

package sacris_v.periphery

import chisel3._
import chisel3.util._

class WatchdogTimer(counterWidth: Int = 32, lfsrWidth: Int = 16) extends Module {
  val io = IO(new Bundle {

    val ctrl_wdata   = Input(UInt(32.W))

    val ctrl_wen     = Input(Bool())

    val reload_wdata = Input(UInt(32.W))

    val reload_wen   = Input(Bool())

    val window_wdata = Input(UInt(32.W))

    val window_wen   = Input(Bool())

    val feed_wdata   = Input(UInt(32.W))

    val feed_wen     = Input(Bool())

    val reload2_wdata = Input(UInt(32.W))

    val reload2_wen   = Input(Bool())

    val status_rdata    = Output(UInt(32.W))

    val challenge_rdata = Output(UInt(32.W))

    val timeout    = Output(Bool())

    val hardReset  = Output(Bool())
  })

  val mode       = RegInit(0.U(2.W))
  val enable     = RegInit(false.B)
  val counter    = RegInit(0.U(counterWidth.W))
  val reloadVal  = RegInit(0.U(counterWidth.W))
  val windowVal  = RegInit(0.U(counterWidth.W))
  val timeoutLatch = RegInit(false.B)

  val reload2Val = RegInit(0.U(counterWidth.W))

  val stage = RegInit(false.B)

  val seed = "hACE1".U(lfsrWidth.W)
  val lfsr = RegInit(seed)
  val lfsrFeedback = lfsr(0)
  val lfsrTaps = if (lfsrWidth == 16) "hB400".U(lfsrWidth.W) else "hACE1".U(lfsrWidth.W)
  val lfsrNext = Mux(lfsrFeedback, (lfsr >> 1) ^ lfsrTaps, lfsr >> 1)

  val lfsrNextNonZero = Mux(lfsrNext === 0.U, seed, lfsrNext)

  lfsr := lfsrNextNonZero

  val challengeLatch = RegInit(seed)

  val feedTimeout  = WireDefault(false.B)
  val feedReload   = WireDefault(false.B)
  val counterZero  = enable && (counter === 0.U)

  val counterTimeout = counterZero && !feedReload
  val anyTimeout     = feedTimeout || counterTimeout

  val stage0Event = anyTimeout && !stage
  val stage1Event = anyTimeout && stage

  when(io.ctrl_wen) {
    mode   := io.ctrl_wdata(1, 0)
    enable := io.ctrl_wdata(2)

    timeoutLatch := false.B
    stage := false.B

    when(io.ctrl_wdata(2) && !enable) {
      counter := reloadVal
    }
  }

  when(io.reload_wen) {
    reloadVal  := io.reload_wdata(counterWidth - 1, 0)
    reload2Val := io.reload_wdata(counterWidth - 1, 0)
  }

  when(io.reload2_wen) {
    reload2Val := io.reload2_wdata(counterWidth - 1, 0)
  }

  when(io.window_wen) {
    windowVal := io.window_wdata(counterWidth - 1, 0)
  }

  when(io.feed_wen && enable) {
    switch(mode) {
      is(0.U) {

        counter    := reloadVal
        feedReload := true.B
        stage      := false.B
      }
      is(1.U) {

        when(counter <= windowVal) {
          counter    := reloadVal
          feedReload := true.B
          stage      := false.B
        }.otherwise {
          feedTimeout := true.B
        }
      }
      is(2.U) {

        val expected = ~challengeLatch
        when(io.feed_wdata(lfsrWidth - 1, 0) === expected(lfsrWidth - 1, 0)) {
          counter    := reloadVal
          feedReload := true.B
          stage      := false.B
        }.otherwise {
          feedTimeout := true.B
        }
      }
    }
  }

  when(feedReload && mode === 2.U) {
    challengeLatch := lfsr
  }

  when(enable && !counterZero && !feedTimeout && !feedReload) {
    counter := counter - 1.U
  }

  when(stage0Event) {
    timeoutLatch := true.B
    counter      := reload2Val
    stage        := true.B
  }

  when(stage1Event) {
    timeoutLatch := true.B
    counter      := reloadVal
    stage        := false.B
  }

  io.timeout    := stage0Event
  io.hardReset  := stage1Event
  io.status_rdata    := Cat(0.U(29.W), stage, timeoutLatch, enable)
  io.challenge_rdata := Cat(0.U((32 - lfsrWidth).W), challengeLatch)
}
