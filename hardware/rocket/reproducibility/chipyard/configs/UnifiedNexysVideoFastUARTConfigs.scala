package chipyard.fpga.nexysvideo

import org.chipsalliance.cde.config.Config

/*
 * Experimental high-speed UART-TSI configurations.
 *
 * Preserve existing Rocket cache and core settings.
 * Override only the UART-TSI initialization baud rate.
 *
 * Physical UART reliability must be validated.
 */

class UnifiedResource1Core16K50FastUARTNexysVideoConfig
    extends Config(
  new testchipip.tsi.WithUARTTSIClient(
    initBaudRate = BigInt(921600)
  ) ++
  new UnifiedResource1Core16K50NexysVideoConfig
)

class UnifiedResource1Core32KFastUARTNexysVideoConfig
    extends Config(
  new testchipip.tsi.WithUARTTSIClient(
    initBaudRate = BigInt(921600)
  ) ++
  new UnifiedResource1Core32KNexysVideoConfig
)


class UnifiedResourceFastUARTNexysVideoConfig extends Config(
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = BigInt(921600)) ++
  new UnifiedResourceNexysVideoConfig
)


class UnifiedPowerFastUARTNexysVideoConfig extends Config(
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = BigInt(921600)) ++
  new UnifiedPowerNexysVideoConfig
)

class UnifiedPerformance2Core32KFastUARTNexysVideoConfig extends Config(
  new testchipip.tsi.WithUARTTSIClient(
    initBaudRate = BigInt(921600)
  ) ++
  new UnifiedPerformance2Core32KNexysVideoConfig
)
