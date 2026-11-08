// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Bundles exporting committed state for lockstep comparison, TMR voting and rollback.

package sacris_v.processor

import chisel3._
import sacris_v.common.{MemCmd, MemType}
import sacris_v.processor.Constants._

class InflightSnapshot(val withFp: Boolean = false) extends Bundle {

  val if_pc        = UInt(32.W)
  val if_pc_buffer = UInt(32.W)

  val id_valid = Bool()
  val id_pc    = UInt(32.W)
  val id_inst  = UInt(32.W)

  val ex_valid           = Bool()
  val ex_inst            = UInt(32.W)
  val ex_pc              = UInt(32.W)
  val ex_wbaddr          = UInt(5.W)
  val ex_rs1_addr        = UInt(5.W)
  val ex_rs2_addr        = UInt(5.W)
  val ex_op1_data        = UInt(32.W)
  val ex_op2_data        = UInt(32.W)
  val ex_rs2_data        = UInt(32.W)
  val ex_ctrl_alu_func   = AluOp.Type()
  val ex_ctrl_wb_sel     = UInt(3.W)
  val ex_ctrl_rf_wen     = Bool()
  val ex_ctrl_mem_val    = Bool()
  val ex_ctrl_mem_cmd    = MemCmd.Type()
  val ex_ctrl_mem_typ    = MemType.Type()
  val ex_predicted_taken = Bool()

  val mem_valid          = Bool()
  val mem_pc             = UInt(32.W)
  val mem_inst           = UInt(32.W)
  val mem_alu_out        = UInt(32.W)
  val mem_wbaddr         = UInt(5.W)
  val mem_rs1_addr       = UInt(5.W)
  val mem_rs2_addr       = UInt(5.W)
  val mem_op1_data       = UInt(32.W)
  val mem_op2_data       = UInt(32.W)
  val mem_ctrl_rf_wen    = Bool()
  val mem_ctrl_mem_val   = Bool()
  val mem_ctrl_mem_cmd   = MemCmd.Type()
  val mem_ctrl_mem_typ   = MemType.Type()
  val mem_ctrl_wb_sel    = UInt(3.W)
  val mem_data_misaligned= Bool()

  val wb_valid        = Bool()
  val wb_wbaddr       = UInt(5.W)
  val wb_wbdata       = UInt(32.W)
  val wb_ctrl_rf_wen  = Bool()

  val ex_fp_wen       = if (withFp) Some(Bool())       else None
  val ex_fp_op1_data  = if (withFp) Some(UInt(33.W))   else None
  val ex_fp_op2_data  = if (withFp) Some(UInt(33.W))   else None
  val ex_fp_op3_data  = if (withFp) Some(UInt(33.W))   else None
  val ex_fpu_op       = if (withFp) Some(FpuOp.Type()) else None
  val ex_fp_fma       = if (withFp) Some(Bool())       else None
  val ex_fp_excFlags  = if (withFp) Some(UInt(5.W))    else None
  val ex_fp_storeData = if (withFp) Some(UInt(32.W))   else None
  val mem_fp_wen      = if (withFp) Some(Bool())       else None
  val mem_fp_wbdata   = if (withFp) Some(UInt(33.W))   else None
  val mem_fp_is_load  = if (withFp) Some(Bool())       else None
  val mem_fp_fma      = if (withFp) Some(Bool())       else None
  val mem_fp_excFlags = if (withFp) Some(UInt(5.W))    else None
  val wb_fp_wen       = if (withFp) Some(Bool())       else None
  val wb_fp_wbdata    = if (withFp) Some(UInt(33.W))   else None
  val wb_fp_excFlags  = if (withFp) Some(UInt(5.W))    else None
}

class SafetyCommitIO(val enableDualIssue: Boolean, val enableFExtension: Boolean,
                     val exposeGprSnapshot: Boolean = false,
                     val withInflight: Boolean = false,
                     val exposeRegfileScrub: Boolean = false,
                     val exposeRegfileScrubSelfCheck: Boolean = false,
                     val exposeRegfileEcc: Boolean = false,
                     val withRollbackPc: Boolean = false) extends Bundle {

  val wp0_wen   = Bool()
  val wp0_waddr = UInt(5.W)
  val wp0_wdata = UInt(32.W)

  val wp1_wen   = if (enableDualIssue) Some(Bool())       else None
  val wp1_waddr = if (enableDualIssue) Some(UInt(5.W))    else None
  val wp1_wdata = if (enableDualIssue) Some(UInt(32.W))   else None

  val wb_valid   = Bool()
  val wb_B_valid = if (enableDualIssue) Some(Bool())      else None

  val csrState = UInt(CSR.archStateWidth(enableFExtension).W)

  val fp_wen   = if (enableFExtension) Some(Bool())       else None
  val fp_waddr = if (enableFExtension) Some(UInt(5.W))    else None
  val fp_wdata = if (enableFExtension) Some(UInt(33.W))   else None

  val fpu_busy = if (enableFExtension) Some(Bool())        else None

  val gprs = if (exposeGprSnapshot) Some(Vec(32, UInt(32.W))) else None

  val fpRegs = if (enableFExtension && exposeGprSnapshot) Some(Vec(32, UInt(33.W))) else None

  val inflight = if (withInflight) Some(new InflightSnapshot(enableFExtension)) else None

  val scrubIncoh = if (exposeRegfileScrub) Some(Bool())    else None
  val scrubIdx   = if (exposeRegfileScrub) Some(UInt(5.W)) else None

  val scrubStFail = if (exposeRegfileScrubSelfCheck) Some(Bool()) else None

  val rfEccCorrectable   = if (exposeRegfileEcc) Some(Bool()) else None
  val rfEccUncorrectable = if (exposeRegfileEcc) Some(Bool()) else None

  val mem_pc    = if (withRollbackPc) Some(UInt(32.W)) else None
  val mem_valid = if (withRollbackPc) Some(Bool())     else None
}

class RollbackRestoreIO(val archWidth: Int, val withParallel: Boolean = false,
                        val withFp: Boolean = false,
                        val withInflight: Boolean = false) extends Bundle {
  val gprEn   = Bool()
  val gprAddr = UInt(5.W)
  val gprData = UInt(32.W)
  val csrEn   = Bool()
  val csr     = UInt(archWidth.W)
  val pcEn    = Bool()
  val pc      = UInt(32.W)
  val flush   = Bool()

  val parEn   = if (withParallel) Some(Bool())                  else None
  val parData = if (withParallel) Some(Vec(32, UInt(32.W)))     else None

  val fpParEn   = if (withFp) Some(Bool())                  else None
  val fpParData = if (withFp) Some(Vec(32, UInt(33.W)))     else None

  val inflightEn = if (withInflight) Some(Bool())                       else None
  val inflight   = if (withInflight) Some(new InflightSnapshot(withFp)) else None
}
