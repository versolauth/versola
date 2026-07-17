package versola.util.postgres

import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** Pure config-parsing and pool-validation tests (no database required).
  *
  * Parsing mirrors production wiring: a kebab-case [[zio.ConfigProvider]] over a HOCON
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

  private val validConfig = PostgresConfig(
    url = "jdbc:postgresql://localhost:5432/auth",
    user = "dev",
    password = "1234",
    maximumPoolSize = 10,
    minimumIdle = 10,
    connectionTimeout = 30.seconds,
    maxLifetime = 30.minutes,
    leakDetectionThreshold = Duration.Zero,
  )

  def spec = suite("PostgresConfig")(
    suite("parsing")(
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
    ),
    suite("PostgresHikariDataSource.validate")(
      test("accepts a well-formed config") {
        assertTrue(PostgresHikariDataSource.validate(validConfig) == Right(()))
      },
      test("rejects maximumPoolSize <= 0") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(maximumPoolSize = 0)).isLeft)
      },
      test("rejects minimumIdle > maximumPoolSize") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(minimumIdle = 11)).isLeft)
      },
      test("rejects minimumIdle < 0") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(minimumIdle = -1)).isLeft)
      },
      test("rejects connectionTimeout below 250ms") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(connectionTimeout = 100.millis)).isLeft)
      },
      test("rejects maxLifetime between 0 (exclusive) and 30 seconds") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(maxLifetime = 5.seconds)).isLeft)
      },
      test("accepts maxLifetime == 0 (disabled)") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(maxLifetime = Duration.Zero)) == Right(()))
      },
      test("rejects negative leakDetectionThreshold") {
        assertTrue(PostgresHikariDataSource.validate(validConfig.copy(leakDetectionThreshold = (-1).seconds)).isLeft)
      },
    ),
  )
