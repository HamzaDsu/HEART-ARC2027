// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Memory map: base addresses, sizes and register offsets of all peripherals.

package sacris_v

object AddressMap {

  val DMEM_BASE: Long          = 0x00000000L
  val IMEM_BASE: Long          = 0x80000000L
  val BOOTROM_BASE: Long       = 0x00000000L  // instruction port only
  val BOOTROM_SIZE_WORDS: Int  = 512

  val MMIO_BASE: Long          = 0x02000000L

  val ACLINT_BASE: Long        = 0x02000000L
  val ACLINT_SIZE: Long        = 0x00010000L
  val ACLINT_MSIP_OFFSET: Long       = 0x0000L
  val ACLINT_MTIMECMP_LO_OFFSET: Long = 0x4000L
  val ACLINT_MTIMECMP_HI_OFFSET: Long = 0x4004L
  val ACLINT_MTIME_LO_OFFSET: Long   = 0xBFF8L
  val ACLINT_MTIME_HI_OFFSET: Long   = 0xBFFCL

  val ACLINT_MSWI_BASE: Long  = ACLINT_BASE + ACLINT_MSIP_OFFSET       // 0x02000000
  val ACLINT_MSWI_SIZE: Long  = 0x4000L                                 // MSIP register region
  val ACLINT_MTIMECMP_BASE: Long = ACLINT_BASE + ACLINT_MTIMECMP_LO_OFFSET // 0x02004000
  val ACLINT_MTIMECMP_SIZE: Long = 0x8L                                  // 64-bit mtimecmp
  val ACLINT_MTIME_BASE: Long = ACLINT_BASE + ACLINT_MTIME_LO_OFFSET   // 0x0200BFF8
  val ACLINT_MTIME_SIZE: Long = 0x8L                                     // 64-bit mtime

  val ACLINT_COUNTER_LO_OFFSET: Long = 0xC000L
  val ACLINT_COUNTER_HI_OFFSET: Long = 0xC004L

  val PLIC_BASE: Long          = 0x0C000000L
  val PLIC_SIZE: Long          = 0x00400000L
  val PLIC_NUM_SOURCES: Int    = 15
  val PLIC_MAX_PRIORITY: Int   = 7            // 3-bit priority field → levels 1..7
  val PLIC_SRC_UART16550: Int  = 1            // PLIC source ID of the 16550 UART

  val SEVENSEG_ADDR: Long      = 0x64000FF0L
  val SEVENSEG_DP_ADDR: Long   = 0x64000FF4L
  val GPO_ADDR: Long           = 0x64000FF8L
  val SIMULATION_END_ADDR: Long = 0x64000F04L

  val IMEM_WRITER_BASE: Long   = 0x65000000L
  val IMEM_WDATA_OFFSET: Long  = 0x00L
  val IMEM_COUNT_OFFSET: Long  = 0x04L

  val SPI_BASE: Long           = 0x66000000L
  val SPI_SIZE: Long           = 0x00000018L  // 6 registers × 4

  val I2C_BASE: Long           = 0x67000000L
  val I2C_SIZE: Long           = 0x0000000CL  // 3 registers × 4

  val WDT_BASE: Long           = 0x68000000L
  val WDT_SIZE: Long           = 0x0000001CL  // 7 registers × 4

  val UART16550_BASE: Long     = 0x69000000L
  val UART16550_SIZE: Long     = 0x00000020L  // 8 registers × 4 (reg-shift=2)
  val UART16550_REG_SHIFT: Int = 2

  val TEST_IRQ_ADDR: Long      = 0x6A000000L

  val SAFETY_BASE: Long        = 0x6B000000L
  val SAFETY_SIZE: Long        = 0x00000030L  // 12 registers × 4
  val SAFETY_STATUS_OFFSET: Long = 0x00L
  val SAFETY_CTRL_OFFSET: Long   = 0x04L
  val SAFETY_WALK_OFFSET: Long   = 0x08L
  val SAFETY_SG_OFFSET: Long     = 0x0CL
  val MEM_ECC_OFFSET: Long       = 0x10L
  val MEM_ECC_ADDR_OFFSET: Long  = 0x14L
  val IMEM_SCRUB_ADDR_OFFSET: Long = 0x18L

  val SAFETY_CKPT_OFFSET: Long     = 0x1CL
  val SAFETY_CKPT_PC_OFFSET: Long  = 0x20L

  val SAFETY_RESYNC_OFFSET: Long    = 0x24L
  val SAFETY_RESYNC_PC_OFFSET: Long = 0x28L

  val SAFETY_MG_OFFSET: Long        = 0x2CL

  val DMA_MEM_BASE: Long       = 0x30000000L
  val DMA_MEM_SIZE_DEFAULT: Long = 0x00010000L  // 64 KiB (actual size from p.dmaMemSize)

  val DMA_CTRL_BASE: Long      = 0x31000000L
  val DMA_CTRL_CHANNEL_SPACING: Long = 0x100L   // 256 bytes per channel
  val DMA_CTRL_SIZE: Long      = 0x00000020L    // 8 registers × 4 (single channel)

  val DMA_TEST_SOURCE_BASE: Long = 0x32000000L
  val DMA_TEST_SOURCE_SPACING: Long = 0x100L    // 256 bytes per instance
  val DMA_TEST_SOURCE_SIZE: Long = 0x00000020L  // 8 registers × 4 (single instance)
}
