package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import zio.*
import zio.test.ZIOSpec


abstract class PostgresSpec extends ZIOSpec[TransactorZIO]:
  override val bootstrap = PostgresSpec.transactor

object PostgresSpec:
  val config =
    ZLayer.fromZIO:
      System.env("POSTGRES_HOST")
        .someOrElse("localhost:5432").map: host =>
          PostgresConfig(
            url = s"jdbc:postgresql://$host/auth",
            user = "dev",
            password = "1234",
          )

  val transactor =
    config >>> (Scope.default >>> PostgresHikariDataSource.layer(migrate = true)) >>> TransactorZIO.layer