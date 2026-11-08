// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Named configurations (TopParams.all), selectable by name on the command line.

package sacris_v

trait ConfigPresets {

  lazy val I:     TopParams = TopParams()
  lazy val IM:    TopParams = I.copy(enableMExtension = true)
  lazy val IB:    TopParams = I.copy(enableBExtension = true)
  lazy val IMB:   TopParams = IM.copy(enableBExtension = true)
  lazy val IMC:   TopParams = IM.copy(enableCExtension = true)
  lazy val IMCB:  TopParams = IMB.copy(enableCExtension = true)
  lazy val IMF:   TopParams = IM.copy(enableFExtension = true)

  lazy val IMF_d2: TopParams = IMF.copy(fpDivStepsPerCycle = 2)
  lazy val IMFC:  TopParams = IMC.copy(enableFExtension = true)
  lazy val IMFB:  TopParams = IMB.copy(enableFExtension = true)
  lazy val IMFCB: TopParams = IMCB.copy(enableFExtension = true)

  lazy val DualI:    TopParams = I.copy(enableDualIssue = true)
  lazy val DualIM:   TopParams = IM.copy(enableDualIssue = true)
  lazy val DualIB:   TopParams = IB.copy(enableDualIssue = true)
  lazy val DualIMB:  TopParams = IMB.copy(enableDualIssue = true)
  lazy val DualIMF:  TopParams = IMF.copy(enableDualIssue = true)
  lazy val DualIMFB: TopParams = IMFB.copy(enableDualIssue = true)
  lazy val IMF_SLM:      TopParams = IMF.copy(dmemSize = 262144, withDmaMem = true, dmaMemSize = 262144)
  lazy val DualIMFB_SLM: TopParams = DualIMFB.copy(dmemSize = 262144, withDmaMem = true, dmaMemSize = 262144)
  // Health-SLM companions: no FPU (kernel is integer/bit work, float only in the per-block scale),
  // 128 KiB DMA memory like the Rocket Arty reference scratchpad (fits an FPGA, small variants only),
  // and TMR with the SLM memories (I_TMR_B3_F plus B).
  lazy val IMB_SLM:      TopParams = IMB.copy(dmemSize = 262144, withDmaMem = true, dmaMemSize = 262144)
  lazy val IMF_SLM_128K: TopParams = IMF.copy(withDmaMem = true, dmaMemSize = 32768)
  lazy val IMF_TMR_SLM:  TopParams = I_TMR_B3_F.copy(enableBExtension = true, dmemSize = 262144, withDmaMem = true, dmaMemSize = 262144)
  lazy val DualIMFB_SLM_128K: TopParams = DualIMFB_SLM.copy(dmemSize = 32768, dmaMemSize = 32768)
  lazy val IMB_SLM_128K: TopParams = IMB_SLM.copy(dmemSize = 32768, dmaMemSize = 32768)
  lazy val IMF_TMR_SLM_128K: TopParams = IMF_TMR_SLM.copy(dmemSize = 32768, dmaMemSize = 32768)

  lazy val DualIMFB_r2_d2: TopParams = DualIMFB.copy(
    fpDivStepsPerCycle = 2,
    divSteps = 2,
  )

  lazy val I_DCLS:       TopParams = I.copy(safetyMode = SafetyMode.DualLockstep)
  lazy val I_TMR:        TopParams = I.copy(safetyMode = SafetyMode.TripleVoting)
  lazy val DualIMB_DCLS: TopParams = DualIMB.copy(safetyMode = SafetyMode.DualLockstep)

  lazy val I_DCLS_B2: TopParams = I_DCLS.copy(safetyStoreGate = true, withMemEcc = true)
  lazy val I_TMR_B2:  TopParams = I_TMR.copy(safetyStoreGate = true, withMemEcc = true)

  lazy val I_DCLS_B3: TopParams = I_DCLS_B2.copy(safetyRollback = true)

  lazy val I_DCLS_B3_fast: TopParams = I_DCLS_B3.copy(safetyCkptPeriod = 256)

