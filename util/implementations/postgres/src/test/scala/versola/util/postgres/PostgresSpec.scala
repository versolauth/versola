package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.Secret
import zio.*
import zio.test.ZIOSpec

import java.lang.System as JavaSystem

abstract class PostgresSpec extends ZIOSpec[TransactorZIO]:

  override val bootstrap = PostgresSpec.transactor

object PostgresSpec:

  def config =
    ZLayer.fromZIO:
      // The process's own environment, not ZIO's `System`: inside a test that is `TestSystem`, which has no variables, so a
      // spec that builds this config in a test would reach the default host while the bootstrap's transactor reached `POSTGRES_HOST`.
      ZIO.succeed(Option(JavaSystem.getenv("POSTGRES_HOST")).getOrElse("localhost:5432")).map: host =>
        PostgresConfig(
          url = s"jdbc:postgresql://$host/auth?sslmode=disable",
          notificationsUrl = None,
          sslRootCert = None,
          user = "dev",
          password = Secret.fromString("1234"),
          maximumPoolSize = 4,
          minimumIdle = 1,
          connectionTimeout = 30.seconds,
          maxLifetime = 30.minutes,
          leakDetectionThreshold = Duration.Zero,
          poolMetricsInterval = None,
        )

  val transactor =
    config >>> (Scope.default >>> PostgresHikariDataSource.layer(
      serviceName = None,
      migrate = true,
      validateOnMigrate = false,
    )) >>> TransactorZIO.layer
