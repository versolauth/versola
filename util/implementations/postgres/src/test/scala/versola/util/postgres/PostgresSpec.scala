package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.Secret
import zio.*
import zio.test.ZIOSpec

abstract class PostgresSpec extends ZIOSpec[TransactorZIO]:

  override val bootstrap = PostgresSpec.transactor

object PostgresSpec:


  def config =
    ZLayer.fromZIO:
      System.env("POSTGRES_HOST")
        .someOrElse("localhost:5432").map: host =>
          PostgresConfig(
            url = s"jdbc:postgresql://$host/auth",
            user = "dev",
            password = Secret.fromString("1234"),
            maximumPoolSize = 4,
            minimumIdle = 1,
            connectionTimeout = 30.seconds,
            maxLifetime = 30.minutes,
            leakDetectionThreshold = Duration.Zero,
          )

  val transactor =
    config >>> (Scope.default >>> PostgresHikariDataSource.layer(
      serviceName = None,
      migrate = true,
      validateOnMigrate = false,
    )) >>> TransactorZIO.layer

