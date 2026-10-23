package versola.edge

import versola.edge.model.EdgeId
import versola.util.{PrivateKeyUtil, Secret, SecretConfig, TestCertificates}
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
    .mapOrFail(SecretConfig.secret)

  private given DeriveConfig[Secret.Bytes32] = DeriveConfig[String]
    .mapOrFail(SecretConfig.fixedLength(Secret.Bytes32))

  private given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private given DeriveConfig[java.security.PrivateKey] = DeriveConfig[String]
    .mapOrFail(SecretConfig.rsaPrivateKey)

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

  private def hocon(includeInternalUrl: Boolean, dpopBlock: String = "", modeLine: String = ""): String =
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
       |$modeLine
       |""".stripMargin

  /** Central's own leaf certificate, written once: what `central.mutual-tls` pins, which every
    * validated config needs since central is reachable over nothing else. */
  private val centralLeafPath: Path =
    val path = Files.createTempFile("edge-config-spec-central", ".pem").nn
    path.toFile.nn.deleteOnExit()
    Files.write(path, TestCertificates.generate(subject = "CN=central.internal,O=Versola,C=KZ").certificatePem.getBytes(StandardCharsets.UTF_8))
    path

  private def centralMutualTls(trusted: Path*) = EdgeConfig.CentralMutualTls(
    certificate = Some("/edge/central-client.crt"),
    privateKey = Some("/edge/central-client.key"),
    trustedCertificates = trusted.map(_.toString).toSet,
  )

  private def baseConfig(trustPath: Option[Path]): EdgeConfig = EdgeConfig(
    id = EdgeId("edge-default"),
    keyId = "test-key",
    privateKey = PrivateKeyUtil.parse(privateKeyB64, "RSA").toOption.get,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32.fromBase64Url(secret32).toOption.get),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32.fromBase64Url(secret32).toOption.get, 30.days),
    ),
    central = EdgeConfig.CentralConfig(
      url = URL.decode("https://central:8093").toOption.get,
      mutualTls = Some(centralMutualTls(centralLeafPath)),
    ),
    versolaUrl = URL.decode("http://localhost:8080").toOption.get,
    versolaInternalTrustedCertificates = trustPath.map(_.toString).toSet,
    edgeUrl = URL.decode("http://edge:8095").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    dpop = Some(EdgeConfig.Dpop.default(Secret.Bytes32.fromBase64Url(secret32).toOption.get)),
  )

  private def nativeConfig(trustedCertificates: String*) = EdgeConfig.Native(
    authMutualTlsUrl = URL.decode("https://auth:8083").toOption.get,
    trustedCertificates = trustedCertificates.toSet,
    blobKey = Secret.Bytes32.fromBase64Url(secret32).toOption.get,
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
      test("parses a native block, the external URL defaulting to the one edge dials") {
        val block =
          s"""native {
             |  auth-mutual-tls-url = "https://localhost:9008"
             |  trusted-certificates = "/tmp/server.crt"
             |  blob-key = "$secret32"
             |}""".stripMargin
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false, dpopBlock = block))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(
          config.native.map(_.externalUrl) == Some(URL.decode("https://localhost:9008").toOption.get),
          config.native.map(_.blobTtl) == Some(10.minutes),
        )
      },
      // Every configuration written before the listener existed has no such block, and none serves
      // `/resources` on a second port.
      test("a file without a mutual-tls block parses with no listener") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(config.mutualTls.isEmpty)
      },
      test("parses a mutual-tls block shaped like auth's") {
        val block =
          """mutual-tls {
            |  certificate = "/app/mtls/edge-server.crt"
            |  private-key = "/app/mtls/edge-server.key"
            |  trusted-certificates = "/app/mtls/callers-ca.crt"
            |  external-url = "https://api-mtls.example.com"
            |}""".stripMargin
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false, dpopBlock = block))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(
          config.mutualTls == Some(
            EdgeConfig.MutualTls(
              certificate = "/app/mtls/edge-server.crt",
              privateKey = "/app/mtls/edge-server.key",
              trustedCertificates = "/app/mtls/callers-ca.crt",
              externalUrl = URL.decode("https://api-mtls.example.com").toOption.get,
            ),
          ),
        )
      },
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
      // Every configuration written before `mode` existed -- which is every one there is -- has
      // no such key, and each of them is the first-party edge it always was (#555).
      test("a file without a mode key parses as the first-party edge") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(config.mode == EdgeMode.FirstParty)
      },
      test("parses mode = partner") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false, modeLine = """mode = "partner""""))
            .kebabCase
            .load(edgeConfigDescriptor)
        yield assertTrue(config.mode == EdgeMode.Partner)
      },
      test("refuses a mode it does not know, naming the ones it does") {
        for error <- TypesafeConfigProvider
            .fromHoconString(hocon(includeInternalUrl = false, modeLine = """mode = "Partner""""))
            .kebabCase
            .load(edgeConfigDescriptor)
            .flip
        yield assertTrue(error.getMessage.contains("first-party, partner"), error.getMessage.contains("'Partner'"))
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
          yield assertTrue(config.versolaInternalTrustedCertificates == Set(path.toString))
      },
      test("refuses a certificate authority pinned for auth's mutual-TLS listener") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            ca = TestCertificates.generate(subject = "CN=auth-mtls-ca,O=Versola,C=KZ", ca = true)
            path <- writeCertificate(directory, "mtls-ca.pem", ca.certificatePem)
            exit <- ZIO.service[EdgeConfig]
              .provideLayer(
                ZLayer.succeed(baseConfig(None).copy(native = Some(nativeConfig(path.toString)))) >>>
                  EdgeConfig.validated,
              ).exit
          yield assertTrue(exit.isFailure)
      },
      // Every native client is registered `dpopBoundAccessTokens`, `/native/start` verifies
      // and replay-guards the device's proof on the `dpop` windows, and this same edge is
      // what the app then calls its API through -- where a key-bound token with no `dpop`
      // block is refused outright. The combination is a deployment that cannot work.
      test("refuses a native block with no dpop block to verify device proofs against") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            leaf = TestCertificates.generate(subject = "CN=auth-mtls,O=Versola,C=KZ")
            path <- writeCertificate(directory, "mtls-leaf.pem", leaf.certificatePem)
            config = baseConfig(None).copy(native = Some(nativeConfig(path.toString)), dpop = None)
            exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated).exit
          yield assertTrue(exit.isFailure)
      },
      test("accepts a native block pinned to a leaf, alongside a dpop block") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            leaf = TestCertificates.generate(subject = "CN=auth-mtls,O=Versola,C=KZ")
            path <- writeCertificate(directory, "mtls-leaf.pem", leaf.certificatePem)
            config <- ZIO.service[EdgeConfig].provideLayer(
              ZLayer.succeed(baseConfig(None).copy(native = Some(nativeConfig(path.toString)))) >>>
                EdgeConfig.validated,
            )
          yield assertTrue(config.native.map(_.trustedCertificates) == Some(Set(path.toString)))
      },
      test("accepts several leaf pins at once, for the overlap window of a rotation") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            old = TestCertificates.generate(subject = "CN=auth-mtls-old,O=Versola,C=KZ")
            incoming = TestCertificates.generate(subject = "CN=auth-mtls-new,O=Versola,C=KZ")
            oldPath <- writeCertificate(directory, "old.pem", old.certificatePem)
            newPath <- writeCertificate(directory, "new.pem", incoming.certificatePem)
            config <- ZIO.service[EdgeConfig].provideLayer(
              ZLayer.succeed(baseConfig(None).copy(native = Some(nativeConfig(oldPath.toString, newPath.toString)))) >>>
                EdgeConfig.validated,
            )
          yield assertTrue(config.native.map(_.trustedCertificates) == Some(Set(oldPath.toString, newPath.toString)))
      },
      test("refuses the whole set when any one pin is a certificate authority") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            leaf = TestCertificates.generate(subject = "CN=auth-mtls,O=Versola,C=KZ")
            ca = TestCertificates.generate(subject = "CN=auth-mtls-ca,O=Versola,C=KZ", ca = true)
            leafPath <- writeCertificate(directory, "leaf.pem", leaf.certificatePem)
            caPath <- writeCertificate(directory, "ca.pem", ca.certificatePem)
            exit <- ZIO.service[EdgeConfig]
              .provideLayer(
                ZLayer.succeed(baseConfig(Some(leafPath)).copy(versolaInternalTrustedCertificates = Set(leafPath.toString, caPath.toString))) >>>
                  EdgeConfig.validated,
              ).exit
          yield assertTrue(exit.isFailure)
      },
      test("refuses a CA that follows a leaf in the same PEM file") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            leaf = TestCertificates.generate(subject = "CN=auth-mtls,O=Versola,C=KZ")
            ca = TestCertificates.generate(subject = "CN=auth-mtls-ca,O=Versola,C=KZ", ca = true)
            path <- writeCertificate(directory, "bundle.pem", leaf.certificatePem + "\n" + ca.certificatePem)
            exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(Some(path))) >>> EdgeConfig.validated).exit
          yield assertTrue(exit.isFailure)
      },
      test("refuses a native block that pins nothing") {
        for
          exit <- ZIO.service[EdgeConfig]
            .provideLayer(ZLayer.succeed(baseConfig(None).copy(native = Some(nativeConfig()))) >>> EdgeConfig.validated)
            .exit
        yield assertTrue(exit.isFailure)
      },
      // Central serves the edge surface only on its mutual-TLS listener: an edge configured for
      // anything else cannot reach it, and is refused at startup instead of at the first sync.
      test("refuses a central block with no mutual-tls") {
        for
          exit <- ZIO.service[EdgeConfig]
            .provideLayer(ZLayer.succeed(baseConfig(None).copy(central = baseConfig(None).central.copy(mutualTls = None))) >>> EdgeConfig.validated)
            .exit
        yield assertTrue(exit.isFailure)
      },
      test("accepts a certificate that central issues, in place of one deployed with the edge") {
        val identity = EdgeConfig.CentralIdentity(
          directory = "/var/lib/edge/identity",
          enrollment = Some(EdgeConfig.CentralEnrollment(URL.decode("https://central.example").toOption.get, "/run/token")),
        )
        val base = baseConfig(None)
        val config = base.copy(central =
          base.central.copy(
            mutualTls = Some(base.central.mutualTls.get.copy(certificate = None, privateKey = None)),
            identity = Some(identity),
          ),
        )
        for result <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated)
        yield assertTrue(result.central.identity.contains(identity))
      },
      test("refuses a certificate named both as a file and as one central issues") {
        val base = baseConfig(None)
        val config = base.copy(central =
          base.central.copy(identity = Some(EdgeConfig.CentralIdentity(directory = "/var/lib/edge/identity"))),
        )
        for exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("both name this edge's certificate"))
      },
      test("refuses a central block that does not say which certificate this edge presents") {
        val base = baseConfig(None)
        val config = base.copy(central =
          base.central.copy(mutualTls = Some(base.central.mutualTls.get.copy(certificate = None, privateKey = None))),
        )
        for exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("is not named"))
      },
      test("refuses a certificate without its key") {
        val base = baseConfig(None)
        val config = base.copy(central =
          base.central.copy(mutualTls = Some(base.central.mutualTls.get.copy(privateKey = None))),
        )
        for exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("given together"))
      },
      test("refuses an enrollment url that is not https, where the one-time token would travel in clear") {
        val base = baseConfig(None)
        val config = base.copy(central =
          base.central.copy(
            mutualTls = Some(base.central.mutualTls.get.copy(certificate = None, privateKey = None)),
            identity = Some(
              EdgeConfig.CentralIdentity(
                directory = "/var/lib/edge/identity",
                enrollment = Some(EdgeConfig.CentralEnrollment(URL.decode("http://central.example").toOption.get, "/run/token")),
              ),
            ),
          ),
        )
        for exit <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(config) >>> EdgeConfig.validated).exit
        yield assertTrue(exit.isFailure, exit.toString.contains("must be https"))
      },
      test("refuses a central url that is not https") {
        for
          exit <- ZIO.service[EdgeConfig]
            .provideLayer(
              ZLayer.succeed(baseConfig(None).copy(central = baseConfig(None).central.copy(url = URL.decode("http://central:8093").toOption.get))) >>>
                EdgeConfig.validated,
            ).exit
        yield assertTrue(exit.isFailure)
      },
      test("refuses central's pin when it names no certificate") {
        for
          exit <- ZIO.service[EdgeConfig]
            .provideLayer(
              ZLayer.succeed(baseConfig(None).copy(central = baseConfig(None).central.copy(mutualTls = Some(centralMutualTls())))) >>>
                EdgeConfig.validated,
            ).exit
        yield assertTrue(exit.isFailure)
      },
      test("refuses a certificate authority pinned for central's mutual-TLS listener") {
        ZIO.scoped:
          for
            directory <- tempDirectory
            ca = TestCertificates.generate(subject = "CN=central-ca,O=Versola,C=KZ", ca = true)
            path <- writeCertificate(directory, "central-ca.pem", ca.certificatePem)
            config = baseConfig(None)
            exit <- ZIO.service[EdgeConfig]
              .provideLayer(ZLayer.succeed(config.copy(central =
                config.central.copy(mutualTls = Some(centralMutualTls(path))),
              )) >>> EdgeConfig.validated)
              .exit
          yield assertTrue(exit.isFailure)
      },
      test("passes an absent trust anchor through unexamined") {
        for
          config <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(None)) >>> EdgeConfig.validated)
        yield assertTrue(config.versolaInternalTrustedCertificates.isEmpty)
      },
      // #555 names the mode before #564 implements it: an edge told to be a partner must say
      // so at startup, not sync from central and vouch to auth as the first-party edge it was
      // not configured to be.
      test("refuses partner mode as not implemented, at startup") {
        for
          error <- ZIO.service[EdgeConfig]
            .provideLayer(ZLayer.succeed(baseConfig(None).copy(mode = EdgeMode.Partner)) >>> EdgeConfig.validated)
            .flip
        yield assertTrue(
          error == EdgeMode.NotImplemented(EdgeMode.Partner),
          error.getMessage.contains("mode = partner is not implemented yet"),
        )
      },
      test("passes the first-party mode through, as the mode every existing edge runs in") {
        for
          config <- ZIO.service[EdgeConfig].provideLayer(ZLayer.succeed(baseConfig(None)) >>> EdgeConfig.validated)
        yield assertTrue(config.mode == EdgeMode.FirstParty)
      },
    ),
  )
