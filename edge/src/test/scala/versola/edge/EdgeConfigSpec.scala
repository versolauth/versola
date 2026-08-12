package versola.edge

import versola.edge.model.EdgeId
import versola.util.{PrivateKeyUtil, Secret}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.URL
import zio.test.*

import java.security.KeyPairGenerator
import java.util.Base64

/** Pure config-parsing tests for EdgeConfig, mirroring PostgresConfigSpec's
  * pattern: a kebab-case [[zio.ConfigProvider]] over a raw HOCON string,
  * loaded via `deriveConfig[EdgeConfig]` — the same mechanism
  * `VersolaApp.parseConfig` uses in production.
  *
  * The `DeriveConfig` givens below duplicate what `PostgresEdgeApp` defines
  * for the running app. They can't be reused directly: `PostgresEdgeApp`
  * lives in a downstream subproject (`edge/implementations/postgres`) that
  * depends on this one, not the other way around.
  */
object EdgeConfigSpec extends ZIOSpecDefault:

  private given DeriveConfig[EdgeId] = DeriveConfig[String].map(EdgeId(_))

  private given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail: str =>
      Secret.Bytes32.fromBase64Url(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))
        .filterOrElse(
          _.length == 32,
          zio.Config.Error.InvalidData(message = s"Base64-encoded string must be 32 bytes. '$str' is not."),
        )

  private given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private given DeriveConfig[java.security.PrivateKey] = DeriveConfig[String]
    .mapOrFail: str =>
      PrivateKeyUtil.parse(str, "RSA")
        .left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage))

  private val edgeConfigDescriptor = deriveConfig[EdgeConfig]

  // A throwaway RSA-2048 key, generated fresh per test run — its value
  // doesn't matter, EdgeConfig just needs something PrivateKeyUtil.parse
  // accepts.
  private val privateKeyB64: String =
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    Base64.getEncoder.encodeToString(kpg.generateKeyPair().getPrivate.getEncoded)

  // Also throwaway — Secret.Bytes32 just needs 32 raw bytes, base64url-encoded.
  private val secret32 = Base64.getUrlEncoder.withoutPadding.encodeToString(Array.fill(32)(7.toByte))

  private def hocon(includeInternalUrl: Boolean): String =
    val internalLine = if includeInternalUrl then """versola-internal-url = "http://auth:8080"""" else ""
    s"""id = "edge-default"
       |key-id = "test-key"
       |private-key = \"\"\"$privateKeyB64\"\"\"
       |security {
       |  token-encryption {
       |    key = "$secret32"
       |  }
       |  edge-sessions {
       |    secret = "$secret32"
       |    ttl = 30 days
       |  }
       |}
       |central {
       |  url = "http://central:8090"
       |}
       |versola-url = "http://localhost:8080"
       |configuration-cache-refresh-interval = 5 minutes
       |$internalLine
       |""".stripMargin

  def spec = suite("EdgeConfig")(
    suite("parsing")(
      // Regression for the docker-local login bug: SSOClient's tokenUrl/
      // userInfoUrl need to point at a different address than the
      // browser-facing authorizeUrl once edge, auth, and the browser
      // aren't all on the same network. See gen-env.scala's docker-local
      // defaults and SSOClient.scala for the two call sites this field
      // split covers.
      test("parses versola-internal-url distinct from versola-url when both are set") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = true))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(
          config.versolaUrl == URL.decode("http://localhost:8080").toOption.get,
          config.versolaInternalUrl == Some(URL.decode("http://auth:8080").toOption.get),
          config.internalUrl == URL.decode("http://auth:8080").toOption.get,
          config.internalUrl != config.versolaUrl,
        )
      },
      // The actual bug this PR fixes: an env.conf generated before this
      // field existed (every environment except a freshly regenerated
      // docker-local one) has no versola-internal-url key at all. This
      // must still parse — and versolaInternalUrl must fall back to
      // versolaUrl — or every existing deployment fails to start on
      // upgrade.
      test("internalUrl falls back to versolaUrl when versola-internal-url is absent") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(
          config.versolaInternalUrl == None,
          config.internalUrl == config.versolaUrl,
          config.versolaUrl == URL.decode("http://localhost:8080").toOption.get,
        )
      },
    ),
  )
