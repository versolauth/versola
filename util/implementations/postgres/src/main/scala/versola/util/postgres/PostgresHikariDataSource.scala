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
      migrationLocations: Option[Seq[String]] = None,
  ): ZLayer[Scope & ConfigProvider, Throwable, TransactorZIO & HikariDataSource] =
    ZLayer(ZIO.serviceWithZIO[ConfigProvider](_.load(Config.Nested("postgres", deriveConfig[PostgresConfig])))) >+>
      layer(serviceName, migrate, validateOnMigrate, migrationLocations) >+>
      TransactorZIO.layer

  /** Create a HikariDataSource layer with optional Flyway migration.
    *
    * @param migrate
    *   Whether to apply Flyway migrations on startup. When false, the schema is *validated* against
    *   this build's migrations instead of being left unchecked -- see the comment on that branch
    *   below for why "false" doesn't mean "skip Flyway entirely".
    * @param validateOnMigrate
    *   Whether to validate migrations on migrate. Should be true in production, false in tests/development
    * @param migrationLocations
    *   Where to find this schema's migrations. Defaults to auto-detecting a single
    *   `<service>/implementations/postgres/migrations` directory under the working directory (see
    *   `detectMigrationDirectories`) -- correct for auth/central/edge's own images, which each bundle
    *   only their own migrations. Callers that bundle more than one service's migrations in the same
    *   classpath/filesystem (versola-tools' migrate-tool, which ships all three so it can migrate
    *   every schema from one image) MUST pass this explicitly -- auto-detection would otherwise find
    *   all of them and hand Flyway a mix of unrelated schemas' migrations, exactly the bug diagnosed
    *   in the CI e2e OOM investigation (`sbt test` from the repo root picking up all three services'
    *   migrations directories together against one shared schema).
    * @return
    *   A ZLayer that provides HikariDataSource
    */
  def layer(
      serviceName: Option[String],
      migrate: Boolean,
      validateOnMigrate: Boolean = true,
      migrationLocations: Option[Seq[String]] = None,
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
          _ <- ZIO.attemptBlocking:
            val locations = migrationLocations.getOrElse(detectMigrationDirectories())

            val flyway = Flyway.configure()
              .locations(locations*)
              .dataSource(dataSource)
              .ignoreMigrationPatterns("*:missing")
              .outOfOrder(true)
              .cleanDisabled(true)
              .validateMigrationNaming(false)
              .validateOnMigrate(validateOnMigrate)
              .load()

            // migrate = false does NOT mean "don't touch Flyway" -- it means "someone else is
            // responsible for applying migrations" (`versola migrate`, which runs migrate-tool
            // against each schema independently -- see versola-tools' entrypoint.sh and the
            // RUN_MIGRATIONS: "false" that versola-cli's compose fragments set on every service).
            // Leaving it entirely unchecked in that case meant a skipped migrate step was
            // invisible: the service started fine, passed its readiness check, and only failed
            // later, at the first query against a table that was never created -- in production,
            // on real traffic, long after the deploy looked successful.
            //
            // validate() closes that: a schema this build has migrations for but the database
            // hasn't had applied fails here, at startup, with Flyway naming the exact migration.
            // It deliberately does NOT fail the reverse case (database ahead of this build, e.g.
            // deploying an older version back out) -- `ignoreMigrationPatterns("*:missing")`
            // above already covers applied-but-not-resolved-locally, so a rollback deploy still
            // starts.
            if migrate then flyway.migrate() else flyway.validate()

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
