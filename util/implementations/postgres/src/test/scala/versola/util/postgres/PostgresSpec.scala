package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.Secret
import zio.*
import zio.test.ZIOSpec

abstract class PostgresSpec extends ZIOSpec[TransactorZIO]:

  override val bootstrap = PostgresSpec.transactor

object PostgresSpec:

  private def configOf(host: String) =
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

  def config =
    ZLayer.fromZIO:
      System.env("POSTGRES_HOST").someOrElse("localhost:5432").map(configOf)

  /** The same config read from the process's environment, for a test that builds a pool of its own: inside a test `System` is the
    * test environment's, which has no variables, so [[config]] there would name the default host and not the one the run was given. */
  val processConfig: ULayer[PostgresConfig] =
    ZLayer.succeed(configOf(sys.env.getOrElse("POSTGRES_HOST", "localhost:5432")))

  val transactor =
    config >>> (Scope.default >>> PostgresHikariDataSource.layer(
      serviceName = None,
      migrate = true,
      validateOnMigrate = false,
    )) >>> TransactorZIO.layer