  lazy val I_TMR_B3: TopParams = I_TMR_B2.copy(safetyResync = true)

  lazy val I_TMR_B3_SCRUB: TopParams = I_TMR_B3.copy(safetyRegfileScrub = true)

  lazy val I_TMR_B3_SCRUB_SC: TopParams = I_TMR_B3_SCRUB.copy(regfileScrubSelfCheck = true)

  lazy val I_TMR_B3_SCRUB_SC_DEFEAT: TopParams =
    I_TMR_B3_SCRUB_SC.copy(regfileScrubSelfCheckDefeat = true, regfileScrubSelfCheckSweeps = 2)

  lazy val I_TMR_B3_ECC: TopParams = I_TMR_B3.copy(safetyRegfileEcc = true)
  lazy val I_TMR_B3_ECC_SCRUB: TopParams = I_TMR_B3.copy(safetyRegfileEcc = true, safetyRegfileScrub = true)

  lazy val I_TMR_B3_slow: TopParams = I_TMR_B3.copy(lockstepDelay = 41, safetyResyncPcDelay = 4, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 3)

  lazy val I_TMR_B3_L6: TopParams = I_TMR_B3.copy(lockstepDelay = 6, safetyResyncPcDelay = 4, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 3)
  lazy val I_TMR_B3_L7: TopParams = I_TMR_B3.copy(lockstepDelay = 7, safetyResyncPcDelay = 4, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 3)

  lazy val I_TMR_B3_d2pc1mc0: TopParams = I_TMR_B3.copy(safetyResyncPcDelay = 1, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 0)
  lazy val I_TMR_B3_d2pc0mc0: TopParams = I_TMR_B3.copy(safetyResyncPcDelay = 0, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 0)
  lazy val I_TMR_B3_d2pc1mc1: TopParams = I_TMR_B3.copy(safetyResyncPcDelay = 1, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 1)

