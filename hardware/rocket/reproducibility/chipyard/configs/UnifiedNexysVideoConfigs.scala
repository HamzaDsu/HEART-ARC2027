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
class UnifiedResourceNexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(16) ++
  new RocketNexysVideoConfig
)

// Performance objective: 2 cores, proposed 32 KiB I/D, 50 MHz.
class UnifiedPerformance2Core32KNexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new freechips.rocketchip.rocket.WithNBigCores(2) ++
  new WithNexysVideoTweaks(freqMHz = 50.0) ++
  new chipyard.config.WithBroadcastManager ++
  new chipyard.config.AbstractConfig
)

// Power proxy objective: 1 core, proposed 8 KiB I/D,
// 25 MHz requested system clock.
// Clock implementation and DDR compatibility are unverified.
class UnifiedPowerNexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(16) ++
  new WithNexysVideoTweaks(freqMHz = 25.0) ++
  new chipyard.config.WithBroadcastManager ++
  new chipyard.RocketConfig
)

class UnifiedResource1Core16K25NexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(32) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(32) ++
  new WithNexysVideoTweaks(freqMHz = 25.0) ++
  new chipyard.config.WithBroadcastManager ++
  new chipyard.RocketConfig
)


// Controlled experiment: 1 core, 32 KiB I/D caches, 50 MHz.
class UnifiedResource1Core32KNexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(64) ++
  new RocketNexysVideoConfig
)

// Controlled experiment: 2 cores, 8 KiB I/D caches, 50 MHz.
class UnifiedPerformance2Core8KNexysVideoConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new freechips.rocketchip.rocket.WithNBigCores(2) ++
  new WithNexysVideoTweaks(freqMHz = 50.0) ++
  new chipyard.config.WithBroadcastManager ++
  new chipyard.config.AbstractConfig
)
