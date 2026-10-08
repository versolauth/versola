package versola.util.postgres

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
          weakness("jdbc:postgresql://db/auth?SSLMODE=verify-ca").isEmpty,
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
        )
      },
    ),
  )
