package versola.util.postgres

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import zio.{Scope, ZIO, ZLayer}

object PostgresHikariDataSource:
  def layer(
      migrate: Boolean,
  ): ZLayer[Scope & PostgresConfig, Throwable, HikariDataSource] =
    ZLayer.scoped:
      ZIO.acquireRelease(
        for
          postgres <- ZIO.service[PostgresConfig]
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
              Flyway.configure()
                .locations("filesystem:./implementations/postgres/migrations")
                .dataSource(dataSource)
                .load()
                .migrate()
        yield dataSource,
      )(dataSource => ZIO.succeed(dataSource.close()))
