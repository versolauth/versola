package versola.util.postgres

import com.zaxxer.hikari.HikariDataSource
import versola.util.Secret
import zio.*
import zio.test.*

/** The certificate checks themselves, against a real Postgres that serves TLS.
  *
  * [[PostgresTlsSpec]] can only show which properties are handed to the driver; whether pgjdbc then
  * actually validates the chain and the host name, through the pool and through the separate
  * `LISTEN` connection, is a property of the driver and the server together. Needs the fixture
  * from `util/implementations/postgres/tls-fixture/start.sh` (CI starts it, develop.md says how to
  * locally); its CA is not in any trust store, which is what makes the "no CA" case fail.
  */
object PostgresTlsConnectionSpec extends ZIOSpecDefault:

  private val tlsHost = sys.env.getOrElse("POSTGRES_TLS_HOST", "localhost:5433")
  private val ca = sys.env.getOrElse("POSTGRES_TLS_CA", "target/postgres-tls/ca.crt")

  // No migrations to apply: only the connection is under test.
  private val noMigrations = Some(Seq("filesystem:./target/postgres-tls/no-migrations"))

  private def config(address: String, query: String = "", sslRootCert: Option[String]) =
    PostgresConfig(
      url = s"jdbc:postgresql://$address/auth$query",
      notificationsUrl = None,
      sslRootCert = sslRootCert,
      user = "dev",
      password = Secret.fromString("1234"),
      // Flyway holds one connection while the pool wants another
      maximumPoolSize = 2,
      minimumIdle = 1,
      connectionTimeout = 5.seconds,
      maxLifetime = Duration.Zero,
      leakDetectionThreshold = Duration.Zero,
      poolMetricsInterval = None,
    )

  private def pool(config: PostgresConfig): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.serviceWithZIO[HikariDataSource](ZIO.succeed(_)).provideSome[Scope](
      ZLayer.succeed(config),
      PostgresHikariDataSource.layer(serviceName = None, migrate = false, migrationLocations = noMigrations),
    )

  /** Refused for a TLS reason, not for some unrelated failure to connect. */
  private def refusedByTls(exit: Exit[Throwable, Any], reason: String): TestResult =
    val messages = exit.causeOption.toList.flatMap(cause => cause.failures ++ cause.defects).flatMap: error =>
      Iterator.iterate[Throwable | Null](error)(_.getCause).takeWhile(_ != null).map(_.nn.getMessage).toList
    assertTrue(exit.isFailure, messages.exists(message => message != null && message.contains(reason)))

  /** Whether the server reports the pooled session as TLS-encrypted. */
  private def encrypted(dataSource: HikariDataSource): Task[Boolean] =
    ZIO.attemptBlocking:
      val connection = dataSource.getConnection()
      try
        val rows = connection.createStatement().executeQuery("select ssl from pg_stat_ssl where pid = pg_backend_pid()")
        rows.next() && rows.getBoolean(1)
      finally connection.close()

  def spec = suite("Postgres TLS against a server that serves it")(
    test("the pool connects with the verified default, given the CA") {
      ZIO.scoped:
        for
          dataSource <- pool(config(tlsHost, sslRootCert = Some(ca)))
          tls <- encrypted(dataSource)
        yield assertTrue(tls)
    },
    test("the pool refuses a certificate no trust store vouches for") {
      ZIO.scoped:
        for
          result <- pool(config(tlsHost, sslRootCert = None)).exit
        yield refusedByTls(result, "PKIX path building failed")
    },
    test("the pool refuses a host name the certificate is not valid for") {
      ZIO.scoped:
        for
          // Same server, reached by an address the certificate does not name.
          ip = tlsHost.replace("localhost", "127.0.0.1")
          result <- pool(config(ip, sslRootCert = Some(ca))).exit
        yield assertTrue(ip != tlsHost) && refusedByTls(result, "could not be verified by hostnameverifier")
    },
    test("verify-ca checks the chain but not the host name") {
      ZIO.scoped:
        for
          dataSource <- pool(config(tlsHost.replace("localhost", "127.0.0.1"), "?sslmode=verify-ca", Some(ca)))
          tls <- encrypted(dataSource)
        yield assertTrue(tls)
    },
    test("the LISTEN connection is verified too: it connects with the CA and is refused without it") {
      for
        connects <- PostgresNotificationListener.make(List("tls_check"))
          .provideSome[Scope](ZLayer.succeed(config(tlsHost, sslRootCert = Some(ca)))).exit
        refused <- PostgresNotificationListener.make(List("tls_check"))
          .provideSome[Scope](ZLayer.succeed(config(tlsHost, sslRootCert = None))).exit
      yield assertTrue(connects.isSuccess) && refusedByTls(refused, "PKIX path building failed")
    },
    // Not tested here: that a server without TLS is refused by default (no fallback to plaintext).
    // It needs a Postgres that speaks no TLS, and the test Postgres is one TLS server shared with
    // every other spec. Verified by hand against such a server (`docker run -d -p 5440:5432
    // -e POSTGRES_PASSWORD=1234 -e POSTGRES_USER=dev -e POSTGRES_DB=auth postgres:18`): a pool
    // built with `sslRootCert = Some(ca)` against localhost:5440 must fail with "The server does
    // not support SSL". Redo it if PostgresTls or the pool's sslmode handling changes.
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
