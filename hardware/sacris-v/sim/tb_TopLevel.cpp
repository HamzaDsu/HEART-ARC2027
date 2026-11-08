#include <stdlib.h>
#include <iostream>
#include <fstream>
#include <vector>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include <stdint.h>

#include "VTopLevel.h"
#include "VTopLevel___024root.h"
#include "uart_std_out_tx.hpp"
#include "uart_stdin_rx.hpp"

#define MAX_SIM_TIME 2000000000ULL

//#define DEBUG

vluint64_t sim_time = 0;
static uint64_t cycle_count = 0;
static FILE *rf_log = nullptr;
static uint32_t prev_mem_reg_pc = 0;  // shadow for WB-stage PC (1-cycle delay)

// WCET per-PC histogram (SACRIS_V_PC_HIST=<file>, 2026-10-21, plan phase 1,
// decision 2): the RTL twin of the emulator's SACRIS_V_PC_HIST for the
// configurations the emulator cannot model (F, dual issue, C). Per PC it
// records how many instructions RETIRED there (wb_reg_valid is set for
// exactly one cycle per architectural retire: Pipeline.scala assigns it
// ~full_stall & mem_reg_valid & ~mem_exception every cycle, so a stall never
// duplicates it; on a dual-issue build wb_reg_B_valid counts the Slot-B
// retirements the same way) and how many CYCLES the pipeline spent with that PC in EX
// (ex_reg_pc while ex_reg_valid; a cycle with no valid EX instruction is
// charged to the last one, which is where a branch bubble or a stall lands).
// The file format "0x<pc> <count> <cycles>" and the stderr lines
// "csr_window <k> cycle=<n> instret=<n>", emitted at every retired
// `csrrs rd, cycle, x0`, are the emulator's, so otawa/ff_audit.py reads
// both sources alike. Cost: one map update per cycle, only when enabled.
#include <map>
static const char *pc_hist_path = nullptr;
static std::map<uint32_t, std::pair<uint64_t, uint64_t>> pc_hist;   // pc -> (retired, ex cycles)
static uint32_t prev_mem_reg_inst = 0;   // shadow for WB-stage instruction word
static uint32_t pc_hist_last_ex_pc = 0;
static uint64_t pc_hist_retired = 0;
static int pc_hist_windows = 0;
static bool pc_hist_window_only = false;   // SACRIS_V_CSR_WINDOW set
static bool pc_hist_in_window = false;
static void pc_hist_dump(void)
{
    if (!pc_hist_path) return;
    FILE *f = fopen(pc_hist_path, "w");
    if (!f) { std::cerr << "warning: cannot write " << pc_hist_path << "\n"; return; }
    for (auto &kv : pc_hist)
        fprintf(f, "0x%08x %llu %llu\n", kv.first,
                (unsigned long long)kv.second.first, (unsigned long long)kv.second.second);
    fclose(f);
    pc_hist_path = nullptr;   // dump once
}
#ifdef SACRIS_DUAL_ISSUE
static uint32_t prev_mem_reg_B_pc = 0; // Slot-B WB-stage PC shadow (1-cycle delay)
#endif

// P1.2 observability: when SACRIS_V_ISSUE_OBSERVE env var is set (to a file
// path), count IssueLogic decisions across the run and write a summary.
// Only active on dual-issue builds. Default behavior unchanged.
#ifdef SACRIS_DUAL_ISSUE
static uint64_t obs_cycles_observed = 0;
static uint64_t obs_issue_A_count   = 0;
static uint64_t obs_issue_B_count   = 0;
static uint64_t obs_stall_B_count   = 0;
static uint64_t obs_squash_B_count  = 0;
static const char *obs_log_path     = nullptr;
#endif

