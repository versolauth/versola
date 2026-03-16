package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.flywaydb.core.Flyway
import zio.config.magnolia.deriveConfig
import zio.*

import java.nio.file.{Files, Paths}

object PostgresHikariDataSource:
  def transactor(
      serviceName: Option[String],
      migrate: Boolean,
  ): ZLayer[Scope & ConfigProvider, Throwable, TransactorZIO] =
    ZLayer(ZIO.serviceWithZIO[ConfigProvider](_.load(Config.Nested("postgres", deriveConfig[PostgresConfig])))) >>>
      layer(serviceName, migrate) >>>
      TransactorZIO.layer


  /** Create a HikariDataSource layer with optional Flyway migration.
    *
    * Auto-detects migration directories by scanning for `<service>/implementations/postgres/migrations` pattern.
    *
    * @param migrate
    *   Whether to run Flyway migrations on startup
    * @return
    *   A ZLayer that provides HikariDataSource
    */
  def layer(
      serviceName: Option[String],
      migrate: Boolean,
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
            ZIO.succeed:
              val locations = serviceName match
                case Some(serviceName) =>
                  List(s"filesystem:./$serviceName/implementations/postgres/migrations")
                case None =>
                  detectMigrationDirectories()

              Flyway.configure()
                .locations(locations*)
                .dataSource(dataSource)
                .outOfOrder(true)
                .load()
                .migrate()
        yield dataSource,
      )(dataSource =>
        ZIO.logInfo("Closing HikariDataSource...") *>
        ZIO.attemptBlocking(dataSource.close()).orDie *>
        ZIO.logInfo("HikariDataSource closed successfully")
      )

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