  lazy val I_TMR_B3_L12pc1mc0: TopParams = I_TMR_B3.copy(lockstepDelay = 12, safetyResyncPcDelay = 1, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 0)
  lazy val I_TMR_B3_L12pc2mc1: TopParams = I_TMR_B3.copy(lockstepDelay = 12, safetyResyncPcDelay = 2, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 1)
  lazy val I_TMR_B3_L12pc3mc2: TopParams = I_TMR_B3.copy(lockstepDelay = 12, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_L12pc4mc3: TopParams = I_TMR_B3.copy(lockstepDelay = 12, safetyResyncPcDelay = 4, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 3)
  lazy val I_TMR_B3_L12pc5mc4: TopParams = I_TMR_B3.copy(lockstepDelay = 12, safetyResyncPcDelay = 5, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 4)

  lazy val I_TMR_B3_L4pc3mc2: TopParams = I_TMR_B3.copy(lockstepDelay = 4, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_L5pc3mc2: TopParams = I_TMR_B3.copy(lockstepDelay = 5, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_L6pc3mc2: TopParams = I_TMR_B3.copy(lockstepDelay = 6, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)

  lazy val I_TMR_B3_F: TopParams = I_TMR_B3.copy(
    lockstepDelay = 6, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0,
    safetyResyncMcycleDelay = 2, enableFExtension = true, enableMExtension = true)
  lazy val I_TMR_B3_F_nogate: TopParams = I_TMR_B3_F.copy(safetyResyncFpuGate = false)

  lazy val I_TMR_B3_F_slow: TopParams = I_TMR_B3_slow.copy(enableFExtension = true, enableMExtension = true)
  lazy val I_TMR_B3_F_slow_nogate: TopParams = I_TMR_B3_F_slow.copy(safetyResyncFpuGate = false)

  lazy val I_TMR_B3_F_w1off: TopParams =
    I_TMR_B3_F.copy(safetyResyncReleaseGate = false, safetyResyncFpRestore = false)

  lazy val I_TMR_B3_F_slow_w1off: TopParams =
    I_TMR_B3_F_slow.copy(safetyResyncReleaseGate = false, safetyResyncFpRestore = false)

  lazy val I_TMR_B3_Q: TopParams = I_TMR_B3.copy(safetyResyncQuiesceAll = true)
  lazy val I_TMR_B3_Q_L1: TopParams = I_TMR_B3.copy(safetyResyncQuiesceAll = true,
    lockstepDelay = 1, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_Q_L2: TopParams = I_TMR_B3.copy(safetyResyncQuiesceAll = true,
    lockstepDelay = 2, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_Q_L4: TopParams = I_TMR_B3.copy(safetyResyncQuiesceAll = true,
    lockstepDelay = 4, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)
  lazy val I_TMR_B3_Q_L6: TopParams = I_TMR_B3.copy(safetyResyncQuiesceAll = true,
    lockstepDelay = 6, safetyResyncPcDelay = 3, safetyResyncCsrDelay = 0, safetyResyncMcycleDelay = 2)

  lazy val I_TMR_B3_F_Q: TopParams = I_TMR_B3_F.copy(safetyResyncQuiesceAll = true)
  lazy val I_TMR_B3_F_Q_reloff: TopParams = I_TMR_B3_F_Q.copy(safetyResyncReleaseGate = false)

  lazy val I_TMR_B3_F_Q_L2: TopParams = I_TMR_B3_F.copy(safetyResyncQuiesceAll = true, lockstepDelay = 2)

  lazy val I_TMR_B3_IF:     TopParams = I_TMR_B3.copy(safetyResyncInflight = true, safetyResyncInflightReleaseAdj = 1)

  lazy val I_TMR_B3_IF_SCRUB: TopParams = I_TMR_B3_IF.copy(safetyRegfileScrub = true)

  lazy val I_TMR_B3_IF_SCRUB_SC: TopParams = I_TMR_B3_IF_SCRUB.copy(regfileScrubSelfCheck = true)

  lazy val I_TMR_B3_IF_ECC: TopParams = I_TMR_B3_IF.copy(safetyRegfileEcc = true)
  lazy val I_TMR_B3_IF_ECC_SCRUB: TopParams = I_TMR_B3_IF.copy(safetyRegfileEcc = true, safetyRegfileScrub = true)
  lazy val I_TMR_B3_IF_L12: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 12, safetyResyncInflightReleaseAdj = 1)

  lazy val I_TMR_B3_IF_L12a1: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 12, safetyResyncInflightReleaseAdj = 1)
  lazy val I_TMR_B3_IF_L12a2: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 12, safetyResyncInflightReleaseAdj = 2)
  lazy val I_TMR_B3_IF_L12a3: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 12, safetyResyncInflightReleaseAdj = 3)
  lazy val I_TMR_B3_IF_L6:  TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 6, safetyResyncInflightReleaseAdj = 1)
  lazy val I_TMR_B3_IF_L4:  TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 4, safetyResyncInflightReleaseAdj = 1)

  lazy val I_TMR_B3_IF_L4a0: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 4, safetyResyncInflightReleaseAdj = 0)
  lazy val I_TMR_B3_IF_L4a2: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 4, safetyResyncInflightReleaseAdj = 2)
  lazy val I_TMR_B3_IF_L4a3: TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 4, safetyResyncInflightReleaseAdj = 3)

  lazy val I_TMR_B3_IF_L5:  TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 5, safetyResyncInflightReleaseAdj = 1)
  lazy val I_TMR_B3_IF_L3:  TopParams = I_TMR_B3.copy(safetyResyncInflight = true, lockstepDelay = 3, safetyResyncInflightReleaseAdj = 1)

  lazy val I_TMR_B3_IF_F:    TopParams = I_TMR_B3_F.copy(safetyResyncInflight = true, safetyResyncReleaseGate = false, safetyResyncInflightReleaseAdj = 1)
  lazy val I_TMR_B3_IF_F_L2: TopParams = I_TMR_B3_F.copy(safetyResyncInflight = true, safetyResyncReleaseGate = false, lockstepDelay = 2, safetyResyncInflightReleaseAdj = 1)

  lazy val I_TMR_B3_MMIO: TopParams = I_TMR_B3.copy(safetyMmioGateSet = MmioGateTarget.terminal)

  lazy val I_TMR_B3_MMIO_FIFO: TopParams = I_TMR_B3_MMIO.copy(safetyMmioGateSet = MmioGateTarget.all)

  lazy val I_TMR_B3_MMIO_SPI_OFF: TopParams =
    I_TMR_B3.copy(withSPI = true, safetyMmioGateSet = MmioGateTarget.terminal)
  lazy val I_TMR_B3_MMIO_SPI: TopParams =
    I_TMR_B3.copy(withSPI = true, safetyMmioGateSet = MmioGateTarget.terminal + MmioGateTarget.SpiData)
  lazy val I_TMR_B3_MMIO_I2C_OFF: TopParams =
    I_TMR_B3.copy(withI2C = true, safetyMmioGateSet = MmioGateTarget.terminal)
  lazy val I_TMR_B3_MMIO_I2C: TopParams =
    I_TMR_B3.copy(withI2C = true, safetyMmioGateSet = MmioGateTarget.terminal + MmioGateTarget.I2cCmd)

  lazy val I_TMR_B3_MMIO_PLIC: TopParams =
    I_TMR_B3.copy(safetyMmioGateSet = MmioGateTarget.terminal + MmioGateTarget.PlicCfg)
  lazy val I_TMR_B3_MMIO_MSIP: TopParams =
    I_TMR_B3.copy(safetyMmioGateSet = MmioGateTarget.terminal + MmioGateTarget.AclintMsip)

  lazy val I_TMR_B3_MMIO_SCR: TopParams =
    I_TMR_B3.copy(safetyMmioGateSet = MmioGateTarget.terminal + MmioGateTarget.UartScr)
  lazy val I_TMR_B3_MMIO_UARTCFG: TopParams =
    I_TMR_B3.copy(safetyMmioGateSet = MmioGateTarget.all +
      MmioGateTarget.UartScr + MmioGateTarget.UartCfg)

  lazy val I_DCLS_B3_MMIO: TopParams =
    I_DCLS_B3.copy(safetyMmioGateSet = MmioGateTarget.terminal)

  lazy val I_DCLS_B3_MMIO_fast: TopParams =
    I_DCLS_B3_MMIO.copy(safetyCkptPeriod = 256)

  lazy val I_DCLS_B3_MMIO_EFI: TopParams =
    I_DCLS_B3_MMIO.copy(safetyEgressFi = true)

  lazy val I_DCLS_B3_MMIO_EC: TopParams =
    I_DCLS_B3_MMIO.copy(safetyEgressCheck = true)
  lazy val I_DCLS_B3_MMIO_ECB: TopParams =
    I_DCLS_B3_MMIO_EC.copy(safetyEgressBlock = true)

  lazy val I_DCLS_B3_MMIO_EFI_ECB: TopParams =
    I_DCLS_B3_MMIO_EFI.copy(safetyEgressCheck = true, safetyEgressBlock = true)

  lazy val I_DCLS_B3_MMIO_ECB_fast: TopParams =
    I_DCLS_B3_MMIO_ECB.copy(safetyCkptPeriod = 256)

  lazy val I_DCLS_B3_MMIO_MG: TopParams =
    I_DCLS_B3_MMIO.copy(safetyMmioDropCounter = true)
  lazy val I_DCLS_B3_MMIO_EFI_MG: TopParams =
    I_DCLS_B3_MMIO_EFI.copy(safetyMmioDropCounter = true)

  lazy val I_TMR_B3_MMIO_STAT: TopParams =
    I_TMR_B3_MMIO_FIFO.copy(safetyMmioFwdStatusSet = Set(MmioGateTarget.UartThr))
  lazy val I_TMR_B3_MMIO_STAT_SPI: TopParams =
    I_TMR_B3_MMIO_SPI.copy(safetyMmioFwdStatusSet = Set(MmioGateTarget.SpiData))
  lazy val I_TMR_B3_MMIO_STAT_I2C: TopParams =
    I_TMR_B3_MMIO_I2C.copy(safetyMmioFwdStatusSet = Set(MmioGateTarget.I2cCmd))

  lazy val I_TMR_B3_VFI: TopParams = I_TMR_B3.copy(safetyVoterFi = true)
  lazy val I_TMR_B3_MMIO_VFI: TopParams = I_TMR_B3_MMIO.copy(safetyVoterFi = true)

  lazy val I_TMR_B3_V2A:       TopParams = I_TMR_B3.copy(safetyWalkRsGate = true)
  lazy val I_TMR_B3_V2AB:      TopParams = I_TMR_B3_V2A.copy(safetyWalkExt = true)
  lazy val I_TMR_B3_MMIO_V2AB: TopParams = I_TMR_B3_MMIO.copy(safetyWalkRsGate = true, safetyWalkExt = true)
  lazy val I_TMR_B3_MMIO_V2NB: TopParams = I_TMR_B3_MMIO_V2AB.copy(safetyEgressCheck = true)
  lazy val I_TMR_B3_MMIO_V2:   TopParams = I_TMR_B3_MMIO_V2NB.copy(safetyEgressBlock = true)
  lazy val I_TMR_B3_VFI_V2A:   TopParams = I_TMR_B3_VFI.copy(safetyWalkRsGate = true)
  lazy val I_TMR_B3_VFI_V2:    TopParams = I_TMR_B3_VFI_V2A.copy(safetyWalkExt = true)
  lazy val I_TMR_B3_MMIO_VFI_V2: TopParams = I_TMR_B3_MMIO_VFI.copy(
    safetyWalkRsGate = true, safetyWalkExt = true,
    safetyEgressCheck = true, safetyEgressBlock = true)

  lazy val all: Map[String, TopParams] = Map(
    "I"        -> I,
    "IM"       -> IM,
    "IB"       -> IB,
    "IMB"      -> IMB,
    "IMC"      -> IMC,
    "IMCB"     -> IMCB,
    "IMF"      -> IMF,
    "IMF_d2"   -> IMF_d2,
    "IMFC"     -> IMFC,
    "IMFB"     -> IMFB,
    "IMFCB"    -> IMFCB,
    "DualI"    -> DualI,
    "DualIM"   -> DualIM,
    "DualIB"   -> DualIB,
    "DualIMB"  -> DualIMB,
    "DualIMF"  -> DualIMF,
    "DualIMFB" -> DualIMFB,
    "DualIMFB_r2_d2" -> DualIMFB_r2_d2,
    "IMF_SLM"      -> IMF_SLM,
    "DualIMFB_SLM" -> DualIMFB_SLM,
    "IMB_SLM"      -> IMB_SLM,
    "IMF_SLM_128K" -> IMF_SLM_128K,
    "IMF_TMR_SLM"  -> IMF_TMR_SLM,
    "DualIMFB_SLM_128K" -> DualIMFB_SLM_128K,
    "IMB_SLM_128K" -> IMB_SLM_128K,
    "IMF_TMR_SLM_128K" -> IMF_TMR_SLM_128K,
    "I_DCLS"       -> I_DCLS,
    "I_TMR"        -> I_TMR,
    "DualIMB_DCLS" -> DualIMB_DCLS,
    "I_DCLS_B2"    -> I_DCLS_B2,
    "I_TMR_B2"     -> I_TMR_B2,
    "I_DCLS_B3"    -> I_DCLS_B3,
    "I_DCLS_B3_fast" -> I_DCLS_B3_fast,
    "I_TMR_B3"     -> I_TMR_B3,
    "I_TMR_B3_SCRUB" -> I_TMR_B3_SCRUB,
    "I_TMR_B3_SCRUB_SC" -> I_TMR_B3_SCRUB_SC,
    "I_TMR_B3_SCRUB_SC_DEFEAT" -> I_TMR_B3_SCRUB_SC_DEFEAT,
    "I_TMR_B3_IF_SCRUB" -> I_TMR_B3_IF_SCRUB,
    "I_TMR_B3_IF_SCRUB_SC" -> I_TMR_B3_IF_SCRUB_SC,
    "I_TMR_B3_ECC"  -> I_TMR_B3_ECC,
    "I_TMR_B3_ECC_SCRUB" -> I_TMR_B3_ECC_SCRUB,
    "I_TMR_B3_IF_ECC" -> I_TMR_B3_IF_ECC,
    "I_TMR_B3_IF_ECC_SCRUB" -> I_TMR_B3_IF_ECC_SCRUB,
    "I_TMR_B3_slow" -> I_TMR_B3_slow,
    "I_TMR_B3_L6"  -> I_TMR_B3_L6,
    "I_TMR_B3_L7"  -> I_TMR_B3_L7,
    "I_TMR_B3_d2pc1mc0" -> I_TMR_B3_d2pc1mc0,
    "I_TMR_B3_d2pc0mc0" -> I_TMR_B3_d2pc0mc0,
    "I_TMR_B3_d2pc1mc1" -> I_TMR_B3_d2pc1mc1,
    "I_TMR_B3_L12pc1mc0" -> I_TMR_B3_L12pc1mc0,
    "I_TMR_B3_L12pc2mc1" -> I_TMR_B3_L12pc2mc1,
    "I_TMR_B3_L12pc3mc2" -> I_TMR_B3_L12pc3mc2,
    "I_TMR_B3_L12pc4mc3" -> I_TMR_B3_L12pc4mc3,
    "I_TMR_B3_L12pc5mc4" -> I_TMR_B3_L12pc5mc4,
    "I_TMR_B3_L4pc3mc2" -> I_TMR_B3_L4pc3mc2,
    "I_TMR_B3_L5pc3mc2" -> I_TMR_B3_L5pc3mc2,
    "I_TMR_B3_L6pc3mc2" -> I_TMR_B3_L6pc3mc2,
    "I_TMR_B3_F"        -> I_TMR_B3_F,
    "I_TMR_B3_F_nogate" -> I_TMR_B3_F_nogate,
    "I_TMR_B3_F_slow"   -> I_TMR_B3_F_slow,
    "I_TMR_B3_F_slow_nogate" -> I_TMR_B3_F_slow_nogate,
    "I_TMR_B3_F_w1off"  -> I_TMR_B3_F_w1off,
    "I_TMR_B3_F_slow_w1off" -> I_TMR_B3_F_slow_w1off,
    "I_TMR_B3_Q"        -> I_TMR_B3_Q,
    "I_TMR_B3_Q_L1"     -> I_TMR_B3_Q_L1,
    "I_TMR_B3_Q_L2"     -> I_TMR_B3_Q_L2,
    "I_TMR_B3_Q_L4"     -> I_TMR_B3_Q_L4,
    "I_TMR_B3_Q_L6"     -> I_TMR_B3_Q_L6,
    "I_TMR_B3_F_Q"      -> I_TMR_B3_F_Q,
    "I_TMR_B3_F_Q_reloff" -> I_TMR_B3_F_Q_reloff,
    "I_TMR_B3_F_Q_L2"   -> I_TMR_B3_F_Q_L2,
    "I_TMR_B3_IF"       -> I_TMR_B3_IF,
    "I_TMR_B3_IF_L12"   -> I_TMR_B3_IF_L12,
    "I_TMR_B3_IF_L12a1" -> I_TMR_B3_IF_L12a1,
    "I_TMR_B3_IF_L12a2" -> I_TMR_B3_IF_L12a2,
    "I_TMR_B3_IF_L12a3" -> I_TMR_B3_IF_L12a3,
    "I_TMR_B3_IF_L6"    -> I_TMR_B3_IF_L6,
    "I_TMR_B3_IF_L5"    -> I_TMR_B3_IF_L5,
    "I_TMR_B3_IF_L4"    -> I_TMR_B3_IF_L4,
    "I_TMR_B3_IF_L4a0"  -> I_TMR_B3_IF_L4a0,
    "I_TMR_B3_IF_L4a2"  -> I_TMR_B3_IF_L4a2,
    "I_TMR_B3_IF_L4a3"  -> I_TMR_B3_IF_L4a3,
    "I_TMR_B3_IF_L3"    -> I_TMR_B3_IF_L3,
    "I_TMR_B3_IF_F"     -> I_TMR_B3_IF_F,
    "I_TMR_B3_IF_F_L2"  -> I_TMR_B3_IF_F_L2,
    "I_TMR_B3_MMIO"     -> I_TMR_B3_MMIO,
    "I_DCLS_B3_MMIO"    -> I_DCLS_B3_MMIO,
    "I_DCLS_B3_MMIO_fast" -> I_DCLS_B3_MMIO_fast,
    "I_DCLS_B3_MMIO_EFI" -> I_DCLS_B3_MMIO_EFI,
    "I_DCLS_B3_MMIO_EC"  -> I_DCLS_B3_MMIO_EC,
    "I_DCLS_B3_MMIO_ECB" -> I_DCLS_B3_MMIO_ECB,
    "I_DCLS_B3_MMIO_EFI_ECB" -> I_DCLS_B3_MMIO_EFI_ECB,
    "I_DCLS_B3_MMIO_ECB_fast" -> I_DCLS_B3_MMIO_ECB_fast,
    "I_DCLS_B3_MMIO_MG" -> I_DCLS_B3_MMIO_MG,
    "I_DCLS_B3_MMIO_EFI_MG" -> I_DCLS_B3_MMIO_EFI_MG,
    "I_TMR_B3_MMIO_FIFO" -> I_TMR_B3_MMIO_FIFO,
    "I_TMR_B3_MMIO_SPI_OFF" -> I_TMR_B3_MMIO_SPI_OFF,
    "I_TMR_B3_MMIO_SPI" -> I_TMR_B3_MMIO_SPI,
    "I_TMR_B3_MMIO_I2C_OFF" -> I_TMR_B3_MMIO_I2C_OFF,
    "I_TMR_B3_MMIO_I2C" -> I_TMR_B3_MMIO_I2C,
    "I_TMR_B3_MMIO_PLIC" -> I_TMR_B3_MMIO_PLIC,
    "I_TMR_B3_MMIO_MSIP" -> I_TMR_B3_MMIO_MSIP,
    "I_TMR_B3_MMIO_SCR" -> I_TMR_B3_MMIO_SCR,
    "I_TMR_B3_MMIO_UARTCFG" -> I_TMR_B3_MMIO_UARTCFG,
    "I_TMR_B3_MMIO_STAT" -> I_TMR_B3_MMIO_STAT,
    "I_TMR_B3_MMIO_STAT_SPI" -> I_TMR_B3_MMIO_STAT_SPI,
    "I_TMR_B3_MMIO_STAT_I2C" -> I_TMR_B3_MMIO_STAT_I2C,
    "I_TMR_B3_VFI"      -> I_TMR_B3_VFI,
    "I_TMR_B3_MMIO_VFI" -> I_TMR_B3_MMIO_VFI,
    "I_TMR_B3_V2A"        -> I_TMR_B3_V2A,
    "I_TMR_B3_V2AB"       -> I_TMR_B3_V2AB,
    "I_TMR_B3_MMIO_V2AB"  -> I_TMR_B3_MMIO_V2AB,
    "I_TMR_B3_MMIO_V2NB"  -> I_TMR_B3_MMIO_V2NB,
    "I_TMR_B3_MMIO_V2"    -> I_TMR_B3_MMIO_V2,
    "I_TMR_B3_VFI_V2A"    -> I_TMR_B3_VFI_V2A,
    "I_TMR_B3_VFI_V2"     -> I_TMR_B3_VFI_V2,
    "I_TMR_B3_MMIO_VFI_V2" -> I_TMR_B3_MMIO_VFI_V2,

    "DualIMB_BOOTREG" -> DualIMB.copy(bootRomRegisteredData = true),
    "IMB_BOOTREG"     -> IMB.copy(bootRomRegisteredData = true),

    "IMB_STALLREG"     -> IMB.copy(frontendStallAddrFromReg = true),
    "DualIMB_STALLREG" -> DualIMB.copy(frontendStallAddrFromReg = true),

    "DualIMB_BOOTREG_STALLREG" ->
      DualIMB.copy(bootRomRegisteredData = true, frontendStallAddrFromReg = true),
    "IMB_BOOTREG_STALLREG"     ->
      IMB.copy(bootRomRegisteredData = true, frontendStallAddrFromReg = true),

    "IMB_DADDR"     -> IMB.copy(dmemAddrFromAdder = true),
    "DualIMB_DADDR" -> DualIMB.copy(dmemAddrFromAdder = true),

    "DualIMB_BOOTREG_STALLREG_DADDR" -> DualIMB.copy(
      bootRomRegisteredData = true, frontendStallAddrFromReg = true, dmemAddrFromAdder = true),
    "IMB_BOOTREG_STALLREG_DADDR"     -> IMB.copy(
      bootRomRegisteredData = true, frontendStallAddrFromReg = true, dmemAddrFromAdder = true),

    "IMB_MDUSPLIT"     -> IMB.copy(mduSplitPartials = true),
    "DualIMB_MDUSPLIT" -> DualIMB.copy(mduSplitPartials = true),

    "DualIMB_BOOTREG_STALLREG_DADDR_MDUSPLIT" -> DualIMB.copy(
      bootRomRegisteredData = true, frontendStallAddrFromReg = true,
      dmemAddrFromAdder = true, mduSplitPartials = true),
    "IMB_BOOTREG_STALLREG_DADDR_MDUSPLIT"     -> IMB.copy(
      bootRomRegisteredData = true, frontendStallAddrFromReg = true,
      dmemAddrFromAdder = true, mduSplitPartials = true),

    "IMB_DADDR_MDUSPLIT" -> IMB.copy(
      dmemAddrFromAdder = true, mduSplitPartials = true),
    "IMB_BOOTREG_DADDR_MDUSPLIT" -> IMB.copy(
      bootRomRegisteredData = true, dmemAddrFromAdder = true, mduSplitPartials = true),

    "DualIMB_M4REG"         -> DualIMB.copy(frontendMinus4FromReg = true),
    "DualIMB_BOOTREG_M4REG" -> DualIMB.copy(
      bootRomRegisteredData = true, frontendMinus4FromReg = true),

    "IMB_FEOPEN"             -> IMB.copy(fetchEnableOpen = true),
    "IMB_BOOTREG_FEOPEN"     -> IMB.copy(
      bootRomRegisteredData = true, fetchEnableOpen = true),
    "DualIMB_FEOPEN"         -> DualIMB.copy(fetchEnableOpen = true),
    "DualIMB_BOOTREG_FEOPEN" -> DualIMB.copy(
      bootRomRegisteredData = true, fetchEnableOpen = true),

    "DualIMB_BOOTREG_MDUSPLIT" -> DualIMB.copy(
      bootRomRegisteredData = true, mduSplitPartials = true),

    "DualIMB_BOOTREG_DADDR" -> DualIMB.copy(
      bootRomRegisteredData = true, dmemAddrFromAdder = true),
    "DualIMB_BOOTREG_DADDR_MDUSPLIT" -> DualIMB.copy(
      bootRomRegisteredData = true, dmemAddrFromAdder = true, mduSplitPartials = true),
  )
}