// P1.2.5 per-check observability: when SACRIS_ISSUE_OBSERVE is defined at
// compile time AND SACRIS_ISSUE_OBSERVE_CSV (or SACRIS_V_ISSUE_OBSERVE) is
// set at runtime, the testbench aggregates the 10 per-check reject signals
// plus the three-way outcome distribution and writes a CSV. Requires a
// TopLevel.sv generated with ISSUE_OBSERVE=true (so the issue_observe_*
// wires exist in the emitted Verilog).
// Branch-event observability: when SACRIS_BRANCH_OBSERVE is defined at
// compile time, the four 64-bit counter wires (branch_observe_*) on the
// Pipeline module are read once at end-of-sim and dumped to stdout. When
// the SACRIS_BRANCH_OBSERVE env var is additionally set to a file path, a
// CSV is written there (mirrors SACRIS_V_ISSUE_OBSERVE convention).
// Requires a TopLevel.sv generated with WITH_BRANCH_OBSERVE=true.
#ifdef SACRIS_BRANCH_OBSERVE
static const char *branch_obs_csv_path = nullptr;
#endif
// C-extension cycle-regression observability: when SACRIS_CEXT_OBSERVE is
// defined at compile time, the eight 64-bit counters and 8 first-PC capture
// regs from Pipeline.scala's `if (enableCExtensionCycleObserve)` block are
// scraped at end-of-sim and dumped to stdout. The optional
// SACRIS_CEXT_OBSERVE env var names a CSV output path. Requires a
// TopLevel.sv produced by `runMain sacris_v.TopLevelCExtDiag`.
#ifdef SACRIS_CEXT_OBSERVE
static const char *cext_obs_csv_path = nullptr;
#endif

#ifdef SACRIS_ISSUE_OBSERVE
static uint64_t obs_reject_check1_raw_intra    = 0;
static uint64_t obs_reject_check2_waw_intra    = 0;
static uint64_t obs_reject_check3_B_mem        = 0;
static uint64_t obs_reject_check4_B_mdu        = 0;
static uint64_t obs_reject_check5_B_csr        = 0;
static uint64_t obs_reject_check6_B_branch     = 0;
static uint64_t obs_reject_check7_B_sync       = 0;
static uint64_t obs_reject_check8_B_illegal    = 0;
static uint64_t obs_reject_check9_A_taken_br   = 0;
static uint64_t obs_reject_check10_B_zbc_zbs   = 0;
static uint64_t obs_issued_pair                = 0;
static uint64_t obs_issued_single_A_only       = 0;
static uint64_t obs_stalled_B                  = 0;
// P1.2.6: ch1 RAW sole-fire — isolated reject, addressable by P1.4 forwarding.
static uint64_t obs_reject_check1_sole_fire    = 0;
static const char *obs_csv_path                = nullptr;
#endif

void dut_reset(VTopLevel *dut, vluint64_t sim_time)
{
    dut->reset = 0;
    if (sim_time >= 3 && sim_time < 6)
        dut->reset = 1;
}

template<std::size_t N>
static bool loadBinToMem32(const std::string& path, VlUnpacked<IData, N>& mem)
{
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;

    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)),
                               std::istreambuf_iterator<char>());

    size_t numWords = (data.size() + 3) / 4;
    if (numWords > N) numWords = N;

    for (size_t i = 0; i < numWords; i++) {
        uint32_t word = 0;
        for (size_t b = 0; b < 4 && (i * 4 + b) < data.size(); b++) {
            word |= (uint32_t)data[i * 4 + b] << (b * 8);
        }
        mem[i] = word;
    }
    return true;
}

// P1.1: 64-bit-entry variant used by the dual-issue InstructionMem64, which
// packs adjacent 32-bit words (lower half = even word, upper half = odd word).
// File format is unchanged (32-bit little-endian words); packing happens here.
template<std::size_t N>
static bool loadBinToMem64(const std::string& path, VlUnpacked<QData, N>& mem)
{
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;

    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)),
                               std::istreambuf_iterator<char>());

    size_t numWords = (data.size() + 3) / 4;
    size_t numEntries = (numWords + 1) / 2;
    if (numEntries > N) numEntries = N;

    for (size_t e = 0; e < numEntries; e++) {
        uint64_t entry = 0;
        for (int half = 0; half < 2; half++) {
            size_t wi = e * 2 + half;
            uint32_t word = 0;
            for (size_t b = 0; b < 4 && (wi * 4 + b) < data.size(); b++) {
                word |= (uint32_t)data[wi * 4 + b] << (b * 8);
            }
            entry |= ((uint64_t)word) << (32 * half);
        }
        mem[e] = entry;
    }
    return true;
}

