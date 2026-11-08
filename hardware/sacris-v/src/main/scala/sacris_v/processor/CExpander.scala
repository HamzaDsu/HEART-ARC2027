// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Expands 16-bit compressed instructions to their 32-bit form.

package sacris_v.processor

import chisel3._
import chisel3.util._

class CExpander(enableFExtension: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val in_compressed = Input(UInt(16.W))
    val out_inst      = Output(UInt(32.W))
    val out_illegal   = Output(Bool())
  })

  val inst = io.in_compressed
  val op      = inst(1, 0)
  val funct3  = inst(15, 13)

  def rp(field: UInt): UInt = Cat(1.U(2.W), field)
  val rs1p = rp(inst(9, 7))
  val rs2p = rp(inst(4, 2))
  val rdp  = rp(inst(4, 2))

  val cr_rd  = inst(11, 7)
  val cr_rs2 = inst(6, 2)

  val OP_LOAD     = "h03".U(7.W)
  val OP_LOADFP   = "h07".U(7.W)
  val OP_STORE    = "h23".U(7.W)
  val OP_STOREFP  = "h27".U(7.W)
  val OP_OPIMM    = "h13".U(7.W)
  val OP_OP       = "h33".U(7.W)
  val OP_JAL      = "h6f".U(7.W)
  val OP_JALR     = "h67".U(7.W)
  val OP_BRANCH   = "h63".U(7.W)
  val OP_LUI      = "h37".U(7.W)

  def itype(imm12: UInt, rs1: UInt, funct3: UInt, rd: UInt, opcode: UInt): UInt =
    Cat(imm12(11, 0), rs1(4, 0), funct3(2, 0), rd(4, 0), opcode(6, 0))

  def stype(imm12: UInt, rs2: UInt, rs1: UInt, funct3: UInt, opcode: UInt): UInt =
    Cat(imm12(11, 5), rs2(4, 0), rs1(4, 0), funct3(2, 0), imm12(4, 0), opcode(6, 0))

  def rtype(funct7: UInt, rs2: UInt, rs1: UInt, funct3: UInt, rd: UInt, opcode: UInt): UInt =
    Cat(funct7(6, 0), rs2(4, 0), rs1(4, 0), funct3(2, 0), rd(4, 0), opcode(6, 0))

  def jtype(imm21: UInt, rd: UInt): UInt =
    Cat(imm21(20), imm21(10, 1), imm21(11), imm21(19, 12), rd(4, 0), OP_JAL)

  def btype(imm13: UInt, rs2: UInt, rs1: UInt, funct3: UInt): UInt =
    Cat(imm13(12), imm13(10, 5), rs2(4, 0), rs1(4, 0), funct3(2, 0),
        imm13(4, 1), imm13(11), OP_BRANCH)

  def utype(imm20: UInt, rd: UInt, opcode: UInt): UInt =
    Cat(imm20(19, 0), rd(4, 0), opcode(6, 0))

  val ciw_uimm = Cat(0.U(2.W), inst(10, 7), inst(12, 11), inst(5), inst(6), 0.U(2.W))

  val cls_uimm = Cat(0.U(5.W), inst(5), inst(12, 10), inst(6), 0.U(2.W))

  val ci_simm = Cat(Fill(7, inst(12)), inst(6, 2))

  val ci16_simm = Cat(Fill(2, inst(12)), inst(12), inst(4, 3), inst(5), inst(2), inst(6), 0.U(4.W))

  val ci_lui_imm = Cat(Fill(14, inst(12)), inst(12), inst(6, 2))

  val ci_shamt = Cat(0.U(7.W), inst(6, 2))

  val ci_lwsp_uimm = Cat(0.U(4.W), inst(3, 2), inst(12), inst(6, 4), 0.U(2.W))

  val css_uimm = Cat(0.U(4.W), inst(8, 7), inst(12, 9), 0.U(2.W))

  val cb_off = Cat(Fill(4, inst(12)), inst(12), inst(6, 5), inst(2),
                   inst(11, 10), inst(4, 3), 0.U(1.W))

  val cj_off = Cat(Fill(9, inst(12)), inst(12), inst(8), inst(10, 9),
                   inst(6), inst(7), inst(2), inst(11), inst(5, 3), 0.U(1.W))

  val NOP32 = "h00000013".U(32.W)
  val out_inst    = WireDefault(NOP32)
  val out_illegal = WireDefault(false.B)

  val q0_addi4spn = itype(ciw_uimm, "h02".U(5.W), "b000".U(3.W), rdp, OP_OPIMM)
  val q0_lw       = itype(cls_uimm, rs1p, "b010".U(3.W), rdp, OP_LOAD)
  val q0_sw       = stype(cls_uimm, rs2p, rs1p, "b010".U(3.W), OP_STORE)
  val q0_flw      = itype(cls_uimm, rs1p, "b010".U(3.W), rdp, OP_LOADFP)
  val q0_fsw      = stype(cls_uimm, rs2p, rs1p, "b010".U(3.W), OP_STOREFP)

  val q1_addi    = itype(ci_simm, cr_rd, "b000".U(3.W), cr_rd, OP_OPIMM)
  val q1_jal     = jtype(cj_off, "b00001".U(5.W))
  val q1_li      = itype(ci_simm, "b00000".U(5.W), "b000".U(3.W), cr_rd, OP_OPIMM)
  val q1_addi16sp = itype(ci16_simm, "h02".U(5.W), "b000".U(3.W), "h02".U(5.W), OP_OPIMM)
  val q1_lui     = utype(ci_lui_imm, cr_rd, OP_LUI)

  val q1_srli    = itype(ci_shamt, rs1p, "b101".U(3.W), rs1p, OP_OPIMM)
  val q1_srai    = Cat("b0100000".U(7.W), inst(6, 2), rs1p, "b101".U(3.W), rs1p, OP_OPIMM)
  val q1_andi    = itype(ci_simm, rs1p, "b111".U(3.W), rs1p, OP_OPIMM)

  val q1_sub = rtype("b0100000".U(7.W), rs2p, rs1p, "b000".U(3.W), rs1p, OP_OP)
  val q1_xor = rtype("b0000000".U(7.W), rs2p, rs1p, "b100".U(3.W), rs1p, OP_OP)
  val q1_or  = rtype("b0000000".U(7.W), rs2p, rs1p, "b110".U(3.W), rs1p, OP_OP)
  val q1_and = rtype("b0000000".U(7.W), rs2p, rs1p, "b111".U(3.W), rs1p, OP_OP)

  val q1_j     = jtype(cj_off, "b00000".U(5.W))
  val q1_beqz  = btype(cb_off, "b00000".U(5.W), rs1p, "b000".U(3.W))
  val q1_bnez  = btype(cb_off, "b00000".U(5.W), rs1p, "b001".U(3.W))

  val q2_slli  = Cat("b0000000".U(7.W), inst(6, 2), cr_rd, "b001".U(3.W), cr_rd, OP_OPIMM)
  val q2_lwsp  = itype(ci_lwsp_uimm, "h02".U(5.W), "b010".U(3.W), cr_rd, OP_LOAD)
  val q2_flwsp = itype(ci_lwsp_uimm, "h02".U(5.W), "b010".U(3.W), cr_rd, OP_LOADFP)
  val q2_jr    = itype(0.U(12.W), cr_rd, "b000".U(3.W), "b00000".U(5.W), OP_JALR)
  val q2_mv    = rtype("b0000000".U(7.W), cr_rs2, "b00000".U(5.W), "b000".U(3.W), cr_rd, OP_OP)
  val q2_ebreak = "h00100073".U(32.W)
  val q2_jalr  = itype(0.U(12.W), cr_rd, "b000".U(3.W), "b00001".U(5.W), OP_JALR)
  val q2_add   = rtype("b0000000".U(7.W), cr_rs2, cr_rd, "b000".U(3.W), cr_rd, OP_OP)
  val q2_swsp  = stype(css_uimm, cr_rs2, "h02".U(5.W), "b010".U(3.W), OP_STORE)
  val q2_fswsp = stype(css_uimm, cr_rs2, "h02".U(5.W), "b010".U(3.W), OP_STOREFP)

  switch(op) {
    is("b00".U) {

      switch(funct3) {
        is("b000".U) {

          out_inst    := q0_addi4spn
          out_illegal := (inst(12, 5) === 0.U)
        }
        is("b010".U) {
          out_inst := q0_lw
        }
        is("b011".U) {
          if (enableFExtension) {
            out_inst := q0_flw
          } else {
            out_illegal := true.B
          }
        }
        is("b110".U) {
          out_inst := q0_sw
        }
        is("b111".U) {
          if (enableFExtension) {
            out_inst := q0_fsw
          } else {
            out_illegal := true.B
          }
        }

        is("b001".U) { out_illegal := true.B }
        is("b100".U) { out_illegal := true.B }
        is("b101".U) { out_illegal := true.B }
      }
    }
    is("b01".U) {
      switch(funct3) {
        is("b000".U) {

          out_inst := q1_addi
        }
        is("b001".U) {
          out_inst := q1_jal
        }
        is("b010".U) {

          out_inst := q1_li
        }
        is("b011".U) {

          val nzimm_zero = (inst(12) === 0.U) && (inst(6, 2) === 0.U)
          when(cr_rd === "h02".U) {
            out_inst    := q1_addi16sp
            out_illegal := nzimm_zero
          } .otherwise {
            out_inst    := q1_lui
            out_illegal := nzimm_zero
          }
        }
        is("b100".U) {

          val sub_funct2 = inst(11, 10)
          switch(sub_funct2) {
            is("b00".U) {

              out_inst    := q1_srli
              out_illegal := inst(12).asBool
            }
            is("b01".U) {
              out_inst    := q1_srai
              out_illegal := inst(12).asBool
            }
            is("b10".U) {
              out_inst := q1_andi
            }
            is("b11".U) {

              when(inst(12) === 0.U) {
                switch(inst(6, 5)) {
                  is("b00".U) { out_inst := q1_sub }
                  is("b01".U) { out_inst := q1_xor }
                  is("b10".U) { out_inst := q1_or  }
                  is("b11".U) { out_inst := q1_and }
                }
              } .otherwise {
                out_illegal := true.B
              }
            }
          }
        }
        is("b101".U) {
          out_inst := q1_j
        }
        is("b110".U) {
          out_inst := q1_beqz
        }
        is("b111".U) {
          out_inst := q1_bnez
        }
      }
    }
    is("b10".U) {
      switch(funct3) {
        is("b000".U) {

          out_inst    := q2_slli
          out_illegal := inst(12).asBool
        }
        is("b010".U) {

          out_inst    := q2_lwsp
          out_illegal := (cr_rd === 0.U)
        }
        is("b011".U) {
          if (enableFExtension) {

            out_inst := q2_flwsp
          } else {
            out_illegal := true.B
          }
        }
        is("b100".U) {

          when(inst(12) === 0.U) {
            when(cr_rs2 === 0.U) {

              out_inst    := q2_jr
              out_illegal := (cr_rd === 0.U)
            } .otherwise {

              out_inst := q2_mv
            }
          } .otherwise {
            when((cr_rd === 0.U) && (cr_rs2 === 0.U)) {

              out_inst := q2_ebreak
            } .elsewhen(cr_rs2 === 0.U) {

              out_inst := q2_jalr
            } .otherwise {

              out_inst := q2_add
            }
          }
        }
        is("b110".U) {
          out_inst := q2_swsp
        }
        is("b111".U) {
          if (enableFExtension) {
            out_inst := q2_fswsp
          } else {
            out_illegal := true.B
          }
        }

        is("b001".U) { out_illegal := true.B }
        is("b101".U) { out_illegal := true.B }
      }
    }
    is("b11".U) {

      out_illegal := true.B
    }
  }

  io.out_inst    := out_inst
  io.out_illegal := out_illegal
}
