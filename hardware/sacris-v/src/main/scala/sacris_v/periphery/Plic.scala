// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Platform-level interrupt controller: priorities, enables, threshold, claim/complete.

package sacris_v.periphery

import chisel3._
import chisel3.util._

class Plic(numSources: Int = 15) extends Module {
  require(numSources >= 1 && numSources <= 15, "PLIC supports 1..15 sources")

  val io = IO(new Bundle {

    val addr  = Input(UInt(22.W))
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
    val wen   = Input(Bool())
    val ren   = Input(Bool())

    val sources = Input(Vec(numSources + 1, Bool()))

    val mei = Output(Bool())
  })

  val N = numSources + 1

  val priorityReg = RegInit(VecInit(Seq.fill(N)(0.U(3.W))))

  val enableReg = RegInit(0.U(N.W))

  val thresholdReg = RegInit(0.U(3.W))

  val claimedReg = RegInit(0.U(N.W))

  val pending = Wire(Vec(N, Bool()))
  for (i <- 0 until N) {
    if (i == 0) {
      pending(i) := false.B
    } else {
      pending(i) := io.sources(i) && !claimedReg(i)
    }
  }
  val pendingBits = pending.asUInt

  val claimable = Wire(Vec(N, Bool()))
  for (i <- 0 until N) {
    if (i == 0) {
      claimable(i) := false.B
    } else {
      claimable(i) := pending(i) && enableReg(i) && (priorityReg(i) > thresholdReg)
    }
  }

  val (bestId, _) = (1 until N).foldLeft((0.U(log2Ceil(N).W), 0.U(3.W))) {
    case ((prevId, prevPri), i) =>
      val selThis = claimable(i) && (priorityReg(i) > prevPri ||
                    (priorityReg(i) === prevPri && prevId === 0.U))
      val nextId  = Mux(selThis, i.U(log2Ceil(N).W), prevId)
      val nextPri = Mux(selThis, priorityReg(i), prevPri)
      (nextId, nextPri)
  }

  io.mei := claimable.asUInt.orR

  val addr = io.addr

  val isPriorityRegion   = addr < 0x1000.U
  val isPendingRegion    = addr >= 0x1000.U && addr < 0x2000.U
  val isEnableRegion     = addr >= 0x2000.U && addr < 0x3000.U
  val isThresholdAddr    = addr === 0x200000.U
  val isClaimAddr        = addr === 0x200004.U

  val priIdx = addr(5, 2)

  val rdataReg = RegInit(0.U(32.W))

  when(io.ren) {
    when(isPriorityRegion) {
      rdataReg := Mux(priIdx < N.U, priorityReg(priIdx), 0.U)
    }.elsewhen(isPendingRegion) {
      rdataReg := pendingBits
    }.elsewhen(isEnableRegion) {
      rdataReg := enableReg
    }.elsewhen(isThresholdAddr) {
      rdataReg := thresholdReg
    }.elsewhen(isClaimAddr) {

      rdataReg := bestId
      when(bestId =/= 0.U) {
        claimedReg := claimedReg | (1.U << bestId)
      }
    }.otherwise {
      rdataReg := 0.U
    }
  }

  io.rdata := rdataReg

  when(io.wen) {
    when(isPriorityRegion) {
      when(priIdx =/= 0.U && priIdx < N.U) {
        priorityReg(priIdx) := io.wdata(2, 0)
      }
    }.elsewhen(isEnableRegion) {

      enableReg := io.wdata(N - 1, 0) & ~1.U(N.W)
    }.elsewhen(isThresholdAddr) {
      thresholdReg := io.wdata(2, 0)
    }.elsewhen(isClaimAddr) {

      val completeId = io.wdata(log2Ceil(N) - 1, 0)
      when(completeId =/= 0.U && completeId < N.U) {
        claimedReg := claimedReg & ~(1.U << completeId)
      }
    }
  }
}
