package sacris_v.processor

import chisel3._
import chisel3.util._
import sacris_v.common._
import sacris_v.common.{MemCmd, MemType}
import sacris_v.processor.Constants._

class DatToCtlIo(enableCExtension: Boolean = false) extends Bundle()
{
   val id_inst     = Output(UInt(32.W))
   val id_valid    = Output(Bool())
   val ex_br_eq    = Output(Bool())
   val ex_br_lt    = Output(Bool())
   val ex_br_ltu   = Output(Bool())
   val ex_inst_misaligned = Output(Bool())
   val id_cexp_illegal = if (enableCExtension) Some(Output(Bool())) else None
   val mem_ctrl_dmem_val = Output(Bool())
   val mem_data_misaligned = Output(Bool())
   val mem_store = Output(Bool())

   val csr_eret = Output(Bool())
   val csr_interrupt = Output(Bool())

   val id_predict_taken  = Output(Bool())
   val ex_predicted_taken = Output(Bool())
}

class PipelineIo(enableDualIssue: Boolean = false, enableFExtension: Boolean = false, enableCExtension: Boolean = false, exposeSafetyCommit: Boolean = false, exposeGprSnapshot: Boolean = false, exposeResyncRestore: Boolean = false, exposeResyncInflight: Boolean = false, exposeRegfileScrub: Boolean = false, exposeRegfileScrubSelfCheck: Boolean = false, exposeRegfileEcc: Boolean = false, bootRomWait: Boolean = false, exposeRollbackPc: Boolean = false) extends Bundle
{  val safetyCommit = if (exposeSafetyCommit) Some(Output(new SafetyCommitIO(enableDualIssue, enableFExtension, exposeGprSnapshot, withInflight = exposeResyncInflight, exposeRegfileScrub = exposeRegfileScrub, exposeRegfileScrubSelfCheck = exposeRegfileScrubSelfCheck, exposeRegfileEcc = exposeRegfileEcc, withRollbackPc = exposeRollbackPc))) else None; val rbRestore = if (exposeGprSnapshot) Some(Input(new RollbackRestoreIO(CSR.archStateWidth(enableFExtension), exposeResyncRestore, enableFExtension && exposeResyncRestore, withInflight = exposeResyncInflight))) else None
   val imem = new BootRomPortIO; val imemWaitReq = if (bootRomWait) Some(Input(Bool())) else None; val bootRomFlush = if (bootRomWait) Some(Output(Bool())) else None

   val imem_data_hi = if (enableDualIssue) Some(Input(UInt(32.W))) else None

   val slotA_dec = if (enableDualIssue) Some(Input(new CtlDecodeBundle(enableFExtension))) else None

   val slotB_valid_out  = if (enableDualIssue) Some(Output(Bool()))      else None
   val slotB_rs1_addr   = if (enableDualIssue) Some(Output(UInt(5.W)))   else None
   val slotB_rs2_addr   = if (enableDualIssue) Some(Output(UInt(5.W)))   else None
   val dmem = new MemPortIO(32)
   val ctl  = Flipped(new CtlToDatIo(enableFExtension))
   val dat  = new DatToCtlIo(enableCExtension)
   val hartid = Input(UInt())
   val sysCnt = Input(UInt(64.W))
   val reset_vector = Input(UInt())
   val timerInterrupt = Input(Bool())
   val softwareInterrupt = Input(Bool())
   val externalInterrupt = Input(Bool())
   val faultDetectInterrupt = Input(Bool())
   val mdu_busy = Output(Bool())
   val wfi_stall = Output(Bool())

   val exMemValid = Output(Bool())
   val exMemAddr  = Output(UInt(32.W))
}

