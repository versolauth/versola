package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import zio.*
import zio.config.magnolia.deriveConfig

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.ReentrantLock

object PostgresHikariDataSource:
  def transactor(
      serviceName: Option[String],
      migrate: Boolean,
      validateOnMigrate: Boolean = true,
  ): ZLayer[Scope & ConfigProvider, Throwable, TransactorZIO & HikariDataSource] =
    ZLayer(ZIO.serviceWithZIO[ConfigProvider](_.load(Config.Nested("postgres", deriveConfig[PostgresConfig])))) >+>
      layer(serviceName, migrate, validateOnMigrate) >+>
      TransactorZIO.layer

  /** Create a HikariDataSource layer with optional Flyway migration.
    *
    * Auto-detects migration directories by scanning for `<service>/implementations/postgres/migrations` pattern.
    *
    * @param migrate
    *   Whether to run Flyway migrations on startup
    * @param validateOnMigrate
    *   Whether to validate migrations on migrate. Should be true in production, false in tests/development
    * @return
    *   A ZLayer that provides HikariDataSource
    */
  def layer(
      serviceName: Option[String],
      migrate: Boolean,
      validateOnMigrate: Boolean = true,
  ): ZLayer[Scope & PostgresConfig, Throwable, HikariDataSource] =
    ZLayer:
      ZIO.acquireRelease(
        for
          postgres <- ZIO.service[PostgresConfig]
          _ <- ZIO.fromEither(validate(postgres)).mapError(msg => new IllegalArgumentException(msg))
          _ <- ZIO.logInfo("Acquiring HikariDataSource...")
          dataSource = HikariDataSource {
            val config = HikariConfig()
            config.setDriverClassName("org.postgresql.Driver")
            config.setJdbcUrl(postgres.url)
            config.setUsername(postgres.user)
            // Secret is an opaque Array[Byte] newtype (kept out of toString/logging); HikariConfig
            // needs a plain String, so it's decoded back here at the point of use only.
            config.setPassword(new String(postgres.password, StandardCharsets.UTF_8))
            config.setMaximumPoolSize(postgres.maximumPoolSize)
            config.setMinimumIdle(postgres.minimumIdle)
            config.setConnectionTimeout(postgres.connectionTimeout.toMillis)
            config.setMaxLifetime(postgres.maxLifetime.toMillis)
            config.setLeakDetectionThreshold(postgres.leakDetectionThreshold.toMillis)
            // poolName aids diagnostics; taken from the caller-provided service name.
            serviceName.foreach(config.setPoolName)
            config
          }
          _ <- ZIO.when(migrate):
            ZIO.attemptBlocking:
              val locations = detectMigrationDirectories()

              val flyway = Flyway.configure()
                .locations(locations*)
                .dataSource(dataSource)
                .ignoreMigrationPatterns("*:missing")
                .outOfOrder(true)
                .cleanDisabled(true)
                .validateMigrationNaming(false)
                .validateOnMigrate(validateOnMigrate)
                .load()

              flyway.migrate()

        yield dataSource
      )(dataSource => ZIO.attemptBlocking(dataSource.close()).orDie)


  /** Validates pool-tuning values before they reach HikariCP.
    *
    * HikariCP silently corrects (or, for `maximumPoolSize`, throws with a terse message on) several
    * out-of-range values instead of failing clearly, which makes a config typo hard to diagnose. We
    * validate upfront so a bad `postgres { }` block fails fast with an actionable error.
    *
    * Thresholds mirror HikariCP's own documented minimums.
    */
  private[postgres] def validate(postgres: PostgresConfig): Either[String, Unit] =
    val errors = List(
      Option.when(postgres.maximumPoolSize <= 0):
        s"maximum-pool-size must be > 0, got ${postgres.maximumPoolSize}",
      Option.when(postgres.minimumIdle < 0):
        s"minimum-idle must be >= 0, got ${postgres.minimumIdle}",
      Option.when(postgres.minimumIdle > postgres.maximumPoolSize):
        s"minimum-idle (${postgres.minimumIdle}) must be <= maximum-pool-size (${postgres.maximumPoolSize})",
      Option.when(postgres.connectionTimeout.toMillis < 250):
        s"connection-timeout must be >= 250ms, got ${postgres.connectionTimeout}",
      Option.when(postgres.maxLifetime.toMillis != 0 && postgres.maxLifetime.toMillis < 30000):
        s"max-lifetime must be 0 (disabled) or >= 30 seconds, got ${postgres.maxLifetime}",
      Option.when(postgres.leakDetectionThreshold.toMillis < 0):
        s"leak-detection-threshold must be >= 0, got ${postgres.leakDetectionThreshold}",
      Option.when(postgres.leakDetectionThreshold.toMillis > 0 && postgres.leakDetectionThreshold.toMillis < 2000):
        s"leak-detection-threshold must be 0 (disabled) or >= 2 seconds, got ${postgres.leakDetectionThreshold} " +
          "(HikariCP silently disables values in between instead of failing)",
      Option.when(
        postgres.leakDetectionThreshold.toMillis > 0 &&
          postgres.maxLifetime.toMillis > 0 &&
          postgres.leakDetectionThreshold.toMillis > postgres.maxLifetime.toMillis
      ):
        s"leak-detection-threshold (${postgres.leakDetectionThreshold}) must not exceed max-lifetime " +
          s"(${postgres.maxLifetime}) when max-lifetime > 0 (HikariCP silently disables it otherwise)",
    ).flatten

    if errors.isEmpty then Right(())
    else Left(s"Invalid postgres pool config: ${errors.mkString("; ")}")

  private def detectMigrationDirectories(): List[String] =
    import java.nio.file.{Files, Path, Paths}
    import scala.jdk.CollectionConverters.*

    val root = new java.io.File(".").getCanonicalFile.toPath
    Files.walk(root, 4)
      .iterator()
      .asScala
      .filter(_.toString.endsWith("postgres/migrations"))
      .map { path =>
        s"filesystem:./${root.relativize(path).toString.replace('\\', '/')}"
      }
      .toList
