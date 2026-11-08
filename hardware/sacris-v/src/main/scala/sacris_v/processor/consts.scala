// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Control-signal encodings and constants used by the decoder and the datapath.

package sacris_v.processor

import chisel3._

trait RISCVConstants {

   val NOP: UInt = "h00000013".U(32.W)

   val BUBBLE: UInt = NOP
}

trait ScalarOpConstants {

   val Y: Bool = true.B
   val N: Bool = false.B

   object BrCond extends ChiselEnum {
      val None, EQ, NE, LT, LTU, GE, GEU = Value
   }

   val WB_X:   UInt = 0.asUInt(3.W)
   val WB_ALU: UInt = 0.asUInt(3.W)
   val WB_MEM: UInt = 1.asUInt(3.W)
   val WB_PC4: UInt = 2.asUInt(3.W)
   val WB_CSR: UInt = 3.asUInt(3.W)
   val WB_MDU: UInt = 4.asUInt(3.W)
   val WB_BEXT: UInt = 5.asUInt(3.W)

   val WB_FPU:  UInt = 6.asUInt(3.W)

   object FpuOp extends ChiselEnum {
      val X,
          FSGNJ, FSGNJN, FSGNJX,
          FMIN,  FMAX,
          FEQ,   FLT,    FLE,
          FCLASS,
          FCVT_W_S, FCVT_WU_S,
          FCVT_S_W, FCVT_S_WU,
          FMV_X_W,  FMV_W_X,
          FLW,      FSW,

          FADD, FSUB, FMUL,
          FMADD, FMSUB, FNMSUB, FNMADD,

          FDIV, FSQRT = Value
   }

   object Op1Sel extends ChiselEnum {
      val X, RS1, PC, IMZ = Value
   }

   object Op2Sel extends ChiselEnum {
      val X, RS2, ITYPE, STYPE, SBTYPE, UTYPE, UJTYPE = Value
   }

   object AluOp extends ChiselEnum {
      val X, ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND, COPY_1, COPY_2, MULT, DIV, MULH, MULHSU, MULHU, DIVU, REM, REMU = Value
   }

   object PC_OP extends ChiselEnum {
      val None, NE, EQ, GE, GEU, LT, LTU, J, JR, MRET = Value
   }

   val OEN_0: Bool = false.B
   val OEN_1: Bool = true.B

   val REN_0: Bool = false.B
   val REN_1: Bool = true.B

   val MEN_0: Bool = false.B
   val MEN_1: Bool = true.B
   val MEN_X: Bool = false.B // legacy alias

   object PcSel extends ChiselEnum {
      val Plus4, BrJmp, Jalr, Exc, Mret, ExPc4 = Value
   }

}

object Constants extends
   ScalarOpConstants with
   RISCVConstants
{
}