#ifdef SACRIS_MEM_ECC
// FuSi block 2 (withMemEcc builds): the IMEM/DMEM data arrays carry parallel
// SECDED check-bit memories (per-word (39,32) for IMEM, per-byte (13,8) for
// DMEM). Preloaded program words must be encoded consistently, otherwise the
// first read of every loaded word would flag an ECC error. Mirrors
// Secded.encode in DataMem.scala: check word = {overallParity, p_{r-1}..p_0},
// data bit i at the i-th non-power-of-two codeword position.
static uint32_t secded_check(uint64_t d, int k)
{
    int r = 0;
    while ((1 << r) < k + r + 1) r++;
    int pos_of_data[64];
    int di = 0;
    for (int p = 1; p <= k + r; p++)
        if ((p & (p - 1)) != 0) pos_of_data[di++] = p;
    uint32_t par = 0;
    for (int j = 0; j < r; j++) {
        int x = 0;
        for (int i = 0; i < k; i++)
            if (pos_of_data[i] & (1 << j)) x ^= (int)((d >> i) & 1);
        par |= (uint32_t)x << j;
    }
    uint64_t dmasked = (k >= 64) ? d : (d & ((1ULL << k) - 1));
    uint32_t overall = (uint32_t)(__builtin_parityll(dmasked) ^ __builtin_parity(par));
    return par | (overall << r);
}

template<std::size_t N, typename TD, typename TC>
static void secdedEncodeImem(VlUnpacked<TD, N>& data, VlUnpacked<TC, N>& check)
{
    for (std::size_t i = 0; i < N; i++)
        check[i] = (TC)secded_check((uint64_t)data[i], 32);
}

template<std::size_t N, typename TD, typename TC>
static void secdedEncodeDmem(VlUnpacked<TD, N>& data, VlUnpacked<TC, N>& check)
{
    for (std::size_t i = 0; i < N; i++) {
        uint32_t w = (uint32_t)data[i], c = 0;
        for (int b = 0; b < 4; b++)
            c |= secded_check((w >> (8 * b)) & 0xFF, 8) << (5 * b);
        check[i] = (TC)c;
    }
}
#endif

static UartStdOutTx uartTx(50'000'000, 115'200);

int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);

    VTopLevel *dut = new VTopLevel;

    // Optional: path to directory containing instruction_mem.bin and data_mem.bin
    // Usage:  VTopLevel [<program_dir>]
    //   With path:    load memories directly, reset_vector = 0x8000_0000
    //   Without path: bootloader mode, reset_vector = 0x0
    std::string imemPath, dmemPath;
    bool directLoadMode = false;

    if (argc >= 2) {
        const std::string binDir = argv[1];
        imemPath = binDir + "/instruction_mem.bin";
        dmemPath = binDir + "/data_mem.bin";
        dut->io_reset_vector = 0x80000000;
        directLoadMode = true;
        std::cout << "Loaded program from " << binDir << "\n";
    } else {
        dut->io_reset_vector = 0;
        std::cout << "Bootloader mode (no program path given)\n";
    }

    Verilated::traceEverOn(true);

#ifdef DEBUG
    VerilatedVcdC *m_trace = new VerilatedVcdC;
    // Tiefe 99 statt 10: alle internen Chisel-Signale werden erfasst
    dut->trace(m_trace, 99);
    const char *vcd_path = getenv("SACRIS_V_VCD");
    m_trace->open(vcd_path ? vcd_path : "waveform.vcd");
