// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Dual-issue pair-eligibility checks: decides whether slot B issues together with slot A.

package sacris_v.processor

import chisel3._
import sacris_v.processor.Constants._

class CtlDecodeBundle(val enableFExtension: Boolean = false) extends Bundle {
  val rf_wen    = Bool()
  val mem_val   = Bool()
  val wb_sel    = UInt(3.W)
  val fencei    = Bool()
  val is_ecall  = Bool()
  val is_ebreak = Bool()
  val is_wfi    = Bool()
  val illegal   = Bool()
  val br_type   = PC_OP.Type()
  val is_zbc    = Bool()
  val is_zbs    = Bool()

  val is_fp     = if (enableFExtension) Some(Bool()) else None
}

class IssueObserveBundle extends Bundle {
  val reject_check1_raw_intra    = Bool()
  val reject_check2_waw_intra    = Bool()
  val reject_check3_B_mem        = Bool()
  val reject_check4_B_mdu        = Bool()
  val reject_check5_B_csr        = Bool()
  val reject_check6_B_branch     = Bool()
  val reject_check7_B_sync       = Bool()
  val reject_check8_B_illegal    = Bool()
  val reject_check9_A_taken_br   = Bool()
  val reject_check10_B_zbc_zbs   = Bool()
  val reject_check11_B_fp        = Bool()
  val issued_pair                = Bool()
  val issued_single_A_only       = Bool()
  val stalled_B                  = Bool()

  val reject_check1_sole_fire    = Bool()
}

class IssueLogicIo(exposeRejectCounters: Boolean, enableFExtension: Boolean = false) extends Bundle {
  val decA = Input(new CtlDecodeBundle(enableFExtension))
  val decB = Input(new CtlDecodeBundle(enableFExtension))
  val A_rs1 = Input(UInt(5.W))
  val A_rs2 = Input(UInt(5.W))
  val A_rd  = Input(UInt(5.W))
  val B_rs1 = Input(UInt(5.W))
  val B_rs2 = Input(UInt(5.W))
  val B_rd  = Input(UInt(5.W))
  val A_valid = Input(Bool())
  val B_valid = Input(Bool())
  val A_is_taken_branch = Input(Bool())

  val issue_A  = Output(Bool())
  val issue_B  = Output(Bool())
  val stall_A  = Output(Bool())
  val stall_B  = Output(Bool())
  val squash_B = Output(Bool())

  val observe = if (exposeRejectCounters) Some(Output(new IssueObserveBundle)) else None
}

class IssueLogic(
    enableIntraPairForward: Boolean = false,
    exposeRejectCounters:   Boolean = false,
    enableFExtension:       Boolean = false
) extends Module {
  val io = IO(new IssueLogicIo(exposeRejectCounters, enableFExtension))

  private val A_is_alu_or_bext =
    (io.decA.wb_sel === WB_ALU) || (io.decA.wb_sel === WB_BEXT)

  private val raw_match =
    io.decA.rf_wen && (io.A_rd =/= 0.U) &&
      ((io.A_rd === io.B_rs1) || (io.A_rd === io.B_rs2))
  private val raw_reject =
    if (enableIntraPairForward) raw_match && !A_is_alu_or_bext
    else                         raw_match

  private val waw_match =
    io.decA.rf_wen && io.decB.rf_wen && (io.A_rd =/= 0.U) && (io.A_rd === io.B_rd)

  private val b_is_mem = io.decB.mem_val

  private val b_is_mdu = io.decB.wb_sel === WB_MDU

  private val b_is_csr = io.decB.wb_sel === WB_CSR

  private val b_is_br = io.decB.br_type =/= PC_OP.None

  private val b_is_sync =
    io.decB.is_ecall || io.decB.is_ebreak || io.decB.is_wfi || io.decB.fencei

  private val b_illegal = io.decB.illegal

  private val b_is_zbczbs = io.decB.is_zbc || io.decB.is_zbs

  private val b_is_fp =
    if (enableFExtension) io.decB.is_fp.get
    else                  false.B

  private val pair_reject =
    raw_reject || b_is_mem || b_is_mdu || b_is_csr || b_is_br ||
      b_is_sync || b_illegal || b_is_zbczbs || b_is_fp

  private val squash_B = io.A_is_taken_branch

  io.issue_A  := io.A_valid
  io.issue_B  := io.A_valid && io.B_valid && !pair_reject && !squash_B
  io.stall_A  := false.B
  io.stall_B  := io.A_valid && io.B_valid && pair_reject && !squash_B
  io.squash_B := squash_B

  dontTouch(io.issue_A)
  dontTouch(io.issue_B)
  dontTouch(io.stall_A)
  dontTouch(io.stall_B)
  dontTouch(io.squash_B)

  if (exposeRejectCounters) {
    val o = io.observe.get
    o.reject_check1_raw_intra  := io.B_valid && raw_reject
    o.reject_check2_waw_intra  := io.B_valid && waw_match
    o.reject_check3_B_mem      := io.B_valid && b_is_mem
    o.reject_check4_B_mdu      := io.B_valid && b_is_mdu
    o.reject_check5_B_csr      := io.B_valid && b_is_csr
    o.reject_check6_B_branch   := io.B_valid && b_is_br
    o.reject_check7_B_sync     := io.B_valid && b_is_sync
    o.reject_check8_B_illegal  := io.B_valid && b_illegal
    o.reject_check9_A_taken_br := io.B_valid && squash_B
    o.reject_check10_B_zbc_zbs := io.B_valid && b_is_zbczbs
    o.reject_check11_B_fp      := io.B_valid && b_is_fp

    val issued_pair          = io.issue_A && io.issue_B
    val stalled_B_out        = io.stall_B
    val issued_single_A_only = io.issue_A && !io.issue_B && !stalled_B_out
    o.issued_pair          := issued_pair
    o.issued_single_A_only := issued_single_A_only
    o.stalled_B            := stalled_B_out

    val ch1_sole = raw_reject &&
                   !b_is_mem && !b_is_mdu && !b_is_csr &&
                   !b_is_br && !b_is_sync && !b_illegal &&
                   !b_is_zbczbs && !b_is_fp && !squash_B
    o.reject_check1_sole_fire := io.B_valid && ch1_sole
  }
}
