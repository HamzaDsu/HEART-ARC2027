// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Instruction decoder, hazard detection, stall/kill control and exception requests.

package sacris_v.processor

import chisel3._
import chisel3.util._
import sacris_v.common.Instructions._
import sacris_v.common._
import sacris_v.common.{MemCmd, MemType}
import sacris_v.processor.Constants._

class CtlToDatIo(val enableFExtension: Boolean = false) extends Bundle() {
  val id_stall   = Output(Bool())
  val full_stall = Output(Bool())
  val ex_pc_sel  = Output(PcSel.Type())
  val if_kill    = Output(Bool())
  val id_kill    = Output(Bool())
  val op1_sel    = Output(Op1Sel.Type())
  val op2_sel    = Output(Op2Sel.Type())
  val alu_func    = Output(AluOp.Type())
  val wb_sel     = Output(UInt(3.W))
  val rf_wen     = Output(Bool())
  val mem_val    = Output(Bool())
  val mem_cmd    = Output(MemCmd.Type())
  val mem_typ    = Output(MemType.Type())
  val fencei     = Output(Bool())
  val ex_is_wfi  = Output(Bool())

  val pipeline_kill = Output(Bool())
  val mem_exception = Output(Bool())
  val mem_exception_cause = Output(UInt(32.W))

  val fp_wen  = if (enableFExtension) Some(Output(Bool()))         else None
  val fp_ren1 = if (enableFExtension) Some(Output(Bool()))         else None
  val fp_ren2 = if (enableFExtension) Some(Output(Bool()))         else None

  val fp_ren3 = if (enableFExtension) Some(Output(Bool()))         else None
  val fp_fma  = if (enableFExtension) Some(Output(Bool()))         else None
  val fpu_op  = if (enableFExtension) Some(Output(FpuOp.Type()))   else None
}

class ControlUnitIo(enableFExtension: Boolean = false, enableCExtension: Boolean = false) extends Bundle() {
  val imem = new BootRomPortIO()
  val dmem = new MemPortIO(32)
  val dat  = Flipped(new DatToCtlIo(enableCExtension))
  val ctl  = new CtlToDatIo(enableFExtension)
  val mdu_busy = Input(Bool())
  val wfi_stall = Input(Bool())
  val dmaMemStall = Input(Bool())
}

class ControlUnit(enableMExtension: Boolean = true, enableBExtension: Boolean = true, withLateLoadForward: Boolean = false, exposeDecodeBundle: Boolean = false, enableFExtension: Boolean = false, enableCExtension: Boolean = false) extends Module {
  val io = IO(new ControlUnitIo(enableFExtension, enableCExtension))

  val dec: Option[CtlDecodeBundle] = if (exposeDecodeBundle) Some(IO(Output(new CtlDecodeBundle(enableFExtension)))) else None

  val slotB_valid    : Option[Bool] = if (exposeDecodeBundle) Some(IO(Input(Bool())))      else None
  val id_rsB1_addr   : Option[UInt] = if (exposeDecodeBundle) Some(IO(Input(UInt(5.W))))   else None
  val id_rsB2_addr   : Option[UInt] = if (exposeDecodeBundle) Some(IO(Input(UInt(5.W))))   else None
  io := DontCare

  val cs_val_inst = WireDefault(N)
  val cs_br_type = WireDefault(PC_OP.None)
  val cs_reg_br_type = RegInit(PC_OP.None)
  val cs_br_cond = WireDefault(BrCond.None)
  val cs_op1_sel = WireDefault(Op1Sel.X)
  val cs_op2_sel = WireDefault(Op2Sel.X)
  val cs_rs1_oen = WireDefault(OEN_0)
  val cs_rs2_oen = WireDefault(OEN_0)

  val cs_alu_funcc = WireDefault(AluOp.X)
  val cs_wb_sel = WireDefault(WB_X)
  val cs_rf_wen = WireDefault(REN_0)
  val cs_mem_en = WireDefault(MEN_0)
  val cs_mem_cmd = WireDefault(MemCmd.Read)
  val cs_mem_typ = WireDefault(MemType.X)
  val cs_fencei = WireDefault(N)

  val ex_reg_illegal = RegInit(false.B)

  val cs_is_ecall = WireDefault(false.B)
  val cs_is_ebreak = WireDefault(false.B)
  val cs_is_wfi = WireDefault(false.B)