#endif

    /* rtl_rf.log resolution:
     *   1. SACRIS_V_RF_LOG (explicit override; pass-through).
     *   2. $HOME/tmpfs/rtl_rf.log (RAM disk; default).
     *   3. ./rtl_rf.log (CWD fallback) iff $HOME/tmpfs is missing.
     * The log can grow to hundreds of MB during a long simulation,
     * so default it to RAM not the workspace SSD. */
    const char *rf_log_env = getenv("SACRIS_V_RF_LOG");
    std::string rf_log_path;
    if (rf_log_env) {
        rf_log_path = rf_log_env;
    } else {
        const char *home = getenv("HOME");
        std::string tmpfs_dir = std::string(home ? home : "") + "/tmpfs";
        struct stat st{};
        if (home && stat(tmpfs_dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
            rf_log_path = tmpfs_dir + "/rtl_rf.log";
        } else {
            std::cerr << "warning: $HOME/tmpfs not found; rtl_rf.log -> ./rtl_rf.log\n";
            rf_log_path = "rtl_rf.log";
        }
    }
    rf_log = fopen(rf_log_path.c_str(), "w");
#ifdef SACRIS_DUAL_ISSUE
    obs_log_path = getenv("SACRIS_V_ISSUE_OBSERVE");
#endif
#ifdef SACRIS_ISSUE_OBSERVE
    obs_csv_path = getenv("SACRIS_ISSUE_OBSERVE_CSV");
#endif
#ifdef SACRIS_BRANCH_OBSERVE
    branch_obs_csv_path = getenv("SACRIS_BRANCH_OBSERVE");
#endif
#ifdef SACRIS_CEXT_OBSERVE
    cext_obs_csv_path = getenv("SACRIS_CEXT_OBSERVE");
#endif
    pc_hist_path = getenv("SACRIS_V_PC_HIST");
    pc_hist_window_only = getenv("SACRIS_V_CSR_WINDOW") != nullptr;
    if (!rf_log) {
        perror(rf_log_path.c_str());
        delete dut;
        return 1;
    }

    dut->io_faultIRQ = false;
    dut->clock = 0;
    dut->io_uart16550_rx = 1;

    uint8_t prev_clk = 0;
    bool memLoaded = false;

    while (sim_time < (vluint64_t)MAX_SIM_TIME * 2)
    {
        dut_reset(dut, sim_time);

        prev_clk = dut->clock;
        uint8_t next_clk = prev_clk ^ 1;

        if (prev_clk == 0 && next_clk == 1) {
            uartTx.step((bool)dut->io_uart16550_tx);
        }

        dut->clock = next_clk;
        dut->eval();

        // Load memories after reset completes (sim_time >= 6) but before
        // the core starts fetching from the app region.  Loading after
        // reset avoids Verilator's internal memory initialization from
        // clobbering the pre-loaded binary data.
        if (directLoadMode && !memLoaded && sim_time == 6) {
#ifdef SACRIS_DUAL_ISSUE
            // Dual-issue build uses InstructionMem64 (16K x 64, packed pairs).
            if (!loadBinToMem64(imemPath,
                                dut->rootp->TopLevel__DOT__imem64__DOT__memory_ext__DOT__Memory)) {
                std::cerr << "ERROR: failed to load " << imemPath << "\n";
                delete dut;
                return 1;
            }
#else
            if (!loadBinToMem32(imemPath,
                                dut->rootp->TopLevel__DOT__imem__DOT__memory_ext__DOT__Memory)) {
                std::cerr << "ERROR: failed to load " << imemPath << "\n";
                delete dut;
                return 1;
            }
#endif
            if (!loadBinToMem32(dmemPath,
                                dut->rootp->TopLevel__DOT__dmem__DOT__memory__DOT__memory_ext__DOT__Memory)) {
                std::cerr << "ERROR: failed to load " << dmemPath << "\n";
                delete dut;
                return 1;
            }
#ifdef SACRIS_MEM_ECC
#ifndef SACRIS_DUAL_ISSUE
            secdedEncodeImem(dut->rootp->TopLevel__DOT__imem__DOT__memory_ext__DOT__Memory,
                             dut->rootp->TopLevel__DOT__imem__DOT__checkMem_ext__DOT__Memory);
#endif
            secdedEncodeDmem(dut->rootp->TopLevel__DOT__dmem__DOT__memory__DOT__memory_ext__DOT__Memory,
                             dut->rootp->TopLevel__DOT__dmem__DOT__memory__DOT__checkMem_ext__DOT__Memory);
#endif
            memLoaded = true;
        }

        // Log register writebacks on rising edge
        if (prev_clk == 0 && next_clk == 1) {
            uint8_t wb_addr = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_wbaddr;
            uint32_t wb_data = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_wbdata;

#ifdef SACRIS_RETIRE_TRACE
            // Pulse-gated emit: wb_commit_pulse is high for exactly one cycle
            // per architectural retire of a register-writing instruction
            // (Pipeline.scala, env-gated via SACRIS_V_RETIRE_TRACE). This is
            // the correct emit-once trigger — replaces the prior
            // (wb_addr, wb_data) consecutive-identity heuristic, which
            // over-suppressed legitimate same-data retires (see
            // Doc/research/c_extension_emu_diff_validation_v2.md §"Suggested
            // next direction").
            uint8_t wb_pulse = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_commit_pulse;
            if (rf_log && wb_pulse && wb_addr != 0) {
                fprintf(rf_log, "%llu 0x%08x x%u 0x%08x\n",
                        (unsigned long long)cycle_count, prev_mem_reg_pc, wb_addr, wb_data);
            }
#else
            // Legacy gating for the default (non-trace) build: emit on every
            // cycle where wb_reg_ctrl_rf_wen is high. This reproduces the
            // pre-7aac03d behaviour byte-for-byte. Stall-replay duplicates
            // are present here; only the SACRIS_RETIRE_TRACE build is
            // duplicate-free.
            uint8_t wb_en = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_ctrl_rf_wen;
            if (rf_log && wb_en && wb_addr != 0) {
                fprintf(rf_log, "%llu 0x%08x x%u 0x%08x\n",
                        (unsigned long long)cycle_count, prev_mem_reg_pc, wb_addr, wb_data);
            }
#endif

#ifdef SACRIS_DUAL_ISSUE
            // P1.3a Session 4: Slot-B commit watcher. Slot B never commits in
            // {false,false} (ex_reg_B_valid hardwired false → wb_reg_B_valid=0),
            // so the trace remains byte-identical pre-flip. Program order within
            // a pair is A-before-B; emit B immediately after A on the same edge.
            uint8_t wb_B_addr  = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_B_wbaddr;
            uint32_t wb_B_data = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_B_wbdata;
#ifdef SACRIS_RETIRE_TRACE
            uint8_t wb_B_pulse = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_B_commit_pulse;
            if (rf_log && wb_B_pulse && wb_B_addr != 0) {
                fprintf(rf_log, "%llu 0x%08x x%u 0x%08x\n",
                        (unsigned long long)cycle_count, prev_mem_reg_B_pc, wb_B_addr, wb_B_data);
            }
#else
            uint8_t wb_B_en    = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_B_ctrl_rf_wen;
            uint8_t wb_B_valid = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_B_valid;
            if (rf_log && wb_B_en && wb_B_valid && wb_B_addr != 0) {
                fprintf(rf_log, "%llu 0x%08x x%u 0x%08x\n",
                        (unsigned long long)cycle_count, prev_mem_reg_B_pc, wb_B_addr, wb_B_data);
            }
#endif
#endif

            if (pc_hist_path) {
                // Retire: the instruction whose PC/word the shadows hold has
                // just been written into the WB register with wb_reg_valid.
                // With SACRIS_V_CSR_WINDOW set (as the emulator takes it),
                // the histogram covers only the span between the first and
                // the second retired `csrrs rd, cycle, x0`, so code shared
                // with the init phase (memcpy, ...) is not over-counted.
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_valid) {
                    pc_hist_retired++;
                    // csrrs rd, cycle, x0: csr 0xC00, rs1 x0, funct3 010, SYSTEM
                    const bool is_csr_window = (prev_mem_reg_inst & 0xFFF0707Fu) == 0xC0002073u;
                    if (is_csr_window) {
                        fprintf(stderr, "csr_window %d cycle=%llu instret=%llu\n", pc_hist_windows++,
                                (unsigned long long)cycle_count, (unsigned long long)pc_hist_retired);
                        if (pc_hist_window_only) pc_hist_in_window = (pc_hist_windows == 1);
                    }
                    if (!pc_hist_window_only || pc_hist_in_window)
                        pc_hist[prev_mem_reg_pc].first++;
                }
#ifdef SACRIS_DUAL_ISSUE
                // Slot-B retire (plan phase 2A, 2026-10-21): wb_reg_B_valid
                // mirrors wb_reg_valid (Pipeline.scala, written under
                // !full_stall from mem_reg_B_valid), one cycle per retired
                // Slot-B instruction. A CSR instruction never issues in
                // Slot B (IssueLogic check 5), so the window markers above
                // stay on Slot A. The cycle below is still charged to the
                // Slot-A PC in EX: a pair costs one cycle, booked on A.
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__wb_reg_B_valid) {
                    pc_hist_retired++;
                    if (!pc_hist_window_only || pc_hist_in_window)
                        pc_hist[prev_mem_reg_B_pc].first++;
                }
#endif
                // Cycle: charged to the instruction in EX, or to the last one.
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__ex_reg_valid)
                    pc_hist_last_ex_pc = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__ex_reg_pc;
                if (!pc_hist_window_only || pc_hist_in_window)
                    pc_hist[pc_hist_last_ex_pc].second++;
                prev_mem_reg_inst = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__mem_reg_inst;
            }
            prev_mem_reg_pc = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__mem_reg_pc;
