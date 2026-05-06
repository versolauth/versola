package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import zio.*
import zio.config.magnolia.deriveConfig

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
          _ <- ZIO.logInfo("Acquiring HikariDataSource...")
          dataSource = HikariDataSource {
            val config = HikariConfig()
            config.setDriverClassName("org.postgresql.Driver")
            config.setJdbcUrl(postgres.url)
            config.setUsername(postgres.user)
            config.setPassword(postgres.password)
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
