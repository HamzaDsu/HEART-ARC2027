// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Single-precision FPU built on Berkeley HardFloat.

package sacris_v.processor

import chisel3._
import chisel3.util._
import hardfloat._
import sacris_v.processor.Constants._

class FPUIo extends Bundle {
   val op   = Input(FpuOp.Type())
   val rs1  = Input(UInt(33.W))
   val rs2  = Input(UInt(33.W))
   val intRs1 = Input(UInt(32.W))
   val rm   = Input(UInt(3.W))

   val recResult   = Output(UInt(33.W))
   val intResult   = Output(UInt(32.W))
   val storeData   = Output(UInt(32.W))
   val exceptionFlags = Output(UInt(5.W))
}

class FPU extends Module {
   val io = IO(new FPUIo)

   val a    = io.rs1
   val b    = io.rs2
   val intA = io.intRs1

   val cmp = Module(new CompareRecFN(8, 24))
   cmp.io.a := a
   cmp.io.b := b

   cmp.io.signaling := (io.op === FpuOp.FLT) || (io.op === FpuOp.FLE)

   val classOut = classifyRecFN(8, 24, a)

   val i2f = Module(new INToRecFN(32, 8, 24))
   i2f.io.signedIn      := (io.op === FpuOp.FCVT_S_W)
   i2f.io.in            := intA
   i2f.io.roundingMode  := io.rm
   i2f.io.detectTininess := consts.tininess_afterRounding

   val f2i = Module(new RecFNToIN(8, 24, 32))
   f2i.io.in           := a
   f2i.io.roundingMode := io.rm
   f2i.io.signedOut    := (io.op === FpuOp.FCVT_W_S)

   val f2i_ie = f2i.io.intExceptionFlags
   val f2i_flags = Cat(f2i_ie(2) | f2i_ie(1), 0.U(3.W), f2i_ie(0))

   val mvwx_rec  = recFNFromFN(8, 24, intA)
   val mvxw_ieee = fNFromRecFN(8, 24, a)

   val signA  = a(32)
   val signB  = b(32)
   val newSign = Mux(io.op === FpuOp.FSGNJN, !signB,
                 Mux(io.op === FpuOp.FSGNJX, signA ^ signB,
                                             signB))
   val sgnjResult = Cat(newSign, a(31, 0))

   val recCanonNaN = recFNFromFN(8, 24, "h7FC00000".U(32.W))

   val aIsNaN = a(31, 29) === "b111".U
   val bIsNaN = b(31, 29) === "b111".U

   val aIsZero = a(31, 29) === "b000".U
   val bIsZero = b(31, 29) === "b000".U

   val minPickA = cmp.io.lt
   val maxPickA = cmp.io.gt
   val bothZero = aIsZero && bIsZero
   val aNegZero = bothZero && a(32)
   val bNegZero = bothZero && b(32)

   val fminResult =
      Mux(aIsNaN && bIsNaN, recCanonNaN,
      Mux(aIsNaN,           b,
      Mux(bIsNaN,           a,
      Mux(bothZero,         Mux(aNegZero, a, b),
                            Mux(minPickA, a, b)))))
   val fmaxResult =
      Mux(aIsNaN && bIsNaN, recCanonNaN,
      Mux(aIsNaN,           b,
      Mux(bIsNaN,           a,
      Mux(bothZero,         Mux(bNegZero, a, b),
                            Mux(maxPickA, a, b)))))

   val sNaN_flags = cmp.io.exceptionFlags

   val recOut = WireDefault(0.U(33.W))
   val intOut = WireDefault(0.U(32.W))
   val flags  = WireDefault(0.U(5.W))
   switch(io.op) {
      is(FpuOp.FEQ)        { intOut := cmp.io.eq;             flags := cmp.io.exceptionFlags }
      is(FpuOp.FLT)        { intOut := cmp.io.lt;             flags := cmp.io.exceptionFlags }
      is(FpuOp.FLE)        { intOut := cmp.io.lt || cmp.io.eq; flags := cmp.io.exceptionFlags }
      is(FpuOp.FCLASS)     { intOut := classOut }
      is(FpuOp.FMV_X_W)    { intOut := mvxw_ieee }
      is(FpuOp.FCVT_W_S)   { intOut := f2i.io.out;             flags := f2i_flags }
      is(FpuOp.FCVT_WU_S)  { intOut := f2i.io.out;             flags := f2i_flags }

      is(FpuOp.FSGNJ)      { recOut := sgnjResult }
      is(FpuOp.FSGNJN)     { recOut := sgnjResult }
      is(FpuOp.FSGNJX)     { recOut := sgnjResult }
      is(FpuOp.FMIN)       { recOut := fminResult;            flags := sNaN_flags }
      is(FpuOp.FMAX)       { recOut := fmaxResult;            flags := sNaN_flags }
      is(FpuOp.FMV_W_X)    { recOut := mvwx_rec }
      is(FpuOp.FCVT_S_W)   { recOut := i2f.io.out;            flags := i2f.io.exceptionFlags }
      is(FpuOp.FCVT_S_WU)  { recOut := i2f.io.out;            flags := i2f.io.exceptionFlags }

   }

   io.recResult      := recOut
   io.intResult      := intOut

   io.storeData      := fNFromRecFN(8, 24, b)
   io.exceptionFlags := flags
}