#ifdef SACRIS_DUAL_ISSUE
            prev_mem_reg_B_pc = dut->rootp->TopLevel__DOT__core__DOT__d__DOT__mem_reg_B_pc;
#endif
            cycle_count++;

#ifdef SACRIS_DUAL_ISSUE
            if (obs_log_path) {
                obs_cycles_observed++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issueLogic__DOT__io_issue_A)  obs_issue_A_count++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issueLogic__DOT__io_issue_B)  obs_issue_B_count++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issueLogic__DOT__io_stall_B)  obs_stall_B_count++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issueLogic__DOT__io_squash_B) obs_squash_B_count++;
            }
#endif
#ifdef SACRIS_ISSUE_OBSERVE
            if (obs_csv_path) {
                // P1.2.5: per-check reject + three-way outcome counters.
                // Signals live on the Pipeline module ("core.d") as dontTouch'd wires.
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check1_raw_intra)  obs_reject_check1_raw_intra++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check2_waw_intra)  obs_reject_check2_waw_intra++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check3_B_mem)      obs_reject_check3_B_mem++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check4_B_mdu)      obs_reject_check4_B_mdu++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check5_B_csr)      obs_reject_check5_B_csr++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check6_B_branch)   obs_reject_check6_B_branch++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check7_B_sync)     obs_reject_check7_B_sync++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check8_B_illegal)  obs_reject_check8_B_illegal++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check9_A_taken_br) obs_reject_check9_A_taken_br++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check10_B_zbc_zbs) obs_reject_check10_B_zbc_zbs++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_issued_pair)              obs_issued_pair++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_issued_single_A_only)     obs_issued_single_A_only++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_stalled_B)                obs_stalled_B++;
                if (dut->rootp->TopLevel__DOT__core__DOT__d__DOT__issue_observe_reject_check1_sole_fire)  obs_reject_check1_sole_fire++;
            }
