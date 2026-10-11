package versola.cleanup

import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** The `cleanup { }` block as deployed today has neither stats key; it must keep loading. */
object CleanupConfigSpec extends ZIOSpecDefault:

  private val cleanupConfig = Config.Nested("cleanup", deriveConfig[CleanupConfig])

  private def load(hocon: String) =
    TypesafeConfigProvider.fromHoconString(hocon).kebabCase.load(cleanupConfig)

  def spec = suite("CleanupConfig")(
    test("a block without the stats keys loads with their defaults") {
      load(
        """cleanup {
          |  max-threads = 2
          |  tables = [
          |    { table-name = "sso_sessions", batch-size = 1000, interval = "10 minutes" }
          |  ]
          |}""".stripMargin,
      ).map(config => assertTrue(config.statsInterval == 1.minute, config.expiredCountCap == 100_000, config.tables.size == 1))
    },
    test("the stats keys can be set") {
      load(
        """cleanup {
          |  max-threads = 1
          |  tables = []
          |  stats-interval = "5 minutes"
          |  expired-count-cap = 500
          |}""".stripMargin,
      ).map(config => assertTrue(config.statsInterval == 5.minutes, config.expiredCountCap == 500))
    },
  )