  val ex_reg_is_ecall = RegInit(false.B)
  val ex_reg_is_ebreak = RegInit(false.B)
  val ex_reg_is_wfi = RegInit(false.B)

  private def set(
                   valInst: Bool,
                   pcOP: PC_OP.Type,
                   op1Sel: Op1Sel.Type,
                   op2Sel: Op2Sel.Type,
                   rs1Oen: Bool,
                   rs2Oen: Bool,
                   aluFun: AluOp.Type,
                   wbSel: UInt,
                   rfWen: Bool,
                   memEn: Bool,
                   memCmd: MemCmd.Type,
                   memTyp: MemType.Type,
                   fencei: Bool

                 ): Unit = {
    cs_val_inst := valInst
    cs_br_type := pcOP
    cs_op1_sel := op1Sel
    cs_op2_sel := op2Sel
    cs_rs1_oen := rs1Oen
    cs_rs2_oen := rs2Oen
    cs_alu_funcc := aluFun
    cs_wb_sel := wbSel
    cs_rf_wen := rfWen
    cs_mem_en := memEn
    cs_mem_cmd := memCmd
    cs_mem_typ := memTyp
    cs_fencei := fencei
  }

 dontTouch(cs_br_type)

  when(LW === io.dat.id_inst) {
    set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_MEM, REN_1, MEN_1, MemCmd.Read, MemType.W, N)
  }
    .elsewhen(LB === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_MEM, REN_1, MEN_1, MemCmd.Read, MemType.B, N)
    }
    .elsewhen(LBU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_MEM, REN_1, MEN_1, MemCmd.Read, MemType.BU, N)
    }
    .elsewhen(LH === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_MEM, REN_1, MEN_1, MemCmd.Read, MemType.H, N)
    }
    .elsewhen(LHU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_MEM, REN_1, MEN_1, MemCmd.Read, MemType.HU, N)
    }

    .elsewhen(SW === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.STYPE, OEN_1, OEN_1, AluOp.ADD, WB_X, REN_0, MEN_1, MemCmd.Write, MemType.W, N)
    }
    .elsewhen(SB === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.STYPE, OEN_1, OEN_1, AluOp.ADD, WB_X, REN_0, MEN_1, MemCmd.Write, MemType.B, N)
    }
    .elsewhen(SH === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.STYPE, OEN_1, OEN_1, AluOp.ADD, WB_X, REN_0, MEN_1, MemCmd.Write, MemType.H, N)
    }

    .elsewhen(AUIPC === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.PC, Op2Sel.UTYPE, OEN_0, OEN_0, AluOp.ADD, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(LUI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.UTYPE, OEN_0, OEN_0, AluOp.COPY_2, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(ADDI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.ADD, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ANDI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.AND, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ORI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.OR, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(XORI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.XOR, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SLTI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.SLT, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SLTIU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.SLTU, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(SLLI_RV32 === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.SLL, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SRAI_RV32 === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.SRA, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SRLI_RV32 === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.SRL, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(SLL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SLL, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ADD === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.ADD, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SUB === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SUB, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SLT === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SLT, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SLTU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SLTU, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(AND === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.AND, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(OR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.OR, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(XOR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.XOR, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SRA === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SRA, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SRL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.SRL, WB_ALU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(JAL === io.dat.id_inst) {
      set(Y, PC_OP.J, Op1Sel.RS1, Op2Sel.UJTYPE, OEN_0, OEN_0, AluOp.X, WB_PC4, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.None
    }
    .elsewhen(JALR === io.dat.id_inst) {
      set(Y, PC_OP.JR, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_PC4, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.None
    }

    .elsewhen(BEQ === io.dat.id_inst) {
      set(Y, PC_OP.EQ, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.EQ
    }
    .elsewhen(BNE === io.dat.id_inst) {
      set(Y, PC_OP.NE, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.NE
    }
    .elsewhen(BGE === io.dat.id_inst) {
      set(Y, PC_OP.GE, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.GE
    }
    .elsewhen(BGEU === io.dat.id_inst) {
      set(Y, PC_OP.GEU, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.GEU
    }
    .elsewhen(BLT === io.dat.id_inst) {
      set(Y, PC_OP.LT, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.LT
    }
    .elsewhen(BLTU === io.dat.id_inst) {
      set(Y, PC_OP.LTU, Op1Sel.RS1, Op2Sel.SBTYPE, OEN_1, OEN_1, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_br_cond := BrCond.LTU
    }

    .elsewhen(CSRRWI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.IMZ, Op2Sel.X, OEN_1, OEN_0, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CSRRSI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.IMZ, Op2Sel.X, OEN_1, OEN_0, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CSRRW === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.X, OEN_1, OEN_1, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CSRRS === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.X, OEN_1, OEN_1, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CSRRC === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.X, OEN_1, OEN_1, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CSRRCI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.IMZ, Op2Sel.X, OEN_1, OEN_0, AluOp.COPY_1, WB_CSR, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(MRET === io.dat.id_inst) {
      set(Y, PC_OP.MRET, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(ECALL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_is_ecall := true.B
    }
    .elsewhen(EBREAK === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_is_ebreak := true.B
    }

    .elsewhen(FENCE === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(FENCE_I === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, Y)
    }

    .elsewhen(WFI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.X, Op2Sel.X, OEN_0, OEN_0, AluOp.X, WB_X, REN_0, MEN_0, MemCmd.Read, MemType.X, N)
      cs_is_wfi := true.B
    }

  if (enableMExtension) {
    when(MUL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.MULT, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MULH === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.MULH, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MULHSU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.MULHSU, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MULHU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.MULHU, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(DIV === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.DIV, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(DIVU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.DIVU, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(REM === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.REM, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(REMU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.REMU, WB_MDU, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
  }

  if (enableBExtension) {

    when(SH1ADD === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SH2ADD === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SH3ADD === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(ANDN === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ORN === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(XNOR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MIN === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MINU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MAX === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(MAXU === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ROL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ROR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ZEXT_H === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(CLZ === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CTZ === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CPOP === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SEXT_B === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(SEXT_H === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(RORI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(REV8 === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(ORC_B === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(CLMUL === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CLMULH === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(CLMULR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(BCLR === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BEXT === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BINV === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BSET === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.RS2, OEN_1, OEN_1, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }

    .elsewhen(BCLRI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BEXTI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BINVI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
    .elsewhen(BSETI === io.dat.id_inst) {
      set(Y, PC_OP.None, Op1Sel.RS1, Op2Sel.ITYPE, OEN_1, OEN_0, AluOp.X, WB_BEXT, REN_1, MEN_0, MemCmd.Read, MemType.X, N)
    }
  }

  val cs_fp_wen  = WireDefault(false.B)
  val cs_fp_ren1 = WireDefault(false.B)
  val cs_fp_ren2 = WireDefault(false.B)
  val cs_fp_ren3 = WireDefault(false.B)
  val cs_fp_fma  = WireDefault(false.B)
  val cs_fpu_op  = WireDefault(FpuOp.X)

  if (enableFExtension) {

    def fpSet(
        valInst: Bool      = Y,
        op1Sel:  Op1Sel.Type = Op1Sel.X,
        op2Sel:  Op2Sel.Type = Op2Sel.X,
        rs1Oen:  Bool      = OEN_0,
        rs2Oen:  Bool      = OEN_0,
        wbSel:   UInt      = WB_X,
        rfWen:   Bool      = REN_0,
        memEn:   Bool      = MEN_0,
        memCmd:  MemCmd.Type = MemCmd.Read,
        memTyp:  MemType.Type = MemType.X,
        fpWen:   Bool      = false.B,
        fpRen1:  Bool      = false.B,
        fpRen2:  Bool      = false.B,
        fpuOp:   FpuOp.Type = FpuOp.X,
        aluFun:  AluOp.Type = AluOp.ADD
    ): Unit = {
      cs_val_inst  := valInst
      cs_br_type   := PC_OP.None
      cs_op1_sel   := op1Sel
      cs_op2_sel   := op2Sel
      cs_rs1_oen   := rs1Oen
      cs_rs2_oen   := rs2Oen
      cs_alu_funcc := aluFun
      cs_wb_sel    := wbSel
      cs_rf_wen    := rfWen
      cs_mem_en    := memEn
      cs_mem_cmd   := memCmd
      cs_mem_typ   := memTyp
      cs_fp_wen    := fpWen
      cs_fp_ren1   := fpRen1
      cs_fp_ren2   := fpRen2
      cs_fpu_op    := fpuOp
    }

    when(FLW === io.dat.id_inst) {
      fpSet(op1Sel = Op1Sel.RS1, op2Sel = Op2Sel.ITYPE, rs1Oen = OEN_1,
            wbSel = WB_FPU, rfWen = REN_0, memEn = MEN_1,
            memCmd = MemCmd.Read, memTyp = MemType.W,
            fpWen = true.B, fpuOp = FpuOp.FLW)
    }

    .elsewhen(FSW === io.dat.id_inst) {
      fpSet(op1Sel = Op1Sel.RS1, op2Sel = Op2Sel.STYPE, rs1Oen = OEN_1,
            rs2Oen = OEN_0, wbSel = WB_X, rfWen = REN_0, memEn = MEN_1,
            memCmd = MemCmd.Write, memTyp = MemType.W,
            fpRen2 = true.B, fpuOp = FpuOp.FSW)
    }

    .elsewhen(FSGNJ_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FSGNJ)
    }
    .elsewhen(FSGNJN_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FSGNJN)
    }
    .elsewhen(FSGNJX_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FSGNJX)
    }

    .elsewhen(FMIN_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FMIN)
    }
    .elsewhen(FMAX_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FMAX)
    }

    .elsewhen(FEQ_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpRen2 = true.B, fpuOp = FpuOp.FEQ)
    }
    .elsewhen(FLT_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpRen2 = true.B, fpuOp = FpuOp.FLT)
    }
    .elsewhen(FLE_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpRen2 = true.B, fpuOp = FpuOp.FLE)
    }

    .elsewhen(FCLASS_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpuOp = FpuOp.FCLASS)
    }

    .elsewhen(FCVT_W_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpuOp = FpuOp.FCVT_W_S)
    }
    .elsewhen(FCVT_WU_S === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpuOp = FpuOp.FCVT_WU_S)
    }

    .elsewhen(FMV_X_W === io.dat.id_inst) {
      fpSet(wbSel = WB_ALU, rfWen = REN_1,
            fpRen1 = true.B, fpuOp = FpuOp.FMV_X_W)
    }

    .elsewhen(FCVT_S_W === io.dat.id_inst) {
      fpSet(op1Sel = Op1Sel.RS1, rs1Oen = OEN_1,
            wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpuOp = FpuOp.FCVT_S_W)
    }
    .elsewhen(FCVT_S_WU === io.dat.id_inst) {
      fpSet(op1Sel = Op1Sel.RS1, rs1Oen = OEN_1,
            wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpuOp = FpuOp.FCVT_S_WU)
    }

    .elsewhen(FMV_W_X === io.dat.id_inst) {
      fpSet(op1Sel = Op1Sel.RS1, rs1Oen = OEN_1,
            wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpuOp = FpuOp.FMV_W_X)
    }

    .elsewhen(FADD_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FADD)
      cs_fp_fma := true.B
    }
    .elsewhen(FSUB_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FSUB)
      cs_fp_fma := true.B
    }
    .elsewhen(FMUL_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FMUL)
      cs_fp_fma := true.B
    }

    .elsewhen(FMADD_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FMADD)
      cs_fp_ren3 := true.B
      cs_fp_fma  := true.B
    }
    .elsewhen(FMSUB_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FMSUB)
      cs_fp_ren3 := true.B
      cs_fp_fma  := true.B
    }
    .elsewhen(FNMSUB_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FNMSUB)
      cs_fp_ren3 := true.B
      cs_fp_fma  := true.B
    }
    .elsewhen(FNMADD_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FNMADD)
      cs_fp_ren3 := true.B
      cs_fp_fma  := true.B
    }

    .elsewhen(FDIV_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B, fpRen2 = true.B,
            fpuOp = FpuOp.FDIV)
    }
    .elsewhen(FSQRT_S === io.dat.id_inst) {
      fpSet(wbSel = WB_FPU, rfWen = REN_0,
            fpWen = true.B, fpRen1 = true.B,
            fpuOp = FpuOp.FSQRT)
    }
  }

  val mem_exception = WireDefault(false.B)
  val mem_exception_cause = WireDefault(0.U(32.W))

  val mem_ecall_raw = RegNext(ex_reg_is_ecall, false.B)
  val mem_ebreak_raw = RegNext(ex_reg_is_ebreak, false.B)

  val pipeline_killed_prev = RegInit(false.B)
  val mem_ecall = mem_ecall_raw && !pipeline_killed_prev
  val mem_ebreak = mem_ebreak_raw && !pipeline_killed_prev

  mem_exception := (if (enableCExtension)
                      RegNext((ex_reg_illegal || io.dat.ex_inst_misaligned) && !io.dat.csr_eret) && !pipeline_killed_prev
                    else
                      RegNext((ex_reg_illegal || io.dat.ex_inst_misaligned) && !io.dat.csr_eret)) || io.dat.mem_data_misaligned || mem_ecall || mem_ebreak

  mem_exception_cause :=
    Mux(mem_ecall, Causes.machine_ecall.U,
      Mux(mem_ebreak, Causes.breakpoint.U,
        Mux(RegNext(ex_reg_illegal), Causes.illegal_instruction.U,
          Mux(RegNext(io.dat.ex_inst_misaligned), Causes.misaligned_fetch.U,
            Mux(io.dat.mem_store, Causes.misaligned_store.U,
              Causes.misaligned_load.U)))))

  val pc_sel = WireDefault(PcSel.Plus4)

  val pipeline_kill_wire = WireDefault(false.B)
  pipeline_kill_wire := mem_exception

  pipeline_killed_prev := pipeline_kill_wire || io.dat.csr_interrupt

  when(io.dat.csr_interrupt || pipeline_kill_wire) {
    pc_sel := PcSel.Exc
  }.elsewhen(io.dat.csr_eret || (cs_reg_br_type === PC_OP.MRET)) {
    pc_sel := PcSel.Mret
  }.otherwise {

    when(cs_reg_br_type === PC_OP.J) {
      when(!io.dat.ex_predicted_taken) {
        pc_sel := PcSel.BrJmp
      }
    }.elsewhen(cs_reg_br_type === PC_OP.JR) {
      when(!io.dat.ex_predicted_taken) {
        pc_sel := PcSel.Jalr
      }
    }.otherwise {

      val br_taken = WireDefault(false.B)
      switch(cs_reg_br_type) {
        is(PC_OP.EQ)  { br_taken :=  io.dat.ex_br_eq }
        is(PC_OP.NE)  { br_taken := !io.dat.ex_br_eq }
        is(PC_OP.LT)  { br_taken :=  io.dat.ex_br_lt }
        is(PC_OP.LTU) { br_taken :=  io.dat.ex_br_ltu }
        is(PC_OP.GE)  { br_taken := !io.dat.ex_br_lt }
        is(PC_OP.GEU) { br_taken := !io.dat.ex_br_ltu }
      }
      when(br_taken && !io.dat.ex_predicted_taken) {

        pc_sel := PcSel.BrJmp
      }.elsewhen(!br_taken && io.dat.ex_predicted_taken) {

        pc_sel := PcSel.ExPc4
      }

    }
  }

  val fencei_d = RegNext(cs_fencei, init = false.B)

  val if_kill_w = (pc_sel =/= PcSel.Plus4) || fencei_d || io.dat.id_predict_taken

  val id_kill_w = (pc_sel =/= PcSel.Plus4) || RegNext(io.dat.csr_interrupt, init = false.B)

  val id_illegal = (!cs_val_inst && io.dat.id_valid) ||
    (if (enableCExtension) (io.dat.id_valid && io.dat.id_cexp_illegal.get) else false.B)

  val stall = Wire(Bool())

  val id_rs1_addr = io.dat.id_inst(19, 15)
  val id_rs2_addr = io.dat.id_inst(24, 20)
  val id_wbaddr   = io.dat.id_inst(11, 7)
  val id_rs1_oen  = Mux(id_kill_w, false.B, cs_rs1_oen)
  val id_rs2_oen  = Mux(id_kill_w, false.B, cs_rs2_oen)

  val ex_inst_is_load = RegInit(false.B)
  val ex_inst_is_mdu  = RegInit(false.B)

  val ex_reg_fp_load  = if (enableFExtension) Some(RegInit(false.B)) else None
  val mem_reg_fp_load = if (enableFExtension) Some(RegInit(false.B)) else None

  val ex_reg_fp_fma   = if (enableFExtension) Some(RegInit(false.B)) else None
  val ex_reg_wbaddr = chisel3.Reg(UInt())
  val mem_reg_wbaddr = Reg(UInt())
  val wb_reg_wbaddr = Reg(UInt())
  val ex_reg_ctrl_rf_wen = RegInit(false.B)
  val mem_reg_ctrl_rf_wen = RegInit(false.B)
  val wb_reg_ctrl_rf_wen = RegInit(false.B)

  val ex_reg_is_csr = RegInit(false.B)
  val full_stall = Wire(Bool())

  when(!full_stall) {

    ex_reg_wbaddr      := 0.U
    ex_reg_ctrl_rf_wen := false.B
    ex_reg_is_csr      := false.B
    ex_reg_illegal     := false.B
    ex_reg_is_ecall    := false.B
    ex_reg_is_ebreak   := false.B
    ex_reg_is_wfi      := false.B
    if (enableFExtension) {

      ex_reg_fp_fma.get := false.B
    }

    when(!stall && !id_kill_w) {
      ex_reg_wbaddr      := id_wbaddr
      ex_reg_ctrl_rf_wen := cs_rf_wen
      ex_reg_illegal     := id_illegal
      ex_reg_is_csr      := (cs_wb_sel === WB_CSR) && cs_rf_wen
      ex_reg_is_ecall    := cs_is_ecall
      ex_reg_is_ebreak   := cs_is_ebreak
      ex_reg_is_wfi      := cs_is_wfi
      if (enableFExtension) {
        ex_reg_fp_fma.get := cs_fp_fma
      }
    }

    mem_reg_wbaddr      := ex_reg_wbaddr
    wb_reg_wbaddr       := mem_reg_wbaddr
    mem_reg_ctrl_rf_wen := ex_reg_ctrl_rf_wen
    wb_reg_ctrl_rf_wen  := mem_reg_ctrl_rf_wen

    ex_inst_is_load := cs_mem_en && (cs_mem_cmd === MemCmd.Read)

    ex_inst_is_mdu := (cs_wb_sel === WB_MDU)

    if (enableFExtension) {
      ex_reg_fp_load.get  := cs_fp_wen && cs_mem_en && (cs_mem_cmd === MemCmd.Read)
      mem_reg_fp_load.get := ex_reg_fp_load.get
    }
  }

  when(io.dat.csr_eret) {
    ex_reg_illegal := false.B
  }

  val load_stall_gate = if (withLateLoadForward) false.B else ex_inst_is_load
  val ex_needs_stall = load_stall_gate || ex_inst_is_mdu

  val slotB_valid_w  = slotB_valid.getOrElse(false.B)
  val id_rsB1_addr_w = id_rsB1_addr.getOrElse(0.U(5.W))
  val id_rsB2_addr_w = id_rsB2_addr.getOrElse(0.U(5.W))

  val id_rs3_addr = io.dat.id_inst(31, 27)

  val fp_load_stall =
    if (enableFExtension) {

      ex_reg_fp_load.get && (
        (cs_fp_ren1 && (ex_reg_wbaddr === id_rs1_addr)) ||
        (cs_fp_ren2 && (ex_reg_wbaddr === id_rs2_addr)) ||
        (cs_fp_ren3 && (ex_reg_wbaddr === id_rs3_addr))
      )
    } else false.B

  val fp_fma_stall =
    if (enableFExtension) {
      ex_reg_fp_fma.get && (
        (cs_fp_ren1 && (ex_reg_wbaddr === id_rs1_addr)) ||
        (cs_fp_ren2 && (ex_reg_wbaddr === id_rs2_addr)) ||
        (cs_fp_ren3 && (ex_reg_wbaddr === id_rs3_addr))
      )
    } else false.B

  stall := (ex_needs_stall && (ex_reg_wbaddr === id_rs1_addr) && (ex_reg_wbaddr =/= 0.U) && id_rs1_oen) ||
    (ex_needs_stall && (ex_reg_wbaddr === id_rs2_addr) && (ex_reg_wbaddr =/= 0.U) && id_rs2_oen) ||
    (ex_needs_stall && slotB_valid_w && (ex_reg_wbaddr === id_rsB1_addr_w) && (ex_reg_wbaddr =/= 0.U)) ||
    (ex_needs_stall && slotB_valid_w && (ex_reg_wbaddr === id_rsB2_addr_w) && (ex_reg_wbaddr =/= 0.U)) ||
    (ex_reg_is_csr) ||
    fp_load_stall ||
    fp_fma_stall

  full_stall := io.mdu_busy || io.wfi_stall || io.dmaMemStall

  io.ctl.id_stall   := stall
  io.ctl.full_stall := full_stall
  io.ctl.ex_is_wfi  := ex_reg_is_wfi

  when(io.dat.csr_interrupt) {
    cs_reg_br_type := PC_OP.None
  }.elsewhen(full_stall || stall || pipeline_kill_wire || id_kill_w) {
    cs_reg_br_type := PC_OP.None
  }.otherwise {
    cs_reg_br_type := cs_br_type
  }

  io.ctl.if_kill       := if_kill_w
  io.ctl.ex_pc_sel     := pc_sel

  io.ctl.pipeline_kill := pipeline_kill_wire || io.dat.csr_interrupt
  io.ctl.id_kill       := id_kill_w
  io.ctl.op1_sel       := cs_op1_sel
  io.ctl.op2_sel       := cs_op2_sel
  io.ctl.alu_func       := cs_alu_funcc
  io.ctl.wb_sel        := cs_wb_sel
  io.ctl.rf_wen        := cs_rf_wen

  io.ctl.fencei := cs_fencei || fencei_d

  io.ctl.mem_exception := mem_exception
  io.ctl.mem_exception_cause := mem_exception_cause

  io.ctl.mem_val := cs_mem_en
  io.ctl.mem_cmd := cs_mem_cmd
  io.ctl.mem_typ := cs_mem_typ
  dontTouch(pc_sel)

  if (enableFExtension) {
    io.ctl.fp_wen.get  := cs_fp_wen
    io.ctl.fp_ren1.get := cs_fp_ren1
    io.ctl.fp_ren2.get := cs_fp_ren2
    io.ctl.fp_ren3.get := cs_fp_ren3
    io.ctl.fp_fma.get  := cs_fp_fma
    io.ctl.fpu_op.get  := cs_fpu_op
  }

  dec.foreach { b =>
    val inst = io.dat.id_inst
    val opcode = inst(6, 0)
    val funct3 = inst(14, 12)
    val funct7 = inst(31, 25)
    val is_op     = opcode === "b0110011".U
    val is_op_imm = opcode === "b0010011".U

    val is_zbc_w = is_op && (funct7 === "b0000101".U) &&
      (funct3 === "b001".U || funct3 === "b010".U || funct3 === "b011".U)

    val is_zbs_r = is_op && (
      (funct7 === "b0100100".U && funct3 === "b001".U) ||
      (funct7 === "b0100100".U && funct3 === "b101".U) ||
      (funct7 === "b0110100".U && funct3 === "b001".U) ||
      (funct7 === "b0010100".U && funct3 === "b001".U))

    val is_zbs_i = is_op_imm && (
      (funct7 === "b0100100".U && funct3 === "b001".U) ||
      (funct7 === "b0100100".U && funct3 === "b101".U) ||
      (funct7 === "b0110100".U && funct3 === "b001".U) ||
      (funct7 === "b0010100".U && funct3 === "b001".U))

    b.rf_wen    := cs_rf_wen
    b.mem_val   := cs_mem_en
    b.wb_sel    := cs_wb_sel
    b.fencei    := cs_fencei
    b.is_ecall  := cs_is_ecall
    b.is_ebreak := cs_is_ebreak
    b.is_wfi    := cs_is_wfi
    b.illegal   := id_illegal
    b.br_type   := cs_br_type
    b.is_zbc    := is_zbc_w
    b.is_zbs    := is_zbs_r || is_zbs_i

    if (enableFExtension) {
      val is_load_fp  = opcode === "b0000111".U
      val is_store_fp = opcode === "b0100111".U
      val is_op_fp    = opcode === "b1010011".U
      val is_madd     = opcode === "b1000011".U
      val is_msub     = opcode === "b1000111".U
      val is_nmsub    = opcode === "b1001011".U
      val is_nmadd    = opcode === "b1001111".U
      b.is_fp.get := is_load_fp || is_store_fp || is_op_fp ||
                     is_madd || is_msub || is_nmsub || is_nmadd
    }
  }
}
