package versola.edge

import versola.edge.model.EdgeId
import versola.util.{PrivateKeyUtil, Secret, TestCertificates}
import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.URL
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
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

  private given DeriveConfig[Secret] = DeriveConfig[String]
    .mapOrFail: str =>
      Secret.fromBase64Url(str)
        .left.map(message => zio.Config.Error.InvalidData(message = message))

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

  private def hocon(includeInternalUrl: Boolean, dpopBlock: String = ""): String =
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
       |edge-url = "http://edge:8095"
       |configuration-cache-refresh-interval = 5 minutes
       |$internalLine
       |$dpopBlock
       |""".stripMargin

  private def baseConfig(trustPath: Option[Path]): EdgeConfig = EdgeConfig(
    id = EdgeId("edge-default"),
    keyId = "test-key",
    privateKey = PrivateKeyUtil.parse(privateKeyB64, "RSA").toOption.get,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32.fromBase64Url(secret32).toOption.get),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32.fromBase64Url(secret32).toOption.get, 30.days),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("http://central:8090").toOption.get),
    versolaUrl = URL.decode("http://localhost:8080").toOption.get,
    versolaInternalTrustedCertificates = trustPath.map(_.toString),
    edgeUrl = URL.decode("http://edge:8095").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private def writeCertificate(directory: Path, name: String, pem: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(directory.resolve(name), pem.getBytes(StandardCharsets.UTF_8)).nn)

  private def tempDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("edge-config-spec").nn)): dir =>
      ZIO.attemptBlocking(Files.deleteIfExists(dir)).ignoreLogged

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
    // The gap #401's own trust-anchor fix could not close by itself: nothing in zio-http's
    // client verifies the hostname on this connection (see SSOClient's `internalTrust` and
    // EdgeConfig's own comment), so trusting a CA -- rather than the one leaf certificate the
    // endpoint actually presents -- would accept any certificate that CA has ever issued, for
    // any host. `EdgeConfig.validated` is where that is refused instead.
    suite("validated")(
      test("refuses a certificate authority named as the trust anchor") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            ca = TestCertificates.generate(subject = "CN=internal-ca,O=Versola,C=KZ", ca = true)
            path <- writeCertificate(directory, "ca.pem", ca.certificatePem)
            exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(Some(path))) >>> EdgeConfig.validated).exit
          yield assertTrue(exit.isFailure)
      },
      test("accepts the leaf certificate the endpoint actually presents") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            leaf = TestCertificates.generate(subject = "CN=auth.internal,O=Versola,C=KZ")
            path <- writeCertificate(directory, "leaf.pem", leaf.certificatePem)
            config <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(Some(path))) >>> EdgeConfig.validated)
          yield assertTrue(config.versolaInternalTrustedCertificates == Some(path.toString))
      },
      test("passes an absent trust anchor through unexamined") {
        for
          config <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(None)) >>> EdgeConfig.validated)
        yield assertTrue(config.versolaInternalTrustedCertificates == None)
      },
    ),
  )
