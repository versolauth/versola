package versola.util.postgres

import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** Pure config-parsing tests (no database required).
  *
  * Mirrors production wiring: a kebab-case [[zio.ConfigProvider]] over a HOCON
  * `postgres { }` block, loaded via `deriveConfig[PostgresConfig]`.
  */
object PostgresConfigSpec extends ZIOSpecDefault:

  private val postgresConfig =
    Config.Nested("postgres", deriveConfig[PostgresConfig])

  private val fullHocon =
    """postgres {
      |  url = "jdbc:postgresql://localhost:5432/auth"
      |  user = "dev"
      |  password = "1234"
      |  maximum-pool-size = 15
      |  minimum-idle = 15
      |  connection-timeout = "30 seconds"
      |  max-lifetime = "30 minutes"
      |  leak-detection-threshold = "0 seconds"
      |}""".stripMargin

  def spec = suite("PostgresConfig")(
    test("parses all pool-tuning fields from a kebab-case HOCON block") {
      for config <- TypesafeConfigProvider.fromHoconString(fullHocon).kebabCase.load(postgresConfig)
      yield assertTrue(
        config.url == "jdbc:postgresql://localhost:5432/auth",
        config.user == "dev",
        config.password == "1234",
        config.maximumPoolSize == 15,
        config.minimumIdle == 15,
        config.connectionTimeout == 30.seconds,
        config.maxLifetime == 30.minutes,
        config.leakDetectionThreshold == Duration.Zero,
      )
    },
    test("fails when a required tuning field is missing (no defaults)") {
      val incompleteHocon =
        """postgres {
          |  url = "jdbc:postgresql://localhost:5432/auth"
          |  user = "dev"
          |  password = "1234"
          |}""".stripMargin
      for result <- TypesafeConfigProvider.fromHoconString(incompleteHocon).kebabCase.load(postgresConfig).exit
      yield assertTrue(result.isFailure)
    },
  )
