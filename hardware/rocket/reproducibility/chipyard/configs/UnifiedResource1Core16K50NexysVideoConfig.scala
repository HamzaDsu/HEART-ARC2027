package chipyard.fpga.nexysvideo

import org.chipsalliance.cde.config.Config

/**
 * Unified model-FPGA optimizer candidate configurations.
 *
 * Generated from unified_search_nexys proposals.
 *
 * IMPORTANT:
 * Cache sets are provisionally mapped from the existing
 * HealthSLMBalancedNexysVideoConfig convention:
 * 32 sets described as 16 KiB.
 *
 * Actual cache capacity and core count require
 * post-elaboration verification.
 *
 * No complete-model FPGA inference has been validated.
 */

// Resource objective: 1 core, proposed 8 KiB I/D, 50 MHz.

// Matched FPGA experiment: same 32K base, 16K L1 I/D caches.
class UnifiedResource1Core16K50NexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(32) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(32) ++
  new RocketNexysVideoConfig
)
