// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Four-digit seven-segment display driver.

package sacris_v.periphery

import chisel3._
import chisel3.util._

class SevenSegment(
                    clkFreqHz: Int = 80_000_000,
                    refreshHzPerDigit: Int = 1000
                  ) extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(32.W))
    val dp  = Input(Vec(4, Bool()))
    val seg = Output(UInt(8.W))
    val an  = Output(UInt(4.W))
  })

  val tickCount = math.max(1, clkFreqHz / (refreshHzPerDigit * 4))
  val tickWidth = math.max(1, log2Ceil(tickCount))

  val tick     = RegInit(0.U(tickWidth.W))
  val digitIdx = RegInit(0.U(2.W))

  when(tick === (tickCount - 1).U) {
    tick := 0.U
    digitIdx := digitIdx + 1.U
  }.otherwise {
    tick := tick + 1.U
  }

  val nibbles = Wire(Vec(4, UInt(4.W)))
  nibbles(0) := io.in(3, 0)
  nibbles(1) := io.in(7, 4)
  nibbles(2) := io.in(11, 8)
  nibbles(3) := io.in(15, 12)

  val curNibble = nibbles(digitIdx)

  def hexTo7SegActiveHigh(x: UInt): UInt = {
    MuxLookup(x, "b0000000".U)(Seq(
      0x0.U -> "b0111111".U,
      0x1.U -> "b0000110".U,
      0x2.U -> "b1011011".U,
      0x3.U -> "b1001111".U,
      0x4.U -> "b1100110".U,
      0x5.U -> "b1101101".U,
      0x6.U -> "b1111101".U,
      0x7.U -> "b0000111".U,
      0x8.U -> "b1111111".U,
      0x9.U -> "b1101111".U,
      0xA.U -> "b1110111".U,
      0xB.U -> "b1111100".U,
      0xC.U -> "b0111001".U,
      0xD.U -> "b1011110".U,
      0xE.U -> "b1111001".U,
      0xF.U -> "b1110001".U
    ))
  }

  val seg7_activeHigh = hexTo7SegActiveHigh(curNibble)

  val dp_activeHigh = io.dp(digitIdx).asUInt

  val seg_activeHigh = Cat(dp_activeHigh, seg7_activeHigh)
  io.seg := (~seg_activeHigh)(7, 0)

  val an_activeHigh = (1.U(4.W) << digitIdx)
  io.an := (~an_activeHigh)(3, 0)
}
