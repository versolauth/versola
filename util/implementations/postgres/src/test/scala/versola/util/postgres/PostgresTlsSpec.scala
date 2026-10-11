package versola.util.postgres

import versola.util.Secret
import zio.*
import zio.test.*

object PostgresTlsSpec extends ZIOSpecDefault:

  private def weakness(url: String) = PostgresTls.weakness(url)

  def spec = suite("PostgresTls")(
    suite("properties")(
      test("defaults sslmode to verify-full when the URL says nothing") {
        val props = PostgresTls.properties("jdbc:postgresql://db:5432/auth", None)
        assertTrue(props.getProperty("sslmode") == "verify-full", props.getProperty("sslrootcert") == null)
      },
      test("leaves sslmode to the URL when it sets one, or the legacy ssl flag") {
        assertTrue(
          PostgresTls.properties("jdbc:postgresql://db/auth?sslmode=require", None).getProperty("sslmode") == null,
          PostgresTls.properties("jdbc:postgresql://db/auth?ssl=true", None).getProperty("sslmode") == null,
        )
      },
      test("trusts the JVM trust store unless a CA bundle or factory is given") {
        assertTrue(
          PostgresTls.properties("jdbc:postgresql://db/auth", None).getProperty("sslfactory") == "org.postgresql.ssl.DefaultJavaSSLFactory",
          PostgresTls.properties("jdbc:postgresql://db/auth", Some("/ca/root.crt")).getProperty("sslfactory") == null,
          PostgresTls.properties("jdbc:postgresql://db/auth?sslrootcert=/x.crt", None).getProperty("sslfactory") == null,
          PostgresTls.properties("jdbc:postgresql://db/auth?sslfactory=a.B", None).getProperty("sslfactory") == null,
        )
      },
      test("passes the CA bundle unless the URL names its own") {
        assertTrue(
          PostgresTls.properties("jdbc:postgresql://db/auth", Some("/ca/root.crt")).getProperty("sslrootcert") == "/ca/root.crt",
          PostgresTls.properties("jdbc:postgresql://db/auth?sslrootcert=/x.crt", Some("/ca/root.crt")).getProperty("sslrootcert") == null,
        )
      },
    ),
    suite("weakness")(
      test("accepts verifying modes, and the default") {
        assertTrue(
          weakness("jdbc:postgresql://db:5432/auth").isEmpty,
          weakness("jdbc:postgresql://db/auth?currentSchema=edge&sslmode=verify-full").isEmpty,
        )
      },
      test("reads the URL the way pgjdbc does") {
        assertTrue(
          // names are case-sensitive: the driver ignores SSLMODE, so verify-full still applies
          PostgresTls.sslMode("jdbc:postgresql://db/auth?SSLMODE=disable") == "verify-full",
          // the last occurrence wins
          weakness("jdbc:postgresql://db/auth?sslmode=verify-full&sslmode=disable").isDefined,
          weakness("jdbc:postgresql://db/auth?sslmode=disable&sslmode=verify-full").isEmpty,
          // values are URL-decoded
          weakness("jdbc:postgresql://db/auth?ssl=%66alse").isDefined,
          weakness("jdbc:postgresql://db/auth?sslmode=verify%2Dfull").isEmpty,
        )
      },
      test("flags certificate-validation overrides") {
        assertTrue(
          weakness("jdbc:postgresql://db/auth?sslfactory=org.postgresql.ssl.NonValidatingFactory").isDefined,
          weakness("jdbc:postgresql://db/auth?sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory").isEmpty,
        )
      },
      test("flags modes that do not verify the certificate") {
        assertTrue(
          List("disable", "allow", "prefer", "require").forall(mode => weakness(s"jdbc:postgresql://db/auth?sslmode=$mode").isDefined),
          weakness("jdbc:postgresql://db/auth?ssl=false").isDefined,
        )
      },
      test("does not report loopback hosts") {
        assertTrue(
          weakness("jdbc:postgresql://127.0.0.1:5432/auth?sslmode=disable").isEmpty,
          weakness("jdbc:postgresql://localhost/auth?sslmode=disable").isEmpty,
          weakness("jdbc:postgresql://db,localhost/auth?sslmode=disable").isDefined,
          // a `host` parameter replaces the authority
          weakness("jdbc:postgresql://localhost:5432/auth?host=db.internal&sslmode=disable").isDefined,
        )
      },
    ),
    suite("startup")(
      test("prod refuses a URL that does not verify the certificate, without touching the database") {
        val config = PostgresConfig(
          url = "jdbc:postgresql://db.internal:5432/auth?sslmode=require",
          notificationsUrl = None,
          sslRootCert = None,
          user = "u",
          password = Secret.fromString("p"),
          maximumPoolSize = 1,
          minimumIdle = 1,
          connectionTimeout = 1.second,
          maxLifetime = Duration.Zero,
          leakDetectionThreshold = Duration.Zero,
          poolMetricsInterval = None,
        )
        PostgresHikariDataSource
          .layer(serviceName = None, migrate = false, requireVerifiedTls = true)
          .build
          .provideSome[Scope](ZLayer.succeed(config))
          .exit
          .map(exit => assertTrue(exit.causeOption.exists(_.failureOption.exists(_.getMessage.contains("Refusing to start in prod")))))
      },
    ),
  )