class Pipeline(
   enableMExtension: Boolean = true,
   enableBExtension: Boolean = true,
   divSteps: Int = 1,
   regOut: Boolean = true,
   withLateLoadForward: Boolean = false,
   dynPredBranch: Boolean = false,
   bhtEntries: Int = 256,
   enableDualIssue: Boolean = false,
   enableCExtension: Boolean = false,
   enableIssueObservability: Boolean = false,

   enableIntraPairForward: Boolean = true, enableBranchObserve: Boolean = false, enableFExtension: Boolean = false, fpDivStepsPerCycle: Int = 1, enableCExtensionCycleObserve: Boolean = false, exposeSafetyCommit: Boolean = false, exposeGprSnapshot: Boolean = false, exposeResyncRestore: Boolean = false, exposeResyncInflight: Boolean = false, exposeRegfileScrub: Boolean = false, exposeRegfileScrubSelfCheck: Boolean = false, regfileScrubSelfCheckSweeps: Int = 256, regfileScrubSelfCheckDefeat: Boolean = false, exposeRegfileEcc: Boolean = false, bootRomWait: Boolean = false, stallAddrFromReg: Boolean = false, dmemAddrFromAdder: Boolean = false, mduSplitPartials: Boolean = false, minus4FromReg: Boolean = false, exposeRollbackPc: Boolean = false
) extends Module
{
   val io = IO(new PipelineIo(enableDualIssue, enableFExtension, enableCExtension, exposeSafetyCommit, exposeGprSnapshot, exposeResyncRestore, exposeResyncInflight, exposeRegfileScrub, exposeRegfileScrubSelfCheck, exposeRegfileEcc, bootRomWait, exposeRollbackPc))
   io := DontCare

   val timerInterruptReg = RegInit(false.B)
   val timerInterruptOneCycle = Wire(Bool())
   timerInterruptReg := io.timerInterrupt
   timerInterruptOneCycle := io.timerInterrupt && !timerInterruptReg

   val faultDetectInterruptReg = RegInit(false.B)
   val faultDetectInterruptOneCycle = Wire(Bool())
   faultDetectInterruptReg := io.faultDetectInterrupt
   faultDetectInterruptOneCycle := io.faultDetectInterrupt && !faultDetectInterruptReg

   private val csr = Module(new CSR(enableFExtension, exposeArchState = exposeSafetyCommit, exposeRestore = exposeGprSnapshot))

   val timer_irq_masked = io.timerInterrupt & csr.io.o.mie(7)
   val sw_irq_masked    = io.softwareInterrupt & csr.io.o.mie(3)
   val ext_irq_masked   = io.externalInterrupt & csr.io.o.mie(11)
   val interrupt = (faultDetectInterruptOneCycle | ext_irq_masked | sw_irq_masked | timer_irq_masked) & csr.io.o.mstatus(3).asBool
   io.dat.csr_interrupt := interrupt

   val interrupt_pending = (io.timerInterrupt & csr.io.o.mie(7)) | (io.softwareInterrupt & csr.io.o.mie(3)) | (io.externalInterrupt & csr.io.o.mie(11)) | io.faultDetectInterrupt
   io.wfi_stall := io.ctl.ex_is_wfi && !interrupt_pending && !io.ctl.mem_exception

   val if_reg_pc             = RegInit(io.reset_vector)

   val id_valid              = RegInit(false.B)
   val id_inst               = WireDefault(BUBBLE)
   val id_pc                 = RegInit(0.asUInt(32.W))

   val ex_reg_valid          = RegInit(false.B)
   val ex_reg_inst           = RegInit(BUBBLE)
   val ex_reg_pc             = RegInit(0.asUInt(32.W))
   val ex_reg_wbaddr         = Reg(UInt(5.W))
   val ex_reg_rs1_addr       = Reg(UInt(5.W))
   val ex_reg_rs2_addr       = Reg(UInt(5.W))
   val ex_reg_op1_data       = Reg(UInt(32.W))
   val ex_reg_op2_data       = Reg(UInt(32.W))
   val ex_reg_rs2_data       = Reg(UInt(32.W))
   val ex_reg_ctrl_alu_func   = Reg(AluOp.Type())
   val ex_reg_ctrl_wb_sel    = Reg(UInt())
   val ex_reg_ctrl_rf_wen    = RegInit(false.B)
   val ex_reg_ctrl_mem_val   = RegInit(false.B)
   val ex_reg_ctrl_mem_cmd   = RegInit(MemCmd.Read)
   val ex_reg_ctrl_mem_typ   = RegInit(MemType.X)
   val ex_reg_predicted_taken = RegInit(false.B)

   val mem_reg_valid         = RegInit(false.B)
   val mem_reg_pc            = Reg(UInt(32.W))
   val mem_reg_inst          = Reg(UInt(32.W))
   val mem_reg_alu_out       = Reg(Bits())
   val mem_reg_wbaddr        = Reg(UInt())
   val mem_reg_rs1_addr      = Reg(UInt())
   val mem_reg_rs2_addr      = Reg(UInt())
   val mem_reg_op1_data      = Reg(UInt(32.W))
   val mem_reg_op2_data      = Reg(UInt(32.W))
   val mem_reg_ctrl_rf_wen   = RegInit(false.B)
   val mem_reg_ctrl_mem_val  = RegInit(false.B)
   val mem_reg_ctrl_mem_cmd  = RegInit(MemCmd.Read)
   val mem_reg_ctrl_mem_typ  = RegInit(MemType.X)
   val mem_reg_ctrl_wb_sel   = Reg(UInt())

   val mem_reg_data_misaligned = RegInit(false.B)

   val wb_reg_valid          = RegInit(false.B)
   val wb_reg_wbaddr         = Reg(UInt())
   val wb_reg_wbdata         = Reg(UInt(32.W))
   val wb_reg_ctrl_rf_wen    = RegInit(false.B)
   val dmem_data_signExtended = Wire(UInt(32.W))

   val mem_tval_data_ma = Wire(UInt(32.W))
   val mem_tval_inst_ma = Wire(UInt(32.W))

   val if_pc_next          = Wire(UInt(32.W))
   val ex_brjmp_target     = Wire(UInt(32.W))
   val ex_jump_reg_target  = Wire(UInt(32.W))
   val exception_target    = Wire(UInt(32.W))

   val id_stall_prev = RegInit(false.B)
   when(!io.ctl.full_stall) {
      id_stall_prev := Mux(io.ctl.pipeline_kill, false.B, io.ctl.id_stall)
   }

   val exception_pc_override = if (enableDualIssue) io.ctl.pipeline_kill else false.B
   val if_reg_pc_buffer = RegInit(io.reset_vector)
   when(!io.ctl.full_stall) {
      if_reg_pc_buffer := if_reg_pc
   }

   val if_pc_plus4  = if_reg_pc + 4.asUInt(32.W)
   val if_pc_minus4 = if_reg_pc - 4.asUInt(32.W); val if_reg_pc_m4 = if (minus4FromReg) Some(RegInit((io.reset_vector - 4.U)(31, 0))) else None

   val if_pc_plus8     = if (enableDualIssue) Some(if_reg_pc + 8.asUInt(32.W)) else None
   val paired_dispatch = if (enableDualIssue) Some(Wire(Bool())) else None
   paired_dispatch.foreach { _ := false.B }

   if_pc_next := exception_target

   val pv_4_val = WireDefault(false.B)

   val id_predict_taken  = Wire(Bool())
   val id_predict_target = Wire(UInt(32.W))
   when(!io.ctl.full_stall && (!id_stall_prev || interrupt || exception_pc_override) && !(io.imemWaitReq.getOrElse(false.B) && !interrupt && !io.ctl.pipeline_kill && io.ctl.ex_pc_sel === PcSel.Plus4 && !id_predict_taken)) {
      if_reg_pc := if_pc_next; if_reg_pc_m4.foreach(_ := (if_pc_next - 4.U)(31, 0))
   }

   val ex_pc_plus4_early = (ex_reg_pc + 4.U)(31, 0)

   switch(io.ctl.ex_pc_sel) {
      is(PcSel.Plus4) {

         when(id_predict_taken) {
            if_pc_next := id_predict_target
         }.otherwise {
            if_pc_next := (if (enableDualIssue)
               Mux(paired_dispatch.get, if_pc_plus8.get, if_pc_plus4)
             else
               if_pc_plus4)
            pv_4_val := true.B
         }
      }
      is(PcSel.BrJmp) { if_pc_next := ex_brjmp_target }
      is(PcSel.Jalr)  { if_pc_next := ex_jump_reg_target }
      is(PcSel.Exc)   { if_pc_next := csr.io.o.mtvec }
      is(PcSel.Mret)  { if_pc_next := csr.io.o.mepc }
      is(PcSel.ExPc4) { if_pc_next := ex_pc_plus4_early }
   }

   when(io.ctl.fencei && (io.ctl.ex_pc_sel === PcSel.Plus4) &&
     !io.ctl.id_stall && !io.ctl.pipeline_kill) {
      if_pc_next := if_reg_pc
   }

   val if_id_bubble = RegInit(false.B)

   io.imem.addr := if_reg_pc; io.bootRomFlush.foreach(_ := io.ctl.pipeline_kill || io.ctl.if_kill || interrupt)

   when(io.ctl.full_stall) {

      io.imem.addr := id_pc
   }
   .elsewhen((io.ctl.pipeline_kill || io.ctl.if_kill || interrupt)) {

      id_valid     := false.B
      io.imem.addr := if_reg_pc
      if_id_bubble := true.B
      id_pc        := id_pc
   }
     .elsewhen(!io.ctl.id_stall && !id_stall_prev && !io.imemWaitReq.getOrElse(false.B)) {

        id_valid     := true.B
        io.imem.addr := (if (enableDualIssue)
           Mux(paired_dispatch.get, if_reg_pc + 4.U, if_reg_pc)
         else
           if_reg_pc)
        if_id_bubble := false.B
        id_pc        := (if (enableDualIssue)
           Mux(paired_dispatch.get, if_reg_pc + 4.U, if_reg_pc)
         else
           if_reg_pc)
     }
     .elsewhen(io.ctl.id_stall && !id_stall_prev && !io.imemWaitReq.getOrElse(false.B)) {

        id_valid     := true.B
        io.imem.addr := (if (stallAddrFromReg) id_pc else if (minus4FromReg) if_reg_pc_m4.get else if_pc_minus4)
        if_id_bubble := false.B
        id_pc        := id_pc
     }
     .elsewhen(!io.ctl.id_stall && id_stall_prev && !io.imemWaitReq.getOrElse(false.B)) {

        id_valid     := true.B
        io.imem.addr := (if (stallAddrFromReg) if_reg_pc_buffer else if (minus4FromReg) if_reg_pc_m4.get else if_pc_minus4)
        if_id_bubble := false.B
        id_pc        := if_reg_pc_buffer
     }.otherwise {

        id_valid     := false.B
        io.imem.addr := if_reg_pc
        if_id_bubble := true.B
        id_pc        := id_pc
     }

   val fetch_data_64 = if (enableDualIssue) Some(Wire(UInt(64.W))) else None
   val splitter      = if (enableDualIssue) Some(Module(new FetchSplitter)) else None
   if (enableDualIssue) {
      fetch_data_64.get               := Cat(io.imem_data_hi.get, io.imem.data)
      splitter.get.io.fetch_data      := fetch_data_64.get
      splitter.get.io.fetch_valid     := !if_id_bubble
      splitter.get.io.fetch_pc        := id_pc
   }
   when(if_id_bubble) {
      id_inst := BUBBLE
   }.otherwise{
      if (enableDualIssue) {
         id_inst := splitter.get.io.slotA_inst
      } else {
         id_inst := io.imem.data
      }
   }

   val id_rs1_addr = id_inst(19, 15)
   val id_rs2_addr = id_inst(24, 20)
   val id_wbaddr   = id_inst(11, 7)

   val rf_rsB1_addr     = Wire(UInt(5.W))
   val rf_rsB2_addr     = Wire(UInt(5.W))
   val rf_rsB1_data     = Wire(UInt(32.W))
   val rf_rsB2_data     = Wire(UInt(32.W))
   val rf_wp1_waddr     = Wire(UInt(5.W))
   val rf_wp1_wdata     = Wire(UInt(32.W))
   val rf_wp1_wen       = Wire(Bool())
   val rf_wp0_suppress  = Wire(Bool()); val scrubIdx = if (exposeRegfileScrub) Some(RegInit(0.U(5.W))) else None; val scrubScanData = if (exposeRegfileScrub) Some(Wire(UInt(32.W))) else None; val rfEccCorrectable = if (exposeRegfileEcc) Some(Wire(Bool())) else None; val rfEccUncorrectable = if (exposeRegfileEcc) Some(Wire(Bool())) else None

   val (rf_rs1_data, rf_rs2_data) = if (enableDualIssue) {
      val rf = Module(new RegisterFileDI)
      rf.io.rsA1_addr := id_rs1_addr
      rf.io.rsA2_addr := id_rs2_addr
      rf.io.rsB1_addr := rf_rsB1_addr
      rf.io.rsB2_addr := rf_rsB2_addr
      rf_rsB1_data    := rf.io.rsB1_data
      rf_rsB2_data    := rf.io.rsB2_data
      rf.io.wp0_waddr := wb_reg_wbaddr
      rf.io.wp0_wdata := wb_reg_wbdata
      rf.io.wp0_wen   := wb_reg_ctrl_rf_wen && !rf_wp0_suppress
      rf.io.wp1_waddr := rf_wp1_waddr
      rf.io.wp1_wdata := rf_wp1_wdata
      rf.io.wp1_wen   := rf_wp1_wen

      rf_rsB1_addr    := 0.U
      rf_rsB2_addr    := 0.U
      rf_wp1_waddr    := 0.U
      rf_wp1_wdata    := 0.U
      rf_wp1_wen      := false.B
      rf_wp0_suppress := false.B
      (rf.io.rsA1_data, rf.io.rsA2_data)
   } else {
      val regfile = Module(new RegisterFile(exposeResyncRestore, withScrubScan = exposeRegfileScrub, withEcc = exposeRegfileEcc))
      regfile.io.rs1_addr := id_rs1_addr
      regfile.io.rs2_addr := id_rs2_addr
      regfile.io.waddr    := (if (exposeGprSnapshot) Mux(io.rbRestore.get.gprEn, io.rbRestore.get.gprAddr, wb_reg_wbaddr) else wb_reg_wbaddr)
      regfile.io.wdata    := (if (exposeGprSnapshot) Mux(io.rbRestore.get.gprEn, io.rbRestore.get.gprData, wb_reg_wbdata) else wb_reg_wbdata)
      regfile.io.wen      := (if (exposeGprSnapshot) (io.rbRestore.get.gprEn || wb_reg_ctrl_rf_wen) else wb_reg_ctrl_rf_wen); if (exposeResyncRestore) { regfile.io.parEn.get := io.rbRestore.get.parEn.get; regfile.io.parData.get := io.rbRestore.get.parData.get }; if (exposeRegfileScrub) { regfile.io.scrubAddr.get := scrubIdx.get; scrubScanData.get := regfile.io.scrubData.get }; if (exposeRegfileEcc) { rfEccCorrectable.get := regfile.io.eccCorrectable.get; rfEccUncorrectable.get := regfile.io.eccUncorrectable.get }

      rf_rsB1_addr    := 0.U
      rf_rsB2_addr    := 0.U
      rf_rsB1_data    := 0.U
      rf_rsB2_data    := 0.U
      rf_wp1_waddr    := 0.U
      rf_wp1_wdata    := 0.U
      rf_wp1_wen      := false.B
      rf_wp0_suppress := false.B
      (regfile.io.rs1_data, regfile.io.rs2_data)
   }

   val ex_alu_out = Wire(UInt(32.W))
   val mem_wbdata = Wire(UInt(32.W))

   val id_op1_data = Wire(UInt(32.W))
   val id_op2_data = Wire(UInt(32.W))
   val id_rs2_data = Wire(UInt(32.W))

   val imm_itype  = id_inst(31, 20)
   val imm_stype  = Cat(id_inst(31, 25), id_inst(11, 7))
   val imm_sbtype = Cat(id_inst(31), id_inst(7), id_inst(30, 25), id_inst(11, 8))
   val imm_utype  = id_inst(31, 12)
   val imm_ujtype = Cat(id_inst(31), id_inst(19, 12), id_inst(20), id_inst(30, 21))

   val imm_z = Cat(Fill(27, 0.U), id_inst(19, 15))

   val imm_itype_sext  = Cat(Fill(20, imm_itype(11)), imm_itype)
   val imm_stype_sext  = Cat(Fill(20, imm_stype(11)), imm_stype)
   val imm_sbtype_sext = Cat(Fill(19, imm_sbtype(11)), imm_sbtype, 0.U)
   val imm_utype_sext  = Cat(imm_utype, Fill(12, 0.U))
   val imm_ujtype_sext = Cat(Fill(11, imm_ujtype(19)), imm_ujtype, 0.U)

   val dec_alu_op2 = WireDefault(0.U(32.W))
   switch(io.ctl.op2_sel) {
      is(Op2Sel.RS2)    { dec_alu_op2 := rf_rs2_data }
      is(Op2Sel.ITYPE)  { dec_alu_op2 := imm_itype_sext }
      is(Op2Sel.STYPE)  { dec_alu_op2 := imm_stype_sext }
      is(Op2Sel.SBTYPE) { dec_alu_op2 := imm_sbtype_sext }
      is(Op2Sel.UTYPE)  { dec_alu_op2 := imm_utype_sext }
      is(Op2Sel.UJTYPE) { dec_alu_op2 := imm_ujtype_sext }

   }

   def fwdMatch(wen: Bool, wbaddr: UInt, rs: UInt): Bool =
      wen && (rs =/= 0.U) && (wbaddr === rs)

   val fwd_rs1_exe = fwdMatch(ex_reg_ctrl_rf_wen, ex_reg_wbaddr, id_rs1_addr)
   val fwd_rs1_mem = fwdMatch(mem_reg_ctrl_rf_wen, mem_reg_wbaddr, id_rs1_addr)
   val fwd_rs1_wb  = fwdMatch(wb_reg_ctrl_rf_wen,  wb_reg_wbaddr,  id_rs1_addr)

   val g_rs1_exe = if (withLateLoadForward) (fwd_rs1_exe && !(ex_reg_ctrl_wb_sel === WB_MEM)) else fwd_rs1_exe
   val g_rs1_mem = fwd_rs1_mem && !fwd_rs1_exe
   val g_rs1_wb  = fwd_rs1_wb  && !fwd_rs1_exe && !fwd_rs1_mem

   dontTouch(g_rs1_exe)
   dontTouch(g_rs1_mem)
   dontTouch(g_rs1_wb)

   assert(!(g_rs1_exe && g_rs1_mem))
   assert(!(g_rs1_exe && g_rs1_wb))
   assert(!(g_rs1_mem && g_rs1_wb))

   val rs1_val = if (enableDualIssue) Wire(UInt(32.W))
                 else Mux(g_rs1_exe, ex_alu_out,
                      Mux(g_rs1_mem, mem_wbdata,
                         Mux(g_rs1_wb,  wb_reg_wbdata,
                            rf_rs1_data)))
   if (enableDualIssue) {
      rs1_val := Mux(g_rs1_exe, ex_alu_out,
                 Mux(g_rs1_mem, mem_wbdata,
                    Mux(g_rs1_wb,  wb_reg_wbdata,
                       rf_rs1_data)))
   }

   val rs1_val_for_jalr = if (enableDualIssue) Some(Wire(UInt(32.W))) else None
   val jalr_rs1_needs_exe_fwd = if (enableDualIssue) Some(Wire(Bool())) else None

   val op1_val = WireDefault(rs1_val)
   switch(io.ctl.op1_sel) {
      is(Op1Sel.PC)  { op1_val := id_pc }
      is(Op1Sel.IMZ) { op1_val := imm_z }

   }

   id_op1_data := op1_val

   val fwd_rs2_exe = fwdMatch(ex_reg_ctrl_rf_wen, ex_reg_wbaddr, id_rs2_addr)
   val fwd_rs2_mem = fwdMatch(mem_reg_ctrl_rf_wen, mem_reg_wbaddr, id_rs2_addr)
   val fwd_rs2_wb  = fwdMatch(wb_reg_ctrl_rf_wen,  wb_reg_wbaddr,  id_rs2_addr)

   val g_rs2_exe = if (withLateLoadForward) (fwd_rs2_exe && !(ex_reg_ctrl_wb_sel === WB_MEM)) else fwd_rs2_exe
   val g_rs2_mem = fwd_rs2_mem && !fwd_rs2_exe
   val g_rs2_wb  = fwd_rs2_wb  && !fwd_rs2_exe && !fwd_rs2_mem

   dontTouch(g_rs2_exe)
   dontTouch(g_rs2_mem)
   dontTouch(g_rs2_wb)

   assert(!(g_rs2_exe && g_rs2_mem))
   assert(!(g_rs2_exe && g_rs2_wb))
   assert(!(g_rs2_mem && g_rs2_wb))

   val ex_reg_was_compressed  = if (enableCExtension) Some(RegInit(false.B))     else None
   val ex_reg_inst_low16      = if (enableCExtension) Some(RegInit(0.U(16.W)))   else None
   val mem_reg_was_compressed = if (enableCExtension) Some(RegInit(false.B))     else None
   val mem_reg_inst_low16     = if (enableCExtension) Some(RegInit(0.U(16.W)))   else None

   if (enableCExtension) {
      val if_residual_hw     = RegInit(0.U(16.W))
      val if_residual_valid  = RegInit(false.B)
      val if_last_step_was_2 = RegInit(false.B)

      val cexp = Module(new CExpander(enableFExtension))

      val pc_bit1   = id_pc(1)
      val res_valid = if_residual_valid
      val low16     = io.imem.data(15, 0)
      val high16    = io.imem.data(31, 16)
      val high_is_32bit = high16(1, 0) === "b11".U

      val if_inst_candidate = Wire(UInt(32.W))
      if_inst_candidate := io.imem.data
      when(res_valid) {
         if_inst_candidate := Cat(low16, if_residual_hw)
      } .elsewhen(pc_bit1) {
         when(high_is_32bit) {
            if_inst_candidate := DontCare
         } .otherwise {
            if_inst_candidate := Cat(0.U(16.W), high16)
         }
      }

      val if_id_fresh = id_valid && !if_id_bubble && !id_stall_prev && !io.ctl.full_stall

      val cexp_bubble_w = pc_bit1 && !res_valid && high_is_32bit && if_id_fresh

      val if_advance_for_id = !io.ctl.full_stall &&
                              !io.ctl.pipeline_kill && !interrupt &&
                              !io.ctl.id_stall

      val candidate_is_compressed = if_inst_candidate(1, 0) =/= "b11".U

      val if_is_2byte_step = cexp_bubble_w || res_valid || candidate_is_compressed || if_reg_pc(1)

      val if_pc_step       = Mux(if_is_2byte_step, 2.U(32.W), 4.U(32.W))
      val if_pc_plus_step  = if_reg_pc + if_pc_step
      val if_pc_minus_step = if_reg_pc - Mux(if_last_step_was_2, 2.U(32.W), 4.U(32.W))

      cexp.io.in_compressed := if_inst_candidate(15, 0)
      val if_inst_expanded = Mux(candidate_is_compressed, cexp.io.out_inst, if_inst_candidate)

      io.dat.id_cexp_illegal.get := candidate_is_compressed && cexp.io.out_illegal

      val fenceI_holding = io.ctl.fencei && (io.ctl.ex_pc_sel === PcSel.Plus4) &&
                           !io.ctl.id_stall && !io.ctl.pipeline_kill

      when(io.ctl.ex_pc_sel === PcSel.Plus4 && !id_predict_taken && !fenceI_holding) {
         if_pc_next := if_pc_plus_step
      }

      when(io.ctl.ex_pc_sel === PcSel.ExPc4 && ex_reg_was_compressed.get) {
         if_pc_next := (ex_reg_pc + 2.U)(31, 0)
      }

      when(cexp_bubble_w) {
         if_residual_hw    := high16
         if_residual_valid := true.B
         id_pc             := id_pc
         io.imem.addr      := id_pc + 2.U
      }

      when(id_valid && if_advance_for_id && !io.ctl.if_kill && !cexp_bubble_w) {
         when(res_valid) {

            io.imem.addr := id_pc + 4.U
            id_pc        := id_pc + 4.U
         } .elsewhen(pc_bit1) {

            io.imem.addr := id_pc + 2.U
            id_pc        := id_pc + 2.U
         } .otherwise {

            when(candidate_is_compressed) {
               io.imem.addr := id_pc + 2.U
               id_pc        := id_pc + 2.U
            } .otherwise {
               io.imem.addr := id_pc + 4.U
               id_pc        := id_pc + 4.U
            }
         }
      }

      when(if_advance_for_id && res_valid && !cexp_bubble_w) {
         if_residual_valid := false.B
      }

      when(io.ctl.ex_pc_sel =/= PcSel.Plus4) {
         if_residual_valid := false.B
      }

      when(fenceI_holding) {
         if_residual_valid := false.B
      }

      when(io.ctl.if_kill || io.ctl.pipeline_kill || interrupt) {
         if_residual_valid := false.B
      }

      when(!io.ctl.full_stall && (!id_stall_prev || interrupt ||
           (if (enableDualIssue) io.ctl.pipeline_kill else false.B))) {
         when(io.ctl.ex_pc_sel === PcSel.Plus4) {
            if_last_step_was_2 := if_is_2byte_step
         } .otherwise {
            if_last_step_was_2 := false.B
         }
      }

      when(!if_id_bubble && !cexp_bubble_w) {
         id_inst := if_inst_expanded
      }
      when(cexp_bubble_w) {
         id_inst := BUBBLE
      }

      io.dat.id_valid := id_valid && !cexp_bubble_w

      when(io.ctl.id_stall && !id_stall_prev && !io.ctl.full_stall &&
           !(io.ctl.pipeline_kill || io.ctl.if_kill || interrupt) &&
           !cexp_bubble_w) {
         io.imem.addr := id_pc
      }

      val ex_bubble_local = io.ctl.id_stall || io.ctl.pipeline_kill || io.ctl.id_kill
      val ex_adv_local    = !ex_bubble_local

      when(io.ctl.full_stall) {

      } .elsewhen(ex_bubble_local || cexp_bubble_w) {

         ex_reg_was_compressed.get := false.B
      } .elsewhen(ex_adv_local) {
         ex_reg_was_compressed.get := candidate_is_compressed
         ex_reg_inst_low16.get     := if_inst_candidate(15, 0)
      }

      when(io.ctl.full_stall) {

      } .elsewhen(io.ctl.pipeline_kill) {
         mem_reg_was_compressed.get := false.B
      } .otherwise {
         mem_reg_was_compressed.get := ex_reg_was_compressed.get
         mem_reg_inst_low16.get     := ex_reg_inst_low16.get
      }

      if (enableCExtensionCycleObserve) {
         def satInc(c: UInt, ev: Bool): UInt = {
            val maxed = c.andR
            Mux(ev && !maxed, c + 1.U, c)
         }

         val ev_cexp_bubble_w = cexp_bubble_w

         val ev_residual_valid = if_residual_valid

         val ev_id_inst_diff =
            (!if_id_bubble && !cexp_bubble_w && (if_inst_expanded =/= io.imem.data)) ||
            (cexp_bubble_w && (io.imem.data =/= BUBBLE))

         val ev_if_pc_next_plus4_fires =
            (io.ctl.ex_pc_sel === PcSel.Plus4) && !id_predict_taken && !fenceI_holding
         val ev_if_pc_next_expc4_fires =
            (io.ctl.ex_pc_sel === PcSel.ExPc4) && ex_reg_was_compressed.get
         val ev_if_pc_next_diff =
            (ev_if_pc_next_plus4_fires && if_is_2byte_step) ||
            ev_if_pc_next_expc4_fires

         val ev_id_pc_normal_fires =
            id_valid && if_advance_for_id && !io.ctl.if_kill && !cexp_bubble_w
         val ev_id_pc_normal_value = Mux(res_valid, id_pc + 4.U,
                                      Mux(pc_bit1,
                                         id_pc + 2.U,
                                         Mux(candidate_is_compressed, id_pc + 2.U, id_pc + 4.U)))
         val ev_id_pc_diff =
            (ev_id_pc_normal_fires && (ev_id_pc_normal_value =/= if_reg_pc)) ||
            (cexp_bubble_w && (id_pc =/= if_reg_pc))

         val ev_imem_addr_normal_value = Mux(res_valid, id_pc + 4.U,
                                          Mux(pc_bit1,
                                             id_pc + 2.U,
                                             Mux(candidate_is_compressed, id_pc + 2.U, id_pc + 4.U)))
         val ev_imem_addr_stall_edge_fires =
            ((io.ctl.id_stall && !id_stall_prev) || (!io.ctl.id_stall && id_stall_prev)) &&
            !io.ctl.full_stall &&
            !(io.ctl.pipeline_kill || io.ctl.if_kill || interrupt)
         val ev_imem_addr_diff =
            (ev_id_pc_normal_fires && (ev_imem_addr_normal_value =/= if_reg_pc)) ||
            (cexp_bubble_w && ((id_pc + 2.U) =/= if_reg_pc)) ||
            (ev_imem_addr_stall_edge_fires && if_last_step_was_2)

         val ev_if_id_bubble_diff = false.B

         val ev_id_valid_diff = id_valid && cexp_bubble_w

         val cnt_cexp_bubble_w        = RegInit(0.U(64.W))
         val cnt_residual_valid       = RegInit(0.U(64.W))
         val cnt_id_inst_diff         = RegInit(0.U(64.W))
         val cnt_if_pc_next_diff      = RegInit(0.U(64.W))
         val cnt_id_pc_diff           = RegInit(0.U(64.W))
         val cnt_imem_addr_diff       = RegInit(0.U(64.W))
         val cnt_if_id_bubble_diff    = RegInit(0.U(64.W))
         val cnt_id_valid_diff        = RegInit(0.U(64.W))

         cnt_cexp_bubble_w     := satInc(cnt_cexp_bubble_w,     ev_cexp_bubble_w)
         cnt_residual_valid    := satInc(cnt_residual_valid,    ev_residual_valid)
         cnt_id_inst_diff      := satInc(cnt_id_inst_diff,      ev_id_inst_diff)
         cnt_if_pc_next_diff   := satInc(cnt_if_pc_next_diff,   ev_if_pc_next_diff)
         cnt_id_pc_diff        := satInc(cnt_id_pc_diff,        ev_id_pc_diff)
         cnt_imem_addr_diff    := satInc(cnt_imem_addr_diff,    ev_imem_addr_diff)
         cnt_if_id_bubble_diff := satInc(cnt_if_id_bubble_diff, ev_if_id_bubble_diff)
         cnt_id_valid_diff     := satInc(cnt_id_valid_diff,     ev_id_valid_diff)

         def mkFirstPc(ev: Bool): (UInt, Bool) = {
            val pcReg  = RegInit(0.U(32.W))
            val capReg = RegInit(false.B)
            when(ev && !capReg) {
               pcReg  := id_pc
               capReg := true.B
            }
            (pcReg, capReg)
         }
         val (firstpc_cexp_bubble_w,     cap_cexp_bubble_w)     = mkFirstPc(ev_cexp_bubble_w)
         val (firstpc_residual_valid,    cap_residual_valid)    = mkFirstPc(ev_residual_valid)
         val (firstpc_id_inst_diff,      cap_id_inst_diff)      = mkFirstPc(ev_id_inst_diff)
         val (firstpc_if_pc_next_diff,   cap_if_pc_next_diff)   = mkFirstPc(ev_if_pc_next_diff)
         val (firstpc_id_pc_diff,        cap_id_pc_diff)        = mkFirstPc(ev_id_pc_diff)
         val (firstpc_imem_addr_diff,    cap_imem_addr_diff)    = mkFirstPc(ev_imem_addr_diff)
         val (firstpc_if_id_bubble_diff, cap_if_id_bubble_diff) = mkFirstPc(ev_if_id_bubble_diff)
         val (firstpc_id_valid_diff,     cap_id_valid_diff)     = mkFirstPc(ev_id_valid_diff)

         val cext_observe_cnt_cexp_bubble_w     = WireDefault(cnt_cexp_bubble_w)
         val cext_observe_cnt_residual_valid    = WireDefault(cnt_residual_valid)
         val cext_observe_cnt_id_inst_diff      = WireDefault(cnt_id_inst_diff)
         val cext_observe_cnt_if_pc_next_diff   = WireDefault(cnt_if_pc_next_diff)
         val cext_observe_cnt_id_pc_diff        = WireDefault(cnt_id_pc_diff)
         val cext_observe_cnt_imem_addr_diff    = WireDefault(cnt_imem_addr_diff)
         val cext_observe_cnt_if_id_bubble_diff = WireDefault(cnt_if_id_bubble_diff)
         val cext_observe_cnt_id_valid_diff     = WireDefault(cnt_id_valid_diff)

         val cext_observe_firstpc_cexp_bubble_w     = WireDefault(firstpc_cexp_bubble_w)
         val cext_observe_firstpc_residual_valid    = WireDefault(firstpc_residual_valid)
         val cext_observe_firstpc_id_inst_diff      = WireDefault(firstpc_id_inst_diff)
         val cext_observe_firstpc_if_pc_next_diff   = WireDefault(firstpc_if_pc_next_diff)
         val cext_observe_firstpc_id_pc_diff        = WireDefault(firstpc_id_pc_diff)
         val cext_observe_firstpc_imem_addr_diff    = WireDefault(firstpc_imem_addr_diff)
         val cext_observe_firstpc_if_id_bubble_diff = WireDefault(firstpc_if_id_bubble_diff)
         val cext_observe_firstpc_id_valid_diff     = WireDefault(firstpc_id_valid_diff)

         val cext_observe_cap_cexp_bubble_w     = WireDefault(cap_cexp_bubble_w)
         val cext_observe_cap_residual_valid    = WireDefault(cap_residual_valid)
         val cext_observe_cap_id_inst_diff      = WireDefault(cap_id_inst_diff)
         val cext_observe_cap_if_pc_next_diff   = WireDefault(cap_if_pc_next_diff)
         val cext_observe_cap_id_pc_diff        = WireDefault(cap_id_pc_diff)
         val cext_observe_cap_imem_addr_diff    = WireDefault(cap_imem_addr_diff)
         val cext_observe_cap_if_id_bubble_diff = WireDefault(cap_if_id_bubble_diff)
         val cext_observe_cap_id_valid_diff     = WireDefault(cap_id_valid_diff)

         dontTouch(cext_observe_cnt_cexp_bubble_w)
         dontTouch(cext_observe_cnt_residual_valid)
         dontTouch(cext_observe_cnt_id_inst_diff)
         dontTouch(cext_observe_cnt_if_pc_next_diff)
         dontTouch(cext_observe_cnt_id_pc_diff)
         dontTouch(cext_observe_cnt_imem_addr_diff)
         dontTouch(cext_observe_cnt_if_id_bubble_diff)
         dontTouch(cext_observe_cnt_id_valid_diff)

         dontTouch(cext_observe_firstpc_cexp_bubble_w)
         dontTouch(cext_observe_firstpc_residual_valid)
         dontTouch(cext_observe_firstpc_id_inst_diff)
         dontTouch(cext_observe_firstpc_if_pc_next_diff)
         dontTouch(cext_observe_firstpc_id_pc_diff)
         dontTouch(cext_observe_firstpc_imem_addr_diff)
         dontTouch(cext_observe_firstpc_if_id_bubble_diff)
         dontTouch(cext_observe_firstpc_id_valid_diff)

         dontTouch(cext_observe_cap_cexp_bubble_w)
         dontTouch(cext_observe_cap_residual_valid)
         dontTouch(cext_observe_cap_id_inst_diff)
         dontTouch(cext_observe_cap_if_pc_next_diff)
         dontTouch(cext_observe_cap_id_pc_diff)
         dontTouch(cext_observe_cap_imem_addr_diff)
         dontTouch(cext_observe_cap_if_id_bubble_diff)
         dontTouch(cext_observe_cap_id_valid_diff)
      }
   }

   val rs2_val = if (enableDualIssue) Wire(UInt(32.W))
                 else Mux(g_rs2_exe, ex_alu_out,
                      Mux(g_rs2_mem, mem_wbdata,
                         Mux(g_rs2_wb,  wb_reg_wbdata,
                            rf_rs2_data)))
   if (enableDualIssue) {
      rs2_val := Mux(g_rs2_exe, ex_alu_out,
                 Mux(g_rs2_mem, mem_wbdata,
                    Mux(g_rs2_wb,  wb_reg_wbdata,
                       rf_rs2_data)))
   }

   id_rs2_data := rs2_val

   val op2_imm = WireDefault(0.U(32.W))
   switch(io.ctl.op2_sel) {
      is(Op2Sel.RS2)    { op2_imm := rf_rs2_data }
      is(Op2Sel.ITYPE)  { op2_imm := imm_itype_sext }
      is(Op2Sel.STYPE)  { op2_imm := imm_stype_sext }
      is(Op2Sel.SBTYPE) { op2_imm := imm_sbtype_sext }
      is(Op2Sel.UTYPE)  { op2_imm := imm_utype_sext }
      is(Op2Sel.UJTYPE) { op2_imm := imm_ujtype_sext }

   }

   id_op2_data := Mux(io.ctl.op2_sel === Op2Sel.RS2, rs2_val, op2_imm)

   val id_opcode    = id_inst(6, 0)
   val id_is_branch = (id_opcode === "b1100011".U) && id_valid
   val id_is_jal    = (id_opcode === "b1101111".U) && id_valid
   val id_is_jalr   = (id_opcode === "b1100111".U) && id_valid

   val id_branch_target = (id_pc + imm_sbtype_sext)(31, 0)

   val id_jal_target    = (id_pc + imm_ujtype_sext)(31, 0)

   val id_jalr_target_rs1 = rs1_val_for_jalr.getOrElse(rs1_val)
   val id_jalr_target   = ((id_jalr_target_rs1 + imm_itype_sext) & ~1.U(32.W))(31, 0)

   val id_branch_backward = imm_sbtype_sext(31)

   val bhtOpt: Option[(Mem[UInt], UInt)] = if (dynPredBranch) {
      val bht = Mem(bhtEntries, UInt(2.W))
      val bht_index = if (enableCExtension) id_pc(log2Ceil(bhtEntries), 1) else id_pc(log2Ceil(bhtEntries) + 1, 2)
      val bht_counter = bht.read(bht_index)
      Some((bht, bht_counter))
   } else None

   val id_branch_predict_taken: Bool = bhtOpt match {
      case Some((_, counter)) => counter(1)
      case None               => id_branch_backward
   }

   id_predict_target := Mux(id_is_branch, id_branch_target,
                         Mux(id_is_jal, id_jal_target,
                           id_jalr_target))

   val id_jalr_predict_block = jalr_rs1_needs_exe_fwd.getOrElse(false.B)
   id_predict_taken := !io.ctl.id_stall && !id_stall_prev && !io.ctl.full_stall &&
      !io.ctl.pipeline_kill && !io.ctl.id_kill && !io.ctl.fencei &&
      ((id_is_branch && id_branch_predict_taken) || id_is_jal ||
       (id_is_jalr && !id_jalr_predict_block))

   io.dat.id_predict_taken := id_predict_taken

   val ex_reg_fp_wen        = if (enableFExtension) Some(RegInit(false.B))         else None
   val ex_reg_fp_op1_data   = if (enableFExtension) Some(Reg(UInt(33.W)))          else None
   val ex_reg_fp_op2_data   = if (enableFExtension) Some(Reg(UInt(33.W)))          else None

   val ex_reg_fp_op3_data   = if (enableFExtension) Some(Reg(UInt(33.W)))          else None
   val ex_reg_fpu_op        = if (enableFExtension) Some(RegInit(FpuOp.X))         else None

   val ex_reg_fp_fma        = if (enableFExtension) Some(RegInit(false.B))         else None
   val mem_reg_fp_wen       = if (enableFExtension) Some(RegInit(false.B))         else None
   val mem_reg_fp_wbdata    = if (enableFExtension) Some(Reg(UInt(33.W)))          else None
   val mem_reg_fp_is_load   = if (enableFExtension) Some(RegInit(false.B))         else None

   val mem_reg_fp_fma       = if (enableFExtension) Some(RegInit(false.B))         else None
   val wb_reg_fp_wen        = if (enableFExtension) Some(RegInit(false.B))         else None
   val wb_reg_fp_wbdata     = if (enableFExtension) Some(Reg(UInt(33.W)))          else None
   val ex_reg_fp_excFlags   = if (enableFExtension) Some(RegInit(0.U(5.W)))        else None
   val mem_reg_fp_excFlags  = if (enableFExtension) Some(RegInit(0.U(5.W)))        else None
   val wb_reg_fp_excFlags   = if (enableFExtension) Some(RegInit(0.U(5.W)))        else None
   val ex_reg_fp_storeData  = if (enableFExtension) Some(Reg(UInt(32.W)))          else None

   val ex_fp_result         = if (enableFExtension) Some(Wire(UInt(33.W)))         else None
   val ex_fp_int_result     = if (enableFExtension) Some(Wire(UInt(32.W)))         else None
   val ex_fp_excFlags       = if (enableFExtension) Some(Wire(UInt(5.W)))          else None
   val ex_fp_storeData      = if (enableFExtension) Some(Wire(UInt(32.W)))         else None

   val mem_fp_load_data     = if (enableFExtension) Some(Wire(UInt(33.W)))         else None

   val mem_fp_fma_data      = if (enableFExtension) Some(Wire(UInt(33.W)))         else None
   val mem_fp_fma_flags     = if (enableFExtension) Some(Wire(UInt(5.W)))          else None

   val fpu_busy             = if (enableFExtension) Some(Wire(Bool()))             else None
   fpu_busy.foreach(_ := false.B)
   ex_fp_result   .foreach(_ := 0.U)
   ex_fp_int_result.foreach(_ := 0.U)
   ex_fp_excFlags .foreach(_ := 0.U)
   ex_fp_storeData.foreach(_ := 0.U)
   mem_fp_load_data.foreach(_ := 0.U)
   mem_fp_fma_data .foreach(_ := 0.U)
   mem_fp_fma_flags.foreach(_ := 0.U)

   val (fp_rs1_data, fp_rs2_data, fp_rs3_data) = if (enableFExtension) {
      val fpRf = Module(new FPRegisterFile(33, exposeResyncRestore))
      fpRf.io.rs1_addr := id_rs1_addr
      fpRf.io.rs2_addr := id_rs2_addr
      fpRf.io.rs3_addr := id_inst(31, 27)
      fpRf.io.waddr    := wb_reg_wbaddr
      fpRf.io.wdata    := wb_reg_fp_wbdata.get
      fpRf.io.wen      := wb_reg_fp_wen.get

      if (exposeResyncRestore) {
         fpRf.io.parEn.get   := io.rbRestore.get.fpParEn.get
         fpRf.io.parData.get := io.rbRestore.get.fpParData.get
      }

      val ex_inst_is_fp_load = ex_reg_fp_wen.get && ex_reg_ctrl_mem_val &&
                                (ex_reg_ctrl_mem_cmd === MemCmd.Read)
      val ex_is_single_cycle_fp = ex_reg_fp_wen.get && !ex_inst_is_fp_load

      val fp_fwd_ex_data  = ex_fp_result.get

      val fp_fwd_mem_data = Mux(mem_reg_fp_is_load.get, mem_fp_load_data.get,
                            Mux(mem_reg_fp_fma.get,     mem_fp_fma_data.get,
                                                        mem_reg_fp_wbdata.get))

      def fpFwdRead(addr: UInt, regOut: UInt): UInt = {
         val fwd_ex  = ex_is_single_cycle_fp && (ex_reg_wbaddr === addr)
         val fwd_mem = mem_reg_fp_wen.get && (mem_reg_wbaddr === addr)
         Mux(fwd_ex,  fp_fwd_ex_data,
         Mux(fwd_mem, fp_fwd_mem_data,
                      regOut))
      }

      val rs1 = fpFwdRead(id_rs1_addr,        fpRf.io.rs1_data)
      val rs2 = fpFwdRead(id_rs2_addr,        fpRf.io.rs2_data)
      val rs3 = fpFwdRead(id_inst(31, 27),    fpRf.io.rs3_data)
      (rs1, rs2, rs3)
   } else {
      (0.U(33.W), 0.U(33.W), 0.U(33.W))
   }

   def exWriteBubble(): Unit = {
      ex_reg_valid           := false.B
      ex_reg_inst            := BUBBLE
      ex_reg_wbaddr          := 0.U
      ex_reg_ctrl_rf_wen     := false.B
      ex_reg_ctrl_mem_val    := false.B
      ex_reg_ctrl_mem_cmd    := MemCmd.Read
      ex_reg_predicted_taken := false.B
   }

   val ex_bubble = io.ctl.id_stall || io.ctl.pipeline_kill || io.ctl.id_kill
   val ex_adv    = !io.ctl.id_stall && !io.ctl.pipeline_kill && !io.ctl.id_kill

   when(io.ctl.full_stall) {

   }.elsewhen(ex_bubble) {
      exWriteBubble()
      if (enableFExtension) {
         ex_reg_fp_wen.get := false.B
         ex_reg_fpu_op.get := FpuOp.X
         ex_reg_fp_fma.get := false.B
      }
   }.elsewhen(ex_adv) {
      ex_reg_pc            := id_pc
      ex_reg_rs1_addr      := id_rs1_addr
      ex_reg_rs2_addr      := id_rs2_addr
      ex_reg_op1_data      := id_op1_data
      ex_reg_op2_data      := id_op2_data
      ex_reg_rs2_data      := id_rs2_data
      ex_reg_ctrl_alu_func  := io.ctl.alu_func
      ex_reg_ctrl_wb_sel   := io.ctl.wb_sel
      ex_reg_valid         := id_valid
      ex_reg_inst          := id_inst
      ex_reg_wbaddr        := id_wbaddr
      ex_reg_ctrl_rf_wen   := io.ctl.rf_wen
      ex_reg_ctrl_mem_val    := io.ctl.mem_val
      ex_reg_ctrl_mem_cmd    := io.ctl.mem_cmd
      ex_reg_ctrl_mem_typ    := io.ctl.mem_typ
      ex_reg_predicted_taken := id_predict_taken
      if (enableFExtension) {
         ex_reg_fp_wen.get      := io.ctl.fp_wen.get
         ex_reg_fp_op1_data.get := fp_rs1_data
         ex_reg_fp_op2_data.get := fp_rs2_data
         ex_reg_fp_op3_data.get := fp_rs3_data
         ex_reg_fpu_op.get      := io.ctl.fpu_op.get
         ex_reg_fp_fma.get      := io.ctl.fp_fma.get
      }
   }

   bhtOpt.foreach { case (bht, bht_counter) =>
      val ex_reg_bht_counter = Reg(UInt(2.W))
      val ex_reg_is_cond_branch = RegInit(false.B)

      when(io.ctl.full_stall) {

      }.elsewhen(ex_bubble) {
         ex_reg_bht_counter    := 0.U
         ex_reg_is_cond_branch := false.B
      }.elsewhen(ex_adv) {
         ex_reg_bht_counter    := bht_counter
         ex_reg_is_cond_branch := id_is_branch
      }

      val ex_actual_taken = (io.ctl.ex_pc_sel === PcSel.BrJmp) ||
         (ex_reg_predicted_taken && io.ctl.ex_pc_sel =/= PcSel.ExPc4)

      val old_ctr = ex_reg_bht_counter
      val new_ctr = Mux(ex_actual_taken,
         Mux(old_ctr === 3.U, 3.U, old_ctr + 1.U),
         Mux(old_ctr === 0.U, 0.U, old_ctr - 1.U))

      val bht_update_en = ex_reg_is_cond_branch && ex_reg_valid &&
         !io.ctl.full_stall && !io.ctl.pipeline_kill
      when(bht_update_en) {
         bht.write(if (enableCExtension) ex_reg_pc(log2Ceil(bhtEntries), 1) else ex_reg_pc(log2Ceil(bhtEntries) + 1, 2), new_ctr)
      }
   }

   val alu_module = Module(new ALU())

   val ex_alu_op1 = if (withLateLoadForward) WireDefault(ex_reg_op1_data.asUInt) else ex_reg_op1_data.asUInt
   val ex_alu_op2 = if (withLateLoadForward) WireDefault(ex_reg_op2_data.asUInt) else ex_reg_op2_data.asUInt

   alu_module.io.op1_data := ex_alu_op1
   alu_module.io.op2_data := ex_alu_op2
   alu_module.io.alu_func := ex_reg_ctrl_alu_func

   val ex_adder_out = (ex_alu_op1 + ex_alu_op2)(32 - 1, 0)

   val bext_result = Wire(UInt(32.W))
   val ex_is_bext_op = WireDefault(false.B)
   bext_result := 0.U

   if (enableBExtension) {
      val bext_unit = Module(new bext.BExtALU())
      bext_unit.io.rs1    := ex_alu_op1
      bext_unit.io.rs2    := ex_alu_op2
      bext_unit.io.funct3 := ex_reg_inst(14, 12)
      bext_unit.io.funct7 := ex_reg_inst(31, 25)
      bext_unit.io.rs2sel := ex_reg_inst(24, 20)
      bext_unit.io.itype  := (ex_reg_inst(6, 0) === "b0010011".U)
      bext_result         := bext_unit.io.out.result
      ex_is_bext_op       := (ex_reg_ctrl_wb_sel === WB_BEXT)
   }

   if (enableMExtension) {
      val mdu_unit = Module(new mdu.RV32M_Radix4_MDU(divSteps, regOut, mduSplitPartials))

      val mdu_op = WireDefault(0.U(3.W))
      switch(ex_reg_ctrl_alu_func) {
         is(AluOp.MULT)   { mdu_op := 0.U }
         is(AluOp.MULH)   { mdu_op := 1.U }
         is(AluOp.MULHSU) { mdu_op := 2.U }
         is(AluOp.MULHU)  { mdu_op := 3.U }
         is(AluOp.DIV)    { mdu_op := 4.U }
         is(AluOp.DIVU)   { mdu_op := 5.U }
         is(AluOp.REM)    { mdu_op := 6.U }
         is(AluOp.REMU)   { mdu_op := 7.U }
      }

      val ex_is_mdu_op = ex_reg_valid && (ex_reg_ctrl_wb_sel === WB_MDU)

      val mdu_running = RegInit(false.B)

      when(ex_is_mdu_op && !mdu_running && !io.ctl.pipeline_kill) {
         mdu_running := true.B
      }
      when(mdu_unit.io.respValid || io.ctl.pipeline_kill) {
         mdu_running := false.B
      }

      val mdu_accepting = ex_is_mdu_op && !mdu_running && !io.ctl.pipeline_kill
      mdu_unit.io.reqValid := mdu_accepting
      mdu_unit.io.op       := mdu_op
      mdu_unit.io.rs1      := ex_alu_op1
      mdu_unit.io.rs2      := ex_alu_op2

      io.mdu_busy := mdu_accepting || mdu_unit.io.stall || fpu_busy.getOrElse(false.B)

      ex_alu_out := Mux(ex_is_bext_op, bext_result,
                     Mux(mdu_unit.io.respValid, mdu_unit.io.result, alu_module.io.out))
   } else {

      io.mdu_busy := fpu_busy.getOrElse(false.B)
      ex_alu_out  := Mux(ex_is_bext_op, bext_result, alu_module.io.out)
   }

   if (enableFExtension) {
      val fpu = Module(new FPU)
      fpu.io.op     := ex_reg_fpu_op.get
      fpu.io.rs1    := ex_reg_fp_op1_data.get
      fpu.io.rs2    := ex_reg_fp_op2_data.get
      fpu.io.intRs1 := ex_reg_op1_data

      val inst_rm    = ex_reg_inst(14, 12)
      val dynamic_rm = csr.io.frm_out.get
      val fpu_rm     = Mux(inst_rm === "b111".U, dynamic_rm, inst_rm)
      fpu.io.rm     := fpu_rm

      ex_fp_result.get     := fpu.io.recResult
      ex_fp_int_result.get := fpu.io.intResult
      ex_fp_excFlags.get   := fpu.io.exceptionFlags
      ex_fp_storeData.get  := fpu.io.storeData

      val op = ex_reg_fpu_op.get
      val isIntRdFp =
         (op === FpuOp.FEQ) ||
         (op === FpuOp.FLT) ||
         (op === FpuOp.FLE) ||
         (op === FpuOp.FCLASS) ||
         (op === FpuOp.FMV_X_W) ||
         (op === FpuOp.FCVT_W_S) ||
         (op === FpuOp.FCVT_WU_S)
      when(isIntRdFp) {
         ex_alu_out := fpu.io.intResult
      }

      val fma = Module(new MulAddRecFNPipe(2, 8, 24))
      val recoded_one      = hardfloat.recFNFromFN(8, 24, "h3f800000".U(32.W))
      val recoded_neg_zero = hardfloat.recFNFromFN(8, 24, "h80000000".U(32.W))

      val fpuOp = ex_reg_fpu_op.get
      val isFadd   = fpuOp === FpuOp.FADD
      val isFsub   = fpuOp === FpuOp.FSUB
      val isFmul   = fpuOp === FpuOp.FMUL
      val isFmsub  = fpuOp === FpuOp.FMSUB
      val isFnmsub = fpuOp === FpuOp.FNMSUB
      val isFnmadd = fpuOp === FpuOp.FNMADD

      val fmaA = ex_reg_fp_op1_data.get
      val fmaB = Mux(isFadd || isFsub, recoded_one, ex_reg_fp_op2_data.get)
      val fmaC = Mux(isFadd || isFsub, ex_reg_fp_op2_data.get,
                 Mux(isFmul,           recoded_neg_zero,
                                        ex_reg_fp_op3_data.get))

      val fmaOp = Cat(isFnmsub || isFnmadd,
                      isFsub || isFmsub || isFnmadd)

      fma.io.op             := fmaOp
      fma.io.a              := fmaA
      fma.io.b              := fmaB
      fma.io.c              := fmaC
      fma.io.roundingMode   := fpu_rm
      fma.io.detectTininess := hardfloat.consts.tininess_afterRounding

      mem_fp_fma_data .get := fma.io.out
      mem_fp_fma_flags.get := fma.io.exceptionFlags

      val divOptions = if (fpDivStepsPerCycle == 2) 16 else 0
      val divSqrt = Module(new hardfloat.DivSqrtRecFN_small(8, 24, divOptions))

      val ex_is_div = ex_reg_valid && (
         (ex_reg_fpu_op.get === FpuOp.FDIV) ||
         (ex_reg_fpu_op.get === FpuOp.FSQRT))
      val div_running = RegInit(false.B)
      val div_accepting = ex_is_div && !div_running && !io.ctl.pipeline_kill

      divSqrt.io.inValid        := div_accepting
      divSqrt.io.sqrtOp         := (ex_reg_fpu_op.get === FpuOp.FSQRT)
      divSqrt.io.a              := ex_reg_fp_op1_data.get
      divSqrt.io.b              := ex_reg_fp_op2_data.get
      divSqrt.io.roundingMode   := fpu_rm
      divSqrt.io.detectTininess := hardfloat.consts.tininess_afterRounding

      val divOutValid = divSqrt.io.outValid_div || divSqrt.io.outValid_sqrt
      when(div_accepting) { div_running := true.B }
      when(divOutValid || io.ctl.pipeline_kill) { div_running := false.B }

      fpu_busy.get := div_accepting || !divSqrt.io.inReady

      when(ex_is_div) {
         ex_fp_result.get   := divSqrt.io.out
         ex_fp_excFlags.get := divSqrt.io.exceptionFlags
      }
   }

   val brjmp_offset = ex_reg_op2_data
   ex_brjmp_target     := ex_reg_pc + brjmp_offset
   ex_jump_reg_target  := ex_adder_out & ~1.U(32.W)

   val ex_is_jalr_inst   = ex_reg_valid && (ex_reg_inst(6, 0) === "b1100111".U)
   val ex_is_jal_inst    = ex_reg_valid && (ex_reg_inst(6, 0) === "b1101111".U)
   val ex_is_branch_inst = ex_reg_valid && (ex_reg_inst(6, 0) === "b1100011".U)

   val ex_branch_taken = (io.ctl.ex_pc_sel === PcSel.BrJmp) ||
     (ex_is_branch_inst && ex_reg_predicted_taken && io.ctl.ex_pc_sel =/= PcSel.ExPc4)

   io.dat.ex_inst_misaligned :=
     ((if (enableCExtension) ex_brjmp_target(0) else ex_brjmp_target(1, 0).orR) && (ex_branch_taken || ex_is_jal_inst)) ||
     ((if (enableCExtension) ex_jump_reg_target(0) else ex_jump_reg_target(1, 0).orR) && ex_is_jalr_inst)

   val ex_is_brjmp_type = ex_is_branch_inst || ex_is_jal_inst
   mem_tval_inst_ma := RegNext(Mux(ex_is_brjmp_type, ex_brjmp_target, ex_jump_reg_target))

   val ex_effective_addr = if (dmemAddrFromAdder) ex_adder_out else alu_module.io.out

   val ex_data_misaligned = WireDefault(false.B)
   when(ex_reg_ctrl_mem_val) {
      switch(ex_reg_ctrl_mem_typ) {
         is(MemType.B)  { ex_data_misaligned := false.B }
         is(MemType.BU) { ex_data_misaligned := false.B }
         is(MemType.H)  { ex_data_misaligned := ex_effective_addr(0) }
         is(MemType.HU) { ex_data_misaligned := ex_effective_addr(0) }
         is(MemType.W)  { ex_data_misaligned := ex_effective_addr(1, 0).orR }
         is(MemType.WU) { ex_data_misaligned := ex_effective_addr(1, 0).orR }
      }
   }

   val ex_pc_plus4 = if (enableCExtension)
      (ex_reg_pc + Mux(ex_reg_was_compressed.get, 2.U, 4.U))(32 - 1, 0)
    else
      (ex_reg_pc + 4.U)(32 - 1, 0)
   when(io.ctl.full_stall) {

   }.elsewhen(io.ctl.pipeline_kill) {
      mem_reg_valid           := false.B
      mem_reg_inst            := BUBBLE
      mem_reg_ctrl_rf_wen     := false.B
      mem_reg_ctrl_mem_val    := false.B
      mem_reg_data_misaligned := false.B
      if (enableFExtension) {
         mem_reg_fp_wen.get     := false.B
         mem_reg_fp_is_load.get := false.B
         mem_reg_fp_fma.get     := false.B
         mem_reg_fp_excFlags.get := 0.U
      }
   }.otherwise {
      mem_reg_valid           := ex_reg_valid
      mem_reg_pc              := ex_reg_pc
      mem_reg_inst            := ex_reg_inst
      mem_reg_alu_out         := Mux((ex_reg_ctrl_wb_sel === WB_PC4), ex_pc_plus4, ex_alu_out)
      mem_reg_wbaddr          := ex_reg_wbaddr
      mem_reg_rs1_addr        := ex_reg_rs1_addr
      mem_reg_rs2_addr        := ex_reg_rs2_addr
      mem_reg_op1_data        := ex_reg_op1_data
      mem_reg_op2_data        := ex_reg_op2_data
      mem_reg_ctrl_rf_wen     := ex_reg_ctrl_rf_wen
      mem_reg_ctrl_mem_val    := ex_reg_ctrl_mem_val
      mem_reg_ctrl_mem_cmd    := ex_reg_ctrl_mem_cmd
      mem_reg_ctrl_mem_typ    := ex_reg_ctrl_mem_typ
      mem_reg_ctrl_wb_sel     := ex_reg_ctrl_wb_sel

      mem_reg_data_misaligned := ex_data_misaligned
      if (enableFExtension) {
         mem_reg_fp_wen.get      := ex_reg_fp_wen.get
         mem_reg_fp_wbdata.get   := ex_fp_result.get

         mem_reg_fp_is_load.get  := ex_reg_fp_wen.get && ex_reg_ctrl_mem_val &&
                                    (ex_reg_ctrl_mem_cmd === MemCmd.Read)

         mem_reg_fp_fma.get      := ex_reg_fp_fma.get
         mem_reg_fp_excFlags.get := ex_fp_excFlags.get
      }
   }

   csr.io.sysCnt0 := io.sysCnt

   csr.io.instruction := mem_reg_inst
   csr.io.rs1 := mem_reg_alu_out

   val mepc_candidate = Mux(ex_reg_valid, ex_reg_pc,
                          Mux(id_valid, id_pc,
                            if_reg_pc))
   csr.io.interrupt_pc := mepc_candidate

   csr.io.exception_pc := mem_reg_pc

   csr.io.syncException      := io.ctl.mem_exception
   csr.io.syncExceptionCause := io.ctl.mem_exception_cause

   csr.io.mtval_value := MuxCase(0.U(32.W), Seq(
     (io.ctl.mem_exception_cause === Causes.illegal_instruction.U) -> mem_reg_inst,
     (io.ctl.mem_exception_cause === Causes.misaligned_fetch.U)    -> mem_tval_inst_ma,
     (io.ctl.mem_exception_cause === Causes.misaligned_load.U)     -> mem_tval_data_ma,
     (io.ctl.mem_exception_cause === Causes.misaligned_store.U)    -> mem_tval_data_ma,
     (io.ctl.mem_exception_cause === Causes.breakpoint.U)          -> mem_reg_pc
   ))

   if (enableCExtension) {
      when(io.ctl.mem_exception_cause === Causes.illegal_instruction.U &&
           mem_reg_was_compressed.get) {
         csr.io.mtval_value := Cat(0.U(16.W), mem_reg_inst_low16.get)
      }
   }

   csr.io.mret := mem_reg_valid && (mem_reg_inst === "h30200073".U)

   csr.io.mip_mtip := io.timerInterrupt
   csr.io.mip_msip := io.softwareInterrupt
   csr.io.mip_meip := io.externalInterrupt

   csr.io.instRetire := wb_reg_valid

   if (enableFExtension) {
      csr.io.fp_excFlags.get       := wb_reg_fp_excFlags.get
      csr.io.fp_excFlags_valid.get := wb_reg_valid && wb_reg_fp_excFlags.get.orR
   }

   when(csr.io.o.mstatus(3) === 1.U && !io.ctl.full_stall) {
      csr.io.timerInterrupt    := io.timerInterrupt && csr.io.o.mie(7)
      csr.io.softwareInterrupt := io.softwareInterrupt && csr.io.o.mie(3)
      csr.io.externalInterrupt := io.externalInterrupt && csr.io.o.mie(11)
      csr.io.faultInterrupt    := faultDetectInterruptOneCycle
   }.otherwise{
      csr.io.timerInterrupt    := false.B
      csr.io.softwareInterrupt := false.B
      csr.io.externalInterrupt := false.B
      csr.io.faultInterrupt    := false.B
   }

   io.dat.mem_data_misaligned := mem_reg_data_misaligned && mem_reg_ctrl_mem_val
   io.dat.mem_store := mem_reg_ctrl_mem_cmd === MemCmd.Write
   mem_tval_data_ma := mem_reg_alu_out.asUInt

   when (mem_reg_ctrl_mem_typ === MemType.B) {
      dmem_data_signExtended := Cat(Fill(24, io.dmem.data(7)), io.dmem.data(7, 0))
   }.elsewhen(mem_reg_ctrl_mem_typ === MemType.H) {
      dmem_data_signExtended := Cat(Fill(16, io.dmem.data(15)), io.dmem.data(15, 0))
   }.otherwise{
      dmem_data_signExtended := io.dmem.data
   }

   mem_wbdata := MuxCase(mem_reg_alu_out, Array(
      (mem_reg_ctrl_wb_sel === WB_ALU) -> mem_reg_alu_out,
      (mem_reg_ctrl_wb_sel === WB_PC4) -> mem_reg_alu_out,
      (mem_reg_ctrl_wb_sel === WB_MEM) -> dmem_data_signExtended,
      (mem_reg_ctrl_wb_sel === WB_CSR) -> csr.io.out,
      (mem_reg_ctrl_wb_sel === WB_MDU) -> mem_reg_alu_out,
      (mem_reg_ctrl_wb_sel === WB_BEXT) -> mem_reg_alu_out
   ).toIndexedSeq)

   exception_target := 0.U
   when (!io.ctl.full_stall)
   {
      wb_reg_valid         := mem_reg_valid && !io.ctl.mem_exception
      wb_reg_wbaddr        := mem_reg_wbaddr
      wb_reg_wbdata        := mem_wbdata
      wb_reg_ctrl_rf_wen   := Mux(io.ctl.mem_exception, false.B, mem_reg_ctrl_rf_wen)
      if (enableFExtension) {

         val fp_wb_data = Mux(mem_reg_fp_is_load.get, mem_fp_load_data.get,
                          Mux(mem_reg_fp_fma.get,     mem_fp_fma_data.get,
                                                      mem_reg_fp_wbdata.get))

         val fp_wb_flags = Mux(mem_reg_fp_fma.get,
                               mem_fp_fma_flags.get,
                               mem_reg_fp_excFlags.get)
         wb_reg_fp_wbdata.get   := fp_wb_data
         wb_reg_fp_wen.get      := Mux(io.ctl.mem_exception, false.B, mem_reg_fp_wen.get) &&
                                   mem_reg_valid
         wb_reg_fp_excFlags.get := Mux(io.ctl.mem_exception, 0.U, fp_wb_flags)
      }
   }
     .otherwise
     {
        wb_reg_valid         := false.B
        wb_reg_ctrl_rf_wen   := false.B
        if (enableFExtension) {
           wb_reg_fp_wen.get    := false.B
           wb_reg_fp_excFlags.get := 0.U
        }
     }

   private val enableRetireTrace =
     sys.env.get("SACRIS_V_RETIRE_TRACE").exists(v => v != "0" && v.nonEmpty)

   if (enableRetireTrace) {
      val wb_commit_pulse = WireInit(false.B)
      wb_commit_pulse := wb_reg_ctrl_rf_wen && wb_reg_valid
      dontTouch(wb_commit_pulse)
   }

   io.safetyCommit.foreach { s =>
      s.wp0_wen   := wb_reg_ctrl_rf_wen && !rf_wp0_suppress
      s.wp0_waddr := wb_reg_wbaddr
      s.wp0_wdata := wb_reg_wbdata
      s.wb_valid  := wb_reg_valid
      s.csrState  := csr.io.archState.get

      s.mem_pc.foreach(_ := mem_reg_pc)
      s.mem_valid.foreach(_ := mem_reg_valid && !io.ctl.full_stall)
      if (enableDualIssue) {
         s.wp1_wen.get   := rf_wp1_wen
         s.wp1_waddr.get := rf_wp1_waddr
         s.wp1_wdata.get := rf_wp1_wdata
      }
      if (enableFExtension) {
         s.fp_wen.get   := wb_reg_fp_wen.get
         s.fp_waddr.get := wb_reg_wbaddr
         s.fp_wdata.get := wb_reg_fp_wbdata.get

         s.fpu_busy.get := fpu_busy.get
      }

      s.gprs.foreach { snap =>
         val gprMirror = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
         when(wb_reg_ctrl_rf_wen && (wb_reg_wbaddr =/= 0.U)) {
            gprMirror(wb_reg_wbaddr) := wb_reg_wbdata
         }

         io.rbRestore.foreach { rb =>
            when(rb.gprEn && (rb.gprAddr =/= 0.U)) { gprMirror(rb.gprAddr) := rb.gprData }

            rb.parEn.foreach { pe =>
               when(pe) { for (i <- 1 until 32) { gprMirror(i.U) := rb.parData.get(i) } }
            }
         }
         snap := gprMirror

         if (exposeRegfileScrub) {
            scrubIdx.get := scrubIdx.get + 1.U
            if (exposeRegfileScrubSelfCheck) {

               val stEpoch = RegInit(0.U(log2Ceil(regfileScrubSelfCheckSweeps).W))
               when(scrubIdx.get === 31.U) {
                  stEpoch := Mux(stEpoch === (regfileScrubSelfCheckSweeps - 1).U, 0.U, stEpoch + 1.U)
               }
               val stActive    = stEpoch === 0.U
               val scanOperand = Mux(stActive, scrubScanData.get ^ UIntToOH(scrubIdx.get, 32), scrubScanData.get)
               val incoh       = scanOperand =/= gprMirror(scrubIdx.get)
               val rawIncoh    = scrubScanData.get =/= gprMirror(scrubIdx.get)

               val incohEff    = if (regfileScrubSelfCheckDefeat) false.B else incoh
               val rawIncohEff = if (regfileScrubSelfCheckDefeat) false.B else rawIncoh
               val stFail      = RegInit(false.B)

               when(stActive && !incohEff && !rawIncohEff) { stFail := true.B }
               s.scrubStFail.get := stFail
               s.scrubIncoh.get  := Mux(stActive, false.B, incohEff)
            } else {
               s.scrubIncoh.get := scrubScanData.get =/= gprMirror(scrubIdx.get)
            }
            s.scrubIdx.get   := scrubIdx.get
         }
      }

      if (exposeRegfileEcc) {
         s.rfEccCorrectable.get   := rfEccCorrectable.get
         s.rfEccUncorrectable.get := rfEccUncorrectable.get
      }

      s.fpRegs.foreach { snap =>
         val fpMirror = RegInit(VecInit(Seq.fill(32)(0.U(33.W))))
         when(wb_reg_fp_wen.get) {
            fpMirror(wb_reg_wbaddr) := wb_reg_fp_wbdata.get
         }
         io.rbRestore.foreach { rb =>
            rb.fpParEn.foreach { pe =>
               when(pe) { for (i <- 0 until 32) { fpMirror(i.U) := rb.fpParData.get(i) } }
            }
         }
         snap := fpMirror
      }

      s.inflight.foreach { ifl =>
         ifl.if_pc        := if_reg_pc
         ifl.if_pc_buffer := if_reg_pc_buffer
         ifl.id_valid     := id_valid
         ifl.id_pc        := id_pc
         ifl.id_inst      := id_inst
         ifl.ex_valid           := ex_reg_valid
         ifl.ex_inst            := ex_reg_inst
         ifl.ex_pc              := ex_reg_pc
         ifl.ex_wbaddr          := ex_reg_wbaddr
         ifl.ex_rs1_addr        := ex_reg_rs1_addr
         ifl.ex_rs2_addr        := ex_reg_rs2_addr
         ifl.ex_op1_data        := ex_reg_op1_data
         ifl.ex_op2_data        := ex_reg_op2_data
         ifl.ex_rs2_data        := ex_reg_rs2_data
         ifl.ex_ctrl_alu_func   := ex_reg_ctrl_alu_func
         ifl.ex_ctrl_wb_sel     := ex_reg_ctrl_wb_sel
         ifl.ex_ctrl_rf_wen     := ex_reg_ctrl_rf_wen
         ifl.ex_ctrl_mem_val    := ex_reg_ctrl_mem_val
         ifl.ex_ctrl_mem_cmd    := ex_reg_ctrl_mem_cmd
         ifl.ex_ctrl_mem_typ    := ex_reg_ctrl_mem_typ
         ifl.ex_predicted_taken := ex_reg_predicted_taken
         ifl.mem_valid          := mem_reg_valid
         ifl.mem_pc             := mem_reg_pc
         ifl.mem_inst           := mem_reg_inst
         ifl.mem_alu_out        := mem_reg_alu_out
         ifl.mem_wbaddr         := mem_reg_wbaddr
         ifl.mem_rs1_addr       := mem_reg_rs1_addr
         ifl.mem_rs2_addr       := mem_reg_rs2_addr
         ifl.mem_op1_data       := mem_reg_op1_data
         ifl.mem_op2_data       := mem_reg_op2_data
         ifl.mem_ctrl_rf_wen    := mem_reg_ctrl_rf_wen
         ifl.mem_ctrl_mem_val   := mem_reg_ctrl_mem_val
         ifl.mem_ctrl_mem_cmd   := mem_reg_ctrl_mem_cmd
         ifl.mem_ctrl_mem_typ   := mem_reg_ctrl_mem_typ
         ifl.mem_ctrl_wb_sel    := mem_reg_ctrl_wb_sel
         ifl.mem_data_misaligned:= mem_reg_data_misaligned
         ifl.wb_valid        := wb_reg_valid
         ifl.wb_wbaddr       := wb_reg_wbaddr
         ifl.wb_wbdata       := wb_reg_wbdata
         ifl.wb_ctrl_rf_wen  := wb_reg_ctrl_rf_wen
         if (enableFExtension) {
            ifl.ex_fp_wen.get       := ex_reg_fp_wen.get
            ifl.ex_fp_op1_data.get  := ex_reg_fp_op1_data.get
            ifl.ex_fp_op2_data.get  := ex_reg_fp_op2_data.get
            ifl.ex_fp_op3_data.get  := ex_reg_fp_op3_data.get
            ifl.ex_fpu_op.get       := ex_reg_fpu_op.get
            ifl.ex_fp_fma.get       := ex_reg_fp_fma.get
            ifl.ex_fp_excFlags.get  := ex_reg_fp_excFlags.get
            ifl.ex_fp_storeData.get := ex_reg_fp_storeData.get
            ifl.mem_fp_wen.get      := mem_reg_fp_wen.get
            ifl.mem_fp_wbdata.get   := mem_reg_fp_wbdata.get
            ifl.mem_fp_is_load.get  := mem_reg_fp_is_load.get
            ifl.mem_fp_fma.get      := mem_reg_fp_fma.get
            ifl.mem_fp_excFlags.get := mem_reg_fp_excFlags.get
            ifl.wb_fp_wen.get       := wb_reg_fp_wen.get
            ifl.wb_fp_wbdata.get    := wb_reg_fp_wbdata.get
            ifl.wb_fp_excFlags.get  := wb_reg_fp_excFlags.get
         }
      }
   }

   io.rbRestore.foreach { rb =>
      csr.io.restoreEn.foreach(_ := rb.csrEn)
      csr.io.restoreArch.foreach(_ := rb.csr)
      when(rb.pcEn) {
         if_reg_pc        := rb.pc
         if_reg_pc_buffer := rb.pc
      }
      when(rb.flush) {
         id_valid           := false.B
         ex_reg_valid       := false.B
         mem_reg_valid      := false.B
         wb_reg_valid       := false.B
         wb_reg_ctrl_rf_wen := false.B

         if (exposeRollbackPc) {
            if_id_bubble := true.B
            exWriteBubble()
            mem_reg_inst            := BUBBLE
            mem_reg_ctrl_rf_wen     := false.B
            mem_reg_ctrl_mem_val    := false.B
            mem_reg_data_misaligned := false.B
            if (enableFExtension) {
               ex_reg_fp_wen.get       := false.B
               ex_reg_fpu_op.get       := FpuOp.X
               ex_reg_fp_fma.get       := false.B
               mem_reg_fp_wen.get      := false.B
               mem_reg_fp_is_load.get  := false.B
               mem_reg_fp_fma.get      := false.B
               mem_reg_fp_excFlags.get := 0.U
               wb_reg_fp_wen.get       := false.B
               wb_reg_fp_excFlags.get  := 0.U
            }
         }
      }

      rb.inflightEn.foreach { en =>
         val ifl = rb.inflight.get
         when(en) {
            if_reg_pc        := ifl.if_pc
            if_reg_pc_buffer := ifl.if_pc_buffer
            id_valid         := ifl.id_valid
            id_pc            := ifl.id_pc
            id_inst          := ifl.id_inst
            ex_reg_valid           := ifl.ex_valid
            ex_reg_inst            := ifl.ex_inst
            ex_reg_pc              := ifl.ex_pc
            ex_reg_wbaddr          := ifl.ex_wbaddr
            ex_reg_rs1_addr        := ifl.ex_rs1_addr
            ex_reg_rs2_addr        := ifl.ex_rs2_addr
            ex_reg_op1_data        := ifl.ex_op1_data
            ex_reg_op2_data        := ifl.ex_op2_data
            ex_reg_rs2_data        := ifl.ex_rs2_data
            ex_reg_ctrl_alu_func   := ifl.ex_ctrl_alu_func
            ex_reg_ctrl_wb_sel     := ifl.ex_ctrl_wb_sel
            ex_reg_ctrl_rf_wen     := ifl.ex_ctrl_rf_wen
            ex_reg_ctrl_mem_val    := ifl.ex_ctrl_mem_val
            ex_reg_ctrl_mem_cmd    := ifl.ex_ctrl_mem_cmd
            ex_reg_ctrl_mem_typ    := ifl.ex_ctrl_mem_typ
            ex_reg_predicted_taken := ifl.ex_predicted_taken
            mem_reg_valid          := ifl.mem_valid
            mem_reg_pc             := ifl.mem_pc
            mem_reg_inst           := ifl.mem_inst
            mem_reg_alu_out        := ifl.mem_alu_out
            mem_reg_wbaddr         := ifl.mem_wbaddr
            mem_reg_rs1_addr       := ifl.mem_rs1_addr
            mem_reg_rs2_addr       := ifl.mem_rs2_addr
            mem_reg_op1_data       := ifl.mem_op1_data
            mem_reg_op2_data       := ifl.mem_op2_data
            mem_reg_ctrl_rf_wen    := ifl.mem_ctrl_rf_wen
            mem_reg_ctrl_mem_val   := ifl.mem_ctrl_mem_val
            mem_reg_ctrl_mem_cmd   := ifl.mem_ctrl_mem_cmd
            mem_reg_ctrl_mem_typ   := ifl.mem_ctrl_mem_typ
            mem_reg_ctrl_wb_sel    := ifl.mem_ctrl_wb_sel
            mem_reg_data_misaligned:= ifl.mem_data_misaligned
            wb_reg_valid           := ifl.wb_valid
            wb_reg_wbaddr          := ifl.wb_wbaddr
            wb_reg_wbdata          := ifl.wb_wbdata
            wb_reg_ctrl_rf_wen     := ifl.wb_ctrl_rf_wen
            if (enableFExtension) {
               ex_reg_fp_wen.get       := ifl.ex_fp_wen.get
               ex_reg_fp_op1_data.get  := ifl.ex_fp_op1_data.get
               ex_reg_fp_op2_data.get  := ifl.ex_fp_op2_data.get
               ex_reg_fp_op3_data.get  := ifl.ex_fp_op3_data.get
               ex_reg_fpu_op.get       := ifl.ex_fpu_op.get
               ex_reg_fp_fma.get       := ifl.ex_fp_fma.get
               ex_reg_fp_excFlags.get  := ifl.ex_fp_excFlags.get
               ex_reg_fp_storeData.get := ifl.ex_fp_storeData.get
               mem_reg_fp_wen.get      := ifl.mem_fp_wen.get
               mem_reg_fp_wbdata.get   := ifl.mem_fp_wbdata.get
               mem_reg_fp_is_load.get  := ifl.mem_fp_is_load.get
               mem_reg_fp_fma.get      := ifl.mem_fp_fma.get
               mem_reg_fp_excFlags.get := ifl.mem_fp_excFlags.get
               wb_reg_fp_wen.get       := ifl.wb_fp_wen.get
               wb_reg_fp_wbdata.get    := ifl.wb_fp_wbdata.get
               wb_reg_fp_excFlags.get  := ifl.wb_fp_excFlags.get
            }
         }
      }
   }

   io.dat.id_valid   := id_valid
   io.dat.id_inst    := id_inst
   io.dat.ex_br_eq   := (ex_reg_op1_data === ex_reg_rs2_data)
   io.dat.ex_br_lt   := (ex_reg_op1_data.asSInt < ex_reg_rs2_data.asSInt)
   io.dat.ex_br_ltu  := (ex_reg_op1_data.asUInt < ex_reg_rs2_data.asUInt)
   io.dat.mem_ctrl_dmem_val := mem_reg_ctrl_mem_val

   io.dat.csr_eret := ex_reg_valid && (ex_reg_inst === "h30200073".U)
   io.dat.ex_predicted_taken := ex_reg_predicted_taken

   val mem_is_load = mem_reg_ctrl_mem_val && (mem_reg_ctrl_mem_cmd === MemCmd.Read)
   when(io.ctl.full_stall && mem_is_load) {
      io.dmem.en       := true.B
      io.dmem.req.addr := mem_reg_alu_out.asUInt
      io.dmem.req.cmd  := MemCmd.Read
      io.dmem.req.typ  := mem_reg_ctrl_mem_typ
      io.dmem.req.data := 0.U
   }.otherwise {
      io.dmem.en       := ex_reg_ctrl_mem_val && !ex_data_misaligned
      io.dmem.req.addr := ex_effective_addr.asUInt
      io.dmem.req.cmd  := ex_reg_ctrl_mem_cmd
      io.dmem.req.typ  := ex_reg_ctrl_mem_typ
      io.dmem.req.data := ex_reg_rs2_data
   }

   io.exMemValid := ex_reg_ctrl_mem_val && !ex_data_misaligned
   io.exMemAddr  := ex_effective_addr.asUInt

   if (enableFExtension) {
      mem_fp_load_data.get := hardfloat.recFNFromFN(8, 24, io.dmem.data)
   }

   if (withLateLoadForward) {

      val ex_reg_op1_from_rs1 = RegInit(false.B)
      val ex_reg_op2_from_rs2 = RegInit(false.B)

      when(io.ctl.full_stall) {

      }.elsewhen(ex_bubble) {
         ex_reg_op1_from_rs1 := false.B
         ex_reg_op2_from_rs2 := false.B
      }.elsewhen(ex_adv) {
         ex_reg_op1_from_rs1 := (io.ctl.op1_sel === Op1Sel.RS1)
         ex_reg_op2_from_rs2 := (io.ctl.op2_sel === Op2Sel.RS2)
      }

      val mem_is_load_fwd = (mem_reg_ctrl_wb_sel === WB_MEM) &&
                            mem_reg_ctrl_rf_wen && (mem_reg_wbaddr =/= 0.U)

      val fwd_mem_to_ex_rs1 = mem_is_load_fwd && (mem_reg_wbaddr === ex_reg_rs1_addr)
      val fwd_mem_to_ex_rs2 = mem_is_load_fwd && (mem_reg_wbaddr === ex_reg_rs2_addr)

      when(fwd_mem_to_ex_rs1 && ex_reg_op1_from_rs1) {
         ex_alu_op1 := dmem_data_signExtended
      }
      when(fwd_mem_to_ex_rs2 && ex_reg_op2_from_rs2) {
         ex_alu_op2 := dmem_data_signExtended
      }

      val ex_br_rs1 = Mux(fwd_mem_to_ex_rs1, dmem_data_signExtended, ex_reg_op1_data)
      val ex_br_rs2 = Mux(fwd_mem_to_ex_rs2, dmem_data_signExtended, ex_reg_rs2_data)
      io.dat.ex_br_eq  := (ex_br_rs1 === ex_br_rs2)
      io.dat.ex_br_lt  := (ex_br_rs1.asSInt < ex_br_rs2.asSInt)
      io.dat.ex_br_ltu := (ex_br_rs1.asUInt < ex_br_rs2.asUInt)

      val ex_rs2_effective = Mux(fwd_mem_to_ex_rs2, dmem_data_signExtended, ex_reg_rs2_data)
      when(!(io.ctl.full_stall && mem_is_load)) {
         io.dmem.req.data := ex_rs2_effective
      }
   }

   if (enableFExtension) {
      when(ex_reg_fpu_op.get === FpuOp.FSW && ex_reg_ctrl_mem_val &&
           !(io.ctl.full_stall && mem_is_load)) {
         io.dmem.req.data := ex_fp_storeData.get
      }
   }

   if (enableDualIssue) {
      val splitterB = splitter.get

      val controlUnitB = Module(new ControlUnit(
        enableMExtension   = enableMExtension,
        enableBExtension   = enableBExtension,
        withLateLoadForward = withLateLoadForward,
        exposeDecodeBundle = true,
        enableFExtension   = enableFExtension))
      controlUnitB.io := DontCare
      controlUnitB.io.dat.id_inst             := splitterB.io.slotB_inst
      controlUnitB.io.dat.id_valid            := splitterB.io.slotB_valid
      controlUnitB.io.dat.ex_br_eq            := false.B
      controlUnitB.io.dat.ex_br_lt            := false.B
      controlUnitB.io.dat.ex_br_ltu           := false.B
      controlUnitB.io.dat.ex_inst_misaligned  := false.B
      controlUnitB.io.dat.mem_ctrl_dmem_val   := false.B
      controlUnitB.io.dat.mem_data_misaligned := false.B
      controlUnitB.io.dat.mem_store           := false.B
      controlUnitB.io.dat.csr_eret            := false.B
      controlUnitB.io.dat.csr_interrupt       := false.B
      controlUnitB.io.dat.id_predict_taken    := false.B
      controlUnitB.io.dat.ex_predicted_taken  := false.B
      controlUnitB.io.mdu_busy                := false.B
      controlUnitB.io.wfi_stall               := false.B
      controlUnitB.io.dmaMemStall             := false.B

      controlUnitB.slotB_valid.get  := false.B
      controlUnitB.id_rsB1_addr.get := 0.U
      controlUnitB.id_rsB2_addr.get := 0.U

      val issueLogic = Module(new IssueLogic(
        enableIntraPairForward = enableIntraPairForward,
        exposeRejectCounters   = enableIssueObservability,
        enableFExtension       = enableFExtension))
      issueLogic.io.decA := io.slotA_dec.get
      issueLogic.io.decB := controlUnitB.dec.get

      issueLogic.io.A_rs1 := id_rs1_addr
      issueLogic.io.A_rs2 := id_rs2_addr
      issueLogic.io.A_rd  := id_wbaddr

      val slotB_inst_w = splitterB.io.slotB_inst
      issueLogic.io.B_rs1 := slotB_inst_w(19, 15)
      issueLogic.io.B_rs2 := slotB_inst_w(24, 20)
      issueLogic.io.B_rd  := slotB_inst_w(11, 7)

      rf_rsB1_addr := slotB_inst_w(19, 15)
      rf_rsB2_addr := slotB_inst_w(24, 20)
      issueLogic.io.A_valid := id_valid
      issueLogic.io.B_valid := splitterB.io.slotB_valid
      issueLogic.io.A_is_taken_branch := id_predict_taken

      paired_dispatch.get :=
         issueLogic.io.issue_B &&
         ex_adv &&
         !io.ctl.full_stall &&
         !id_stall_prev
      dontTouch(paired_dispatch.get)

      io.slotB_valid_out.get := splitterB.io.slotB_valid
      io.slotB_rs1_addr.get  := slotB_inst_w(19, 15)
      io.slotB_rs2_addr.get  := slotB_inst_w(24, 20)

      val ex_reg_B_valid       = RegInit(false.B)
      val ex_reg_B_inst        = RegInit(BUBBLE)
      val ex_reg_B_pc          = RegInit(0.asUInt(32.W))
      val ex_reg_B_wbaddr      = Reg(UInt(5.W))
      val ex_reg_B_rs1_addr    = Reg(UInt(5.W))
      val ex_reg_B_rs2_addr    = Reg(UInt(5.W))
      val ex_reg_B_op1_data    = Reg(UInt(32.W))
      val ex_reg_B_op2_data    = Reg(UInt(32.W))
      val ex_reg_B_rs2_data    = Reg(UInt(32.W))
      val ex_reg_B_ctrl_alu_func = Reg(AluOp.Type())
      val ex_reg_B_ctrl_wb_sel = Reg(UInt())
      val ex_reg_B_ctrl_rf_wen = RegInit(false.B)
      val ex_reg_B_ctrl_mem_val = RegInit(false.B)

      val ex_reg_B_op1_from_rs1 = RegInit(false.B)
      val ex_reg_B_op2_from_rs2 = RegInit(false.B)

      val mem_reg_B_valid      = RegInit(false.B)
      val mem_reg_B_pc         = Reg(UInt(32.W))
      val mem_reg_B_inst       = Reg(UInt(32.W))
      val mem_reg_B_wbaddr     = Reg(UInt(5.W))
      val mem_reg_B_wbdata     = Reg(UInt(32.W))
      val mem_reg_B_ctrl_rf_wen = RegInit(false.B)
      val mem_reg_B_ctrl_wb_sel = Reg(UInt())

      val wb_reg_B_valid       = RegInit(false.B)
      val wb_reg_B_wbaddr      = Reg(UInt(5.W))
      val wb_reg_B_wbdata      = Reg(UInt(32.W))
      val wb_reg_B_ctrl_rf_wen = RegInit(false.B)

      val ex_B_wbdata = Wire(UInt(32.W))

      val imm_itype_B  = slotB_inst_w(31, 20)
      val imm_stype_B  = Cat(slotB_inst_w(31, 25), slotB_inst_w(11, 7))
      val imm_sbtype_B = Cat(slotB_inst_w(31), slotB_inst_w(7), slotB_inst_w(30, 25), slotB_inst_w(11, 8))
      val imm_utype_B  = slotB_inst_w(31, 12)
      val imm_ujtype_B = Cat(slotB_inst_w(31), slotB_inst_w(19, 12), slotB_inst_w(20), slotB_inst_w(30, 21))
      val imm_z_B      = Cat(Fill(27, 0.U), slotB_inst_w(19, 15))

      val imm_itype_sext_B  = Cat(Fill(20, imm_itype_B(11)), imm_itype_B)
      val imm_stype_sext_B  = Cat(Fill(20, imm_stype_B(11)), imm_stype_B)
      val imm_sbtype_sext_B = Cat(Fill(19, imm_sbtype_B(11)), imm_sbtype_B, 0.U)
      val imm_utype_sext_B  = Cat(imm_utype_B, Fill(12, 0.U))
      val imm_ujtype_sext_B = Cat(Fill(11, imm_ujtype_B(19)), imm_ujtype_B, 0.U)

      val id_rsB1_addr = slotB_inst_w(19, 15)
      val id_rsB2_addr = slotB_inst_w(24, 20)

      val fwd_rsB1_exe_A = fwdMatch(ex_reg_ctrl_rf_wen,                        ex_reg_wbaddr,    id_rsB1_addr)
      val fwd_rsB1_exe_B = fwdMatch(ex_reg_B_ctrl_rf_wen  && ex_reg_B_valid,   ex_reg_B_wbaddr,  id_rsB1_addr)
      val fwd_rsB1_mem_A = fwdMatch(mem_reg_ctrl_rf_wen,                       mem_reg_wbaddr,   id_rsB1_addr)
      val fwd_rsB1_mem_B = fwdMatch(mem_reg_B_ctrl_rf_wen && mem_reg_B_valid,  mem_reg_B_wbaddr, id_rsB1_addr)
      val fwd_rsB1_wb_A  = fwdMatch(wb_reg_ctrl_rf_wen,                        wb_reg_wbaddr,    id_rsB1_addr)
      val fwd_rsB1_wb_B  = fwdMatch(wb_reg_B_ctrl_rf_wen  && wb_reg_B_valid,   wb_reg_B_wbaddr,  id_rsB1_addr)

      val rsB1_val = Mux(fwd_rsB1_exe_B, ex_B_wbdata,
                     Mux(fwd_rsB1_exe_A, ex_alu_out,
                     Mux(fwd_rsB1_mem_B, mem_reg_B_wbdata,
                     Mux(fwd_rsB1_mem_A, mem_wbdata,
                     Mux(fwd_rsB1_wb_B,  wb_reg_B_wbdata,
                     Mux(fwd_rsB1_wb_A,  wb_reg_wbdata,
                                         rf_rsB1_data))))))

      val fwd_rsB2_exe_A = fwdMatch(ex_reg_ctrl_rf_wen,                        ex_reg_wbaddr,    id_rsB2_addr)
      val fwd_rsB2_exe_B = fwdMatch(ex_reg_B_ctrl_rf_wen  && ex_reg_B_valid,   ex_reg_B_wbaddr,  id_rsB2_addr)
      val fwd_rsB2_mem_A = fwdMatch(mem_reg_ctrl_rf_wen,                       mem_reg_wbaddr,   id_rsB2_addr)
      val fwd_rsB2_mem_B = fwdMatch(mem_reg_B_ctrl_rf_wen && mem_reg_B_valid,  mem_reg_B_wbaddr, id_rsB2_addr)
      val fwd_rsB2_wb_A  = fwdMatch(wb_reg_ctrl_rf_wen,                        wb_reg_wbaddr,    id_rsB2_addr)
      val fwd_rsB2_wb_B  = fwdMatch(wb_reg_B_ctrl_rf_wen  && wb_reg_B_valid,   wb_reg_B_wbaddr,  id_rsB2_addr)

      val rsB2_val = Mux(fwd_rsB2_exe_B, ex_B_wbdata,
                     Mux(fwd_rsB2_exe_A, ex_alu_out,
                     Mux(fwd_rsB2_mem_B, mem_reg_B_wbdata,
                     Mux(fwd_rsB2_mem_A, mem_wbdata,
                     Mux(fwd_rsB2_wb_B,  wb_reg_B_wbdata,
                     Mux(fwd_rsB2_wb_A,  wb_reg_wbdata,
                                         rf_rsB2_data))))))

      val op1_val_B = WireDefault(rsB1_val)
      switch(controlUnitB.io.ctl.op1_sel) {
         is(Op1Sel.PC)  { op1_val_B := splitterB.io.slotB_pc }
         is(Op1Sel.IMZ) { op1_val_B := imm_z_B }

      }
      val id_op1_data_B = op1_val_B

      val op2_imm_B = WireDefault(0.U(32.W))
      switch(controlUnitB.io.ctl.op2_sel) {
         is(Op2Sel.RS2)    { op2_imm_B := rf_rsB2_data }
         is(Op2Sel.ITYPE)  { op2_imm_B := imm_itype_sext_B }
         is(Op2Sel.STYPE)  { op2_imm_B := imm_stype_sext_B }
         is(Op2Sel.SBTYPE) { op2_imm_B := imm_sbtype_sext_B }
         is(Op2Sel.UTYPE)  { op2_imm_B := imm_utype_sext_B }
         is(Op2Sel.UJTYPE) { op2_imm_B := imm_ujtype_sext_B }

      }
      val id_op2_data_B = Mux(controlUnitB.io.ctl.op2_sel === Op2Sel.RS2, rsB2_val, op2_imm_B)

      val decB = controlUnitB.dec.get
      when(io.ctl.full_stall) {

      }.elsewhen(ex_bubble) {
         ex_reg_B_valid       := false.B
         ex_reg_B_inst        := BUBBLE
         ex_reg_B_wbaddr      := 0.U
         ex_reg_B_ctrl_rf_wen := false.B
         ex_reg_B_ctrl_mem_val := false.B
         ex_reg_B_op1_from_rs1 := false.B
         ex_reg_B_op2_from_rs2 := false.B
      }.elsewhen(ex_adv) {

         ex_reg_B_valid       := paired_dispatch.get
         ex_reg_B_inst        := slotB_inst_w
         ex_reg_B_pc          := splitterB.io.slotB_pc
         ex_reg_B_wbaddr      := slotB_inst_w(11, 7)
         ex_reg_B_rs1_addr    := slotB_inst_w(19, 15)
         ex_reg_B_rs2_addr    := slotB_inst_w(24, 20)

         ex_reg_B_op1_data    := id_op1_data_B
         ex_reg_B_op2_data    := id_op2_data_B
         ex_reg_B_rs2_data    := rf_rsB2_data
         ex_reg_B_ctrl_alu_func := controlUnitB.io.ctl.alu_func
         ex_reg_B_ctrl_wb_sel := decB.wb_sel
         ex_reg_B_ctrl_rf_wen := decB.rf_wen
         ex_reg_B_ctrl_mem_val := decB.mem_val

         ex_reg_B_op1_from_rs1 := paired_dispatch.get &&
                                  (controlUnitB.io.ctl.op1_sel === Op1Sel.RS1)
         ex_reg_B_op2_from_rs2 := paired_dispatch.get &&
                                  (controlUnitB.io.ctl.op2_sel === Op2Sel.RS2)
      }

      val ex_B_op1_final =
         if (enableIntraPairForward) {
            val fwd_ex_B_op1_intra_A: Bool =
               ex_reg_valid && ex_reg_ctrl_rf_wen &&
               ((ex_reg_ctrl_wb_sel === WB_ALU) || (ex_reg_ctrl_wb_sel === WB_BEXT)) &&
               (ex_reg_wbaddr =/= 0.U) &&
               ex_reg_B_valid && ex_reg_B_op1_from_rs1 &&
               (ex_reg_wbaddr === ex_reg_B_rs1_addr)
            dontTouch(fwd_ex_B_op1_intra_A)
            Mux(fwd_ex_B_op1_intra_A, ex_alu_out, ex_reg_B_op1_data)
         } else ex_reg_B_op1_data

      val ex_B_op2_final =
         if (enableIntraPairForward) {
            val fwd_ex_B_op2_intra_A: Bool =
               ex_reg_valid && ex_reg_ctrl_rf_wen &&
               ((ex_reg_ctrl_wb_sel === WB_ALU) || (ex_reg_ctrl_wb_sel === WB_BEXT)) &&
               (ex_reg_wbaddr =/= 0.U) &&
               ex_reg_B_valid && ex_reg_B_op2_from_rs2 &&
               (ex_reg_wbaddr === ex_reg_B_rs2_addr)
            dontTouch(fwd_ex_B_op2_intra_A)
            Mux(fwd_ex_B_op2_intra_A, ex_alu_out, ex_reg_B_op2_data)
         } else ex_reg_B_op2_data

      val aluB = Module(new ALU)
      aluB.io.op1_data := ex_B_op1_final
      aluB.io.op2_data := ex_B_op2_final
      aluB.io.alu_func := ex_reg_B_ctrl_alu_func

      val bextB = Module(new bext.BExtALU(bext.BExtConfig(
        enableZba = true, enableZbb = true, enableZbc = false, enableZbs = false)))
      bextB.io.rs1    := ex_B_op1_final
      bextB.io.rs2    := ex_B_op2_final
      bextB.io.funct3 := ex_reg_B_inst(14, 12)
      bextB.io.funct7 := ex_reg_B_inst(31, 25)
      bextB.io.rs2sel := ex_reg_B_inst(24, 20)
      bextB.io.itype  := (ex_reg_B_inst(6, 0) === "b0010011".U)

      val ex_is_bext_op_B = (ex_reg_B_ctrl_wb_sel === WB_BEXT)
      val ex_alu_B_out    = Mux(ex_is_bext_op_B, bextB.io.out.result, aluB.io.out)
      val ex_B_pc_plus4   = (ex_reg_B_pc + 4.U)(32 - 1, 0)

      ex_B_wbdata         := Mux(ex_reg_B_ctrl_wb_sel === WB_PC4, ex_B_pc_plus4, ex_alu_B_out)

      val fwd_rs1_exe_B = fwdMatch(ex_reg_B_ctrl_rf_wen  && ex_reg_B_valid,   ex_reg_B_wbaddr,  id_rs1_addr)
      val fwd_rs1_mem_B = fwdMatch(mem_reg_B_ctrl_rf_wen && mem_reg_B_valid,  mem_reg_B_wbaddr, id_rs1_addr)
      val fwd_rs1_wb_B  = fwdMatch(wb_reg_B_ctrl_rf_wen  && wb_reg_B_valid,   wb_reg_B_wbaddr,  id_rs1_addr)
      val fwd_rs2_exe_B = fwdMatch(ex_reg_B_ctrl_rf_wen  && ex_reg_B_valid,   ex_reg_B_wbaddr,  id_rs2_addr)
      val fwd_rs2_mem_B = fwdMatch(mem_reg_B_ctrl_rf_wen && mem_reg_B_valid,  mem_reg_B_wbaddr, id_rs2_addr)
      val fwd_rs2_wb_B  = fwdMatch(wb_reg_B_ctrl_rf_wen  && wb_reg_B_valid,   wb_reg_B_wbaddr,  id_rs2_addr)

      rs1_val := Mux(fwd_rs1_exe_B, ex_B_wbdata,
                 Mux(g_rs1_exe,     ex_alu_out,
                 Mux(fwd_rs1_mem_B, mem_reg_B_wbdata,
                 Mux(g_rs1_mem,     mem_wbdata,
                 Mux(fwd_rs1_wb_B,  wb_reg_B_wbdata,
                 Mux(g_rs1_wb,      wb_reg_wbdata,
                                    rf_rs1_data))))))

      rs2_val := Mux(fwd_rs2_exe_B, ex_B_wbdata,
                 Mux(g_rs2_exe,     ex_alu_out,
                 Mux(fwd_rs2_mem_B, mem_reg_B_wbdata,
                 Mux(g_rs2_mem,     mem_wbdata,
                 Mux(fwd_rs2_wb_B,  wb_reg_B_wbdata,
                 Mux(g_rs2_wb,      wb_reg_wbdata,
                                    rf_rs2_data))))))

      rs1_val_for_jalr.get :=
         Mux(fwd_rs1_mem_B, mem_reg_B_wbdata,
         Mux(g_rs1_mem,     mem_wbdata,
         Mux(fwd_rs1_wb_B,  wb_reg_B_wbdata,
         Mux(g_rs1_wb,      wb_reg_wbdata,
                            rf_rs1_data))))
      jalr_rs1_needs_exe_fwd.get := g_rs1_exe || fwd_rs1_exe_B

      when(io.ctl.full_stall) {

      }.elsewhen(io.ctl.pipeline_kill || io.ctl.id_kill) {
         mem_reg_B_valid       := false.B
         mem_reg_B_inst        := BUBBLE
         mem_reg_B_ctrl_rf_wen := false.B
      }.otherwise {
         mem_reg_B_valid       := ex_reg_B_valid
         mem_reg_B_pc          := ex_reg_B_pc
         mem_reg_B_inst        := ex_reg_B_inst
         mem_reg_B_wbaddr      := ex_reg_B_wbaddr
         mem_reg_B_wbdata      := ex_B_wbdata
         mem_reg_B_ctrl_rf_wen := ex_reg_B_ctrl_rf_wen
         mem_reg_B_ctrl_wb_sel := ex_reg_B_ctrl_wb_sel
      }

      when(!io.ctl.full_stall) {
         wb_reg_B_valid       := mem_reg_B_valid && !io.ctl.mem_exception
         wb_reg_B_wbaddr      := mem_reg_B_wbaddr
         wb_reg_B_wbdata      := mem_reg_B_wbdata
         wb_reg_B_ctrl_rf_wen := Mux(io.ctl.mem_exception, false.B, mem_reg_B_ctrl_rf_wen)
      }.otherwise {
         wb_reg_B_valid       := false.B
         wb_reg_B_ctrl_rf_wen := false.B
      }

      if (enableRetireTrace) {
         val wb_B_advance_prev = RegNext(!io.ctl.full_stall, init = false.B)
         val wb_B_commit_pulse = WireInit(false.B)
         wb_B_commit_pulse := wb_reg_B_ctrl_rf_wen && wb_reg_B_valid && wb_B_advance_prev
         dontTouch(wb_B_commit_pulse)
      }

      val waw_conflict = wb_reg_ctrl_rf_wen && wb_reg_B_ctrl_rf_wen && wb_reg_B_valid &&
                         (wb_reg_wbaddr === wb_reg_B_wbaddr) && (wb_reg_wbaddr =/= 0.U)
      rf_wp0_suppress := waw_conflict
      rf_wp1_waddr    := wb_reg_B_wbaddr
      rf_wp1_wdata    := wb_reg_B_wbdata
      rf_wp1_wen      := wb_reg_B_valid && wb_reg_B_ctrl_rf_wen

      io.safetyCommit.foreach { _.wb_B_valid.get := wb_reg_B_valid }

      dontTouch(ex_reg_B_valid)
      dontTouch(ex_reg_B_inst)
      dontTouch(ex_reg_B_pc)
      dontTouch(mem_reg_B_valid)

      dontTouch(mem_reg_B_pc)
      dontTouch(wb_reg_B_valid)

      if (enableIssueObservability) {
         val issue_observe = Wire(new IssueObserveBundle)
         issue_observe := issueLogic.io.observe.get
         dontTouch(issue_observe)
      }
   }

   if (enableBranchObserve) {

      val mem_opcode        = mem_reg_inst(6, 0)
      val mem_is_cbranch    = (mem_opcode === "b1100011".U)
      val mem_is_br_or_jump = mem_is_cbranch ||
                              (mem_opcode === "b1101111".U) ||
                              (mem_opcode === "b1100111".U)
      val wb_reg_is_br_or_jump = RegInit(false.B)
      val wb_reg_is_cbranch    = RegInit(false.B)
      when(!io.ctl.full_stall) {
         wb_reg_is_br_or_jump := mem_is_br_or_jump && mem_reg_valid && !io.ctl.mem_exception
         wb_reg_is_cbranch    := mem_is_cbranch    && mem_reg_valid && !io.ctl.mem_exception
      }.otherwise {
         wb_reg_is_br_or_jump := false.B
         wb_reg_is_cbranch    := false.B
      }

      val ev_cbranch_redirect = ex_is_branch_inst &&
         ((io.ctl.ex_pc_sel === PcSel.BrJmp) || (io.ctl.ex_pc_sel === PcSel.ExPc4)) &&
         !io.ctl.full_stall

      val ev_jalr_mispredict = ex_is_jalr_inst &&
         (io.ctl.ex_pc_sel === PcSel.Jalr) &&
         ex_reg_predicted_taken &&
         !io.ctl.full_stall

      val ev_jalr_id_suppressed = id_is_jalr && id_jalr_predict_block &&
         !io.ctl.id_stall && !id_stall_prev && !io.ctl.full_stall &&
         !io.ctl.pipeline_kill && !io.ctl.id_kill && !io.ctl.fencei

      val ev_branches_total = wb_reg_valid && wb_reg_is_br_or_jump

      val ev_jalr_redirect_total = ex_is_jalr_inst &&
         (io.ctl.ex_pc_sel === PcSel.Jalr) &&
         !io.ctl.full_stall

      val ev_cbranches_total = wb_reg_valid && wb_reg_is_cbranch

      val branchObs = Module(new BranchObserve)
      branchObs.io.events.cbranch_redirect   := ev_cbranch_redirect
      branchObs.io.events.jalr_mispredict      := ev_jalr_mispredict
      branchObs.io.events.jalr_id_suppressed   := ev_jalr_id_suppressed
      branchObs.io.events.branches_total       := ev_branches_total
      branchObs.io.events.jalr_redirect_total  := ev_jalr_redirect_total
      branchObs.io.events.cbranches_total      := ev_cbranches_total

      val branch_observe = Wire(new BranchObserveCounters)
      branch_observe := branchObs.io.counters
      dontTouch(branch_observe)
   }

   dontTouch(io.dat.id_inst)
   dontTouch(io.ctl.if_kill)
   dontTouch(id_inst)
   dontTouch(if_id_bubble)
   dontTouch(interrupt)
   dontTouch(if_pc_next)
   dontTouch(ex_brjmp_target)
   dontTouch(ex_bubble)
   dontTouch(ex_reg_valid)
   dontTouch(ex_reg_inst)
   dontTouch(io.ctl.ex_pc_sel)
}
