package chipyard

import org.chipsalliance.cde.config.{Config}

// V5-aligned huge-core Rocket RTL simulation configurations.
// No L2 or scratchpad; 8-way I/D caches with 64-byte blocks.
//
// These configurations align core/cache parameters with V5,
// but do not reproduce the full Nexys Video FPGA platform.

class HealthSLMV5HugeNoScratchpadSimConfig extends Config(
  new testchipip.soc.WithNoScratchpads ++
  new chipyard.config.WithBroadcastManager ++
  new freechips.rocketchip.subsystem.WithNBanks(1) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)

class HealthSLMV5Huge8KiBL1SimConfig extends Config(
  new freechips.rocketchip.rocket.WithL1ICacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(16) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new chipyard.HealthSLMV5HugeNoScratchpadSimConfig
)

class HealthSLMV5Huge16KiBL1SimConfig extends Config(
  new freechips.rocketchip.rocket.WithL1ICacheSets(32) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(32) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new chipyard.HealthSLMV5HugeNoScratchpadSimConfig
)

class HealthSLMV5Huge32KiBL1SimConfig extends Config(
  new freechips.rocketchip.rocket.WithL1ICacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1ICacheWays(8) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(64) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(8) ++
  new chipyard.HealthSLMV5HugeNoScratchpadSimConfig
)