#endif
        }

#ifdef DEBUG
        m_trace->dump(sim_time);
#endif

        if (dut->io_END) {
            std::cout << "Simulation ended by END signal at time " << sim_time << "\n";
            std::cout << "Final GPO: 0x" << std::hex << (unsigned)dut->io_GPO << std::dec << "\n";
#ifdef SACRIS_SAFETY_OBSERVE
            // FuSi phase 1b: end-of-sim scrape of the safety comparator/voter
            // status ports (present only when safetyMode != None — build with
            // -CFLAGS -DSACRIS_SAFETY_OBSERVE on such an elaboration). A clean
            // run must report mismatch=0 (no false detection); a comparator
            // self-test run must report mismatch=1.
            std::cout << "safety_observe mismatch=" << (unsigned)dut->io_safetyMismatch
                      << " err_irq=" << (unsigned)dut->io_safetyErrIRQ
#ifdef SACRIS_SAFETY_TMR
                      << " outvoted=0x" << std::hex << (unsigned)dut->io_safetyOutvoted << std::dec
#endif
                      << std::endl;
#endif
            pc_hist_dump();
            if (rf_log) fclose(rf_log);
#ifdef DEBUG
            m_trace->flush();
            m_trace->close();
#endif
#ifdef SACRIS_DUAL_ISSUE
            if (obs_log_path && obs_cycles_observed > 0) {
                FILE *obs = fopen(obs_log_path, "w");
                if (obs) {
                    fprintf(obs, "cycles_observed %llu\n", (unsigned long long)obs_cycles_observed);
                    fprintf(obs, "issue_A_count  %llu\n",  (unsigned long long)obs_issue_A_count);
                    fprintf(obs, "issue_B_count  %llu\n",  (unsigned long long)obs_issue_B_count);
                    fprintf(obs, "stall_B_count  %llu\n",  (unsigned long long)obs_stall_B_count);
                    fprintf(obs, "squash_B_count %llu\n",  (unsigned long long)obs_squash_B_count);
                    fprintf(obs, "issue_B_rate   %.3f\n",
                            (double)obs_issue_B_count / (double)obs_cycles_observed);
                    fclose(obs);
                }
            }
#endif
#ifdef SACRIS_BRANCH_OBSERVE
            {
                // Read the six 64-bit counters once at end-of-sim. They are
                // RTL accumulators (saturating at 0xFFFF_FFFF_FFFF_FFFF), so a
                // single read is sufficient — no per-cycle aggregation.
                uint64_t cnt_cbranch_red    = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_cbranch_redirect;
                uint64_t cnt_jalr_mp       = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_jalr_mispredict;
                uint64_t cnt_jalr_sup      = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_jalr_id_suppressed;
                uint64_t cnt_br_total      = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_branches_total;
                uint64_t cnt_jalr_redir    = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_jalr_redirect_total;
                uint64_t cnt_cbr_total     = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__branch_observe_cbranches_total;
                // Retired-instruction count from the minstret CSR (64-bit).
                // Used by the branch_cost calibration to normalise branches
                // per retired instruction.
                uint64_t cnt_minstret      = (uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__csr__DOT__minstretReg;
                std::cout << "branch_observe cbranch_redirect=" << cnt_cbranch_red
                          << " jalr_mispredict=" << cnt_jalr_mp
                          << " jalr_id_suppressed=" << cnt_jalr_sup
                          << " branches_total=" << cnt_br_total
                          << " jalr_redirect_total=" << cnt_jalr_redir
                          << " cbranches_total=" << cnt_cbr_total
                          << " minstret=" << cnt_minstret
                          << std::endl;
                if (branch_obs_csv_path) {
                    FILE *bcsv = fopen(branch_obs_csv_path, "w");
                    if (bcsv) {
                        fprintf(bcsv, "metric,count\n");
                        fprintf(bcsv, "cycles_observed,%llu\n",       (unsigned long long)cycle_count);
                        fprintf(bcsv, "cbranch_redirect,%llu\n",    (unsigned long long)cnt_cbranch_red);
                        fprintf(bcsv, "jalr_mispredict,%llu\n",       (unsigned long long)cnt_jalr_mp);
                        fprintf(bcsv, "jalr_id_suppressed,%llu\n",    (unsigned long long)cnt_jalr_sup);
                        fprintf(bcsv, "branches_total,%llu\n",        (unsigned long long)cnt_br_total);
                        fprintf(bcsv, "jalr_redirect_total,%llu\n",   (unsigned long long)cnt_jalr_redir);
                        fprintf(bcsv, "cbranches_total,%llu\n",       (unsigned long long)cnt_cbr_total);
                        fprintf(bcsv, "minstret,%llu\n",              (unsigned long long)cnt_minstret);
                        fclose(bcsv);
                    }
                }
            }
#endif
#ifdef SACRIS_ISSUE_OBSERVE
            if (obs_csv_path) {
                FILE *csv = fopen(obs_csv_path, "w");
                if (csv) {
                    fprintf(csv, "metric,count\n");
                    fprintf(csv, "cycles_observed,%llu\n",               (unsigned long long)cycle_count);
                    fprintf(csv, "issued_pair,%llu\n",                   (unsigned long long)obs_issued_pair);
                    fprintf(csv, "issued_single_A_only,%llu\n",          (unsigned long long)obs_issued_single_A_only);
                    fprintf(csv, "stalled_B,%llu\n",                     (unsigned long long)obs_stalled_B);
                    fprintf(csv, "reject_check1_raw_intra,%llu\n",       (unsigned long long)obs_reject_check1_raw_intra);
                    fprintf(csv, "reject_check2_waw_intra,%llu\n",       (unsigned long long)obs_reject_check2_waw_intra);
                    fprintf(csv, "reject_check3_B_mem,%llu\n",           (unsigned long long)obs_reject_check3_B_mem);
                    fprintf(csv, "reject_check4_B_mdu,%llu\n",           (unsigned long long)obs_reject_check4_B_mdu);
                    fprintf(csv, "reject_check5_B_csr,%llu\n",           (unsigned long long)obs_reject_check5_B_csr);
                    fprintf(csv, "reject_check6_B_branch,%llu\n",        (unsigned long long)obs_reject_check6_B_branch);
                    fprintf(csv, "reject_check7_B_sync,%llu\n",          (unsigned long long)obs_reject_check7_B_sync);
                    fprintf(csv, "reject_check8_B_illegal,%llu\n",       (unsigned long long)obs_reject_check8_B_illegal);
                    fprintf(csv, "reject_check9_A_taken_br,%llu\n",      (unsigned long long)obs_reject_check9_A_taken_br);
                    fprintf(csv, "reject_check10_B_zbc_zbs,%llu\n",      (unsigned long long)obs_reject_check10_B_zbc_zbs);
                    fprintf(csv, "reject_check1_sole_fire,%llu\n",       (unsigned long long)obs_reject_check1_sole_fire);
                    fclose(csv);
                }
            }
#endif
#ifdef SACRIS_CEXT_OBSERVE
            {
                // Eight saturating 64-bit counters + eight first-PC capture
                // regs + eight "captured" flags. Read once at end-of-sim:
                // they are pure RTL accumulators, so a single read is
                // authoritative. Names mirror the dontTouch'd Wire vals in
                // Pipeline.scala.
                #define CEXT_READ(name) ((uint64_t)dut->rootp->TopLevel__DOT__core__DOT__d__DOT__cext_observe_##name)
                uint64_t c_cexp_bubble_w     = CEXT_READ(cnt_cexp_bubble_w);
                uint64_t c_residual_valid    = CEXT_READ(cnt_residual_valid);
                uint64_t c_id_inst_diff      = CEXT_READ(cnt_id_inst_diff);
                uint64_t c_if_pc_next_diff   = CEXT_READ(cnt_if_pc_next_diff);
                uint64_t c_id_pc_diff        = CEXT_READ(cnt_id_pc_diff);
                uint64_t c_imem_addr_diff    = CEXT_READ(cnt_imem_addr_diff);
                uint64_t c_if_id_bubble_diff = CEXT_READ(cnt_if_id_bubble_diff);
                uint64_t c_id_valid_diff     = CEXT_READ(cnt_id_valid_diff);
                uint32_t pc_cexp_bubble_w     = (uint32_t)CEXT_READ(firstpc_cexp_bubble_w);
                uint32_t pc_residual_valid    = (uint32_t)CEXT_READ(firstpc_residual_valid);
                uint32_t pc_id_inst_diff      = (uint32_t)CEXT_READ(firstpc_id_inst_diff);
                uint32_t pc_if_pc_next_diff   = (uint32_t)CEXT_READ(firstpc_if_pc_next_diff);
                uint32_t pc_id_pc_diff        = (uint32_t)CEXT_READ(firstpc_id_pc_diff);
                uint32_t pc_imem_addr_diff    = (uint32_t)CEXT_READ(firstpc_imem_addr_diff);
                uint32_t pc_if_id_bubble_diff = (uint32_t)CEXT_READ(firstpc_if_id_bubble_diff);
                uint32_t pc_id_valid_diff     = (uint32_t)CEXT_READ(firstpc_id_valid_diff);
                uint8_t  cap_cexp_bubble_w     = (uint8_t)CEXT_READ(cap_cexp_bubble_w);
                uint8_t  cap_residual_valid    = (uint8_t)CEXT_READ(cap_residual_valid);
                uint8_t  cap_id_inst_diff      = (uint8_t)CEXT_READ(cap_id_inst_diff);
                uint8_t  cap_if_pc_next_diff   = (uint8_t)CEXT_READ(cap_if_pc_next_diff);
                uint8_t  cap_id_pc_diff        = (uint8_t)CEXT_READ(cap_id_pc_diff);
                uint8_t  cap_imem_addr_diff    = (uint8_t)CEXT_READ(cap_imem_addr_diff);
                uint8_t  cap_if_id_bubble_diff = (uint8_t)CEXT_READ(cap_if_id_bubble_diff);
                uint8_t  cap_id_valid_diff     = (uint8_t)CEXT_READ(cap_id_valid_diff);
                #undef CEXT_READ

                std::cout << "cext_observe cycles=" << cycle_count << "\n";
                #define DUMP(name) std::cout << "  " #name "  count=" << c_##name \
                    << "  first_pc=0x" << std::hex << pc_##name << std::dec \
                    << "  captured=" << (int)cap_##name << "\n"
                DUMP(cexp_bubble_w);
                DUMP(residual_valid);
                DUMP(id_inst_diff);
                DUMP(if_pc_next_diff);
                DUMP(id_pc_diff);
                DUMP(imem_addr_diff);
                DUMP(if_id_bubble_diff);
                DUMP(id_valid_diff);
                #undef DUMP

                if (cext_obs_csv_path) {
                    FILE *ccsv = fopen(cext_obs_csv_path, "w");
                    if (ccsv) {
                        fprintf(ccsv, "signal,count,first_pc,captured\n");
                        #define ROW(name) fprintf(ccsv, #name ",%llu,0x%08x,%u\n", \
                            (unsigned long long)c_##name, pc_##name, (unsigned)cap_##name)
                        ROW(cexp_bubble_w);
                        ROW(residual_valid);
                        ROW(id_inst_diff);
                        ROW(if_pc_next_diff);
                        ROW(id_pc_diff);
                        ROW(imem_addr_diff);
                        ROW(if_id_bubble_diff);
                        ROW(id_valid_diff);
                        #undef ROW
                        fprintf(ccsv, "cycles,%llu,0x00000000,1\n", (unsigned long long)cycle_count);
                        fclose(ccsv);
                    }
                }
            }
#endif
            delete dut;
            return 0;
        }

        sim_time++;
    }

    pc_hist_dump();
    if (rf_log) fclose(rf_log);
#ifdef DEBUG
    m_trace->close();
#endif

    delete dut;
    std::cout << "\n";
    return 0;
}
