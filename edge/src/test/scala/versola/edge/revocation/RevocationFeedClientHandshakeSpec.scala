package versola.edge.revocation

import versola.edge.model.EdgeId
import versola.edge.{ClientCertificateFiles, EdgeConfig, EdgeIdentity}
import versola.util.revocation.{Revocation, RevocationKey}
import versola.util.{PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant

/** The feed call over a real handshake, the half [[RevocationFeedPullerSpec]] stubs away: that the edge presents its
  * own certificate to the listener named by `native.auth-mutual-tls-url`, that the listener is held to
  * `native.trusted-certificates` (a pin) and refused when it is not that one, and what each answer of auth becomes.
  *
  * The server stands in for auth's mutual-TLS listener: it demands a client certificate and validates it against its
  * own trust file, so a handler that is reached has been through a handshake that could have refused the call.
  */
object RevocationFeedClientHandshakeSpec extends ZIOSpecDefault:

  private val server = TestCertificates.generate(subject = "CN=localhost,O=Versola,C=KZ", dnsName = Some("localhost"))

  /** The edge's own certificate: self-signed, so its own trust anchor on the server side. */
  private val edge = TestCertificates.generate(subject = "CN=edge-1,O=Versola,C=KZ", uri = Some("urn:versola:edge:edge-1"))

  /** A second self-signed certificate that nobody trusts. */
  private val untrusted = TestCertificates.generate(subject = "CN=not-registered,O=Versola,C=KZ")

  private val pageJson =
    """{"revocations":[
      |{"key":"jti:token-1","exp":2000000000},
      |{"key":"sub:user-1","exp":2000000100,"before":1999999000},
      |{"key":"tenant:default","exp":2000000000}],
      |"cursor":"opaque-cursor"}""".stripMargin

  private def material(generated: TestCertificates.Generated): PrivateClientCertificate.Material =
    PrivateClientCertificate(generated.bundle).material.toOption.get

  private def identityOf(held: Option[PrivateClientCertificate.Material]): EdgeIdentity =
    new EdgeIdentity:
      override def certificate = ZIO.succeed(held)

  private case class Pem(serverCertificate: Path, serverKey: Path, edgeTrust: Path, serverTrust: Path, foreignTrust: Path)

  /** What the listener saw on its last request, and what it answers next. */
  private case class Listener(port: Int, seen: Ref[Option[(X509Certificate, Request)]], answer: Ref[Response])

  private val pemFiles: ZLayer[Scope, Throwable, Pem] =
    ZLayer.fromZIO(
      for
        directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("versola-feed-handshake").nn))(remove)
        serverCertificate <- write(directory, "server.crt", server.certificatePem)
        serverKey <- write(directory, "server.key", server.privateKeyPem)
        edgeTrust <- write(directory, "edge-trust.pem", edge.certificatePem)
        serverTrust <- write(directory, "server-trust.pem", server.certificatePem)
        foreignTrust <- write(directory, "foreign-trust.pem", untrusted.certificatePem)
      yield Pem(serverCertificate, serverKey, edgeTrust, serverTrust, foreignTrust),
    )

  private val listener: ZLayer[Server, Throwable, Listener] =
    ZLayer.fromZIO(
      for
        seen <- Ref.make(Option.empty[(X509Certificate, Request)])
        answer <- Ref.make(Response.json(pageJson))
        port <- Server.install(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(request.remoteCertificate.collect { case x509: X509Certificate => x509 -> request }) *> answer.get
          }.toRoutes,
        )
      yield Listener(port, seen, answer),
    )

  private val serverLayer: ZLayer[Pem, Throwable, Server] =
    ZLayer.fromFunction((pem: Pem) =>
      Server.Config.default.onAnyOpenPort.ssl(
        SSLConfig.fromFile(
          behaviour = SSLConfig.HttpBehaviour.Fail,
          certPath = pem.serverCertificate.toString,
          keyPath = pem.serverKey.toString,
          clientAuth = Some(ClientAuth.Required),
          trustCertCollectionPath = Some(pem.edgeTrust.toString),
          includeClientCert = true,
        ),
      ),
    ) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized

  private def write(directory: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8)).nn)

  private def remove(directory: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      import scala.jdk.CollectionConverters.*
      Files.walk(directory).nn.iterator.nn.asScala.toList.reverse.foreach(Files.deleteIfExists)
    }.ignoreLogged

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  /** Edge as it is configured for the feed: `native.auth-mutual-tls-url` is the listener, pinned by `trust`. */
  private def edgeConfig(port: Int, trust: Set[Path]): EdgeConfig =
    EdgeConfig(
      id = EdgeId("edge-1"),
      keyId = "kid-1",
      privateKey = keyPair.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
      edgeUrl = URL.decode("https://edge.example").toOption.get,
      configurationCacheRefreshInterval = 5.minutes,
      revocation = EdgeConfig.Revocation(feed = EdgeConfig.RevocationFeed(enabled = true, timeout = 2.seconds, lease = 5.seconds)),
      native = Some(
        EdgeConfig.Native(
          authMutualTlsUrl = URL.decode(s"https://localhost:$port").toOption.get,
          trustedCertificates = trust.map(_.toString),
          blobKey = Secret.Bytes32(Array.fill(32)(9.toByte)),
        ),
      ),
    )

  private def fetch(
      config: EdgeConfig,
      held: Option[PrivateClientCertificate.Material] = Some(material(edge)),
      after: String = "",
  ): ZIO[Client & ClientCertificateFiles, Throwable, RevocationFeedClient.Page] =
    for
      httpClient <- ZIO.service[Client]
      files <- ZIO.service[ClientCertificateFiles]
      page <- RevocationFeedClient.Impl(httpClient, config, files, identityOf(held)).fetch(after, 5000)
    yield page

  /** A rejected handshake reaches the caller as a closed channel whichever side refuses it, so what these tests
    * assert on top of the failure is that the same listener still answers a correctly configured call: that is what
    * tells "refused by TLS" from "nothing was listening". */
  private def stillServes(pem: Pem, listening: Listener): ZIO[Client & ClientCertificateFiles, Throwable, Boolean] =
    fetch(edgeConfig(listening.port, Set(pem.serverTrust))).either.map(_.isRight)

  def spec = suite("RevocationFeedClient over a real handshake")(
    test("calls /revocations on the listener with the edge's own certificate, and reads the page") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        page <- fetch(edgeConfig(listening.port, Set(pem.serverTrust)), after = "cursor-in")
        seen <- listening.seen.get
      yield assertTrue(
        seen.map(_._1).contains(edge.certificate),
        seen.map(_._2.url.path.encode).contains("/revocations"),
        seen.map(_._2.url.queryParams.getAll("after")).contains(Chunk("cursor-in")),
        seen.map(_._2.url.queryParams.getAll("limit")).contains(Chunk("5000")),
        page.cursor == "opaque-cursor",
        // a key of a kind this version does not know is skipped, the rest is read
        page.revocations == List(
          Revocation(key = RevocationKey.Jti("token-1"), expiresAt = Instant.ofEpochSecond(2_000_000_000L), issuedBefore = None),
          Revocation(
            key = RevocationKey.Sub("user-1"),
            expiresAt = Instant.ofEpochSecond(2_000_000_100L),
            issuedBefore = Some(Instant.ofEpochSecond(1_999_999_000L)),
          ),
        ),
      )
    },
    test("with several pins, a listener that presents any one of them is accepted") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        page <- fetch(edgeConfig(listening.port, Set(pem.foreignTrust, pem.serverTrust)))
      yield assertTrue(page.cursor == "opaque-cursor")
    },
    test("a listener that is not the pinned one is refused before the certificate is sent") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        result <- fetch(edgeConfig(listening.port, Set(pem.foreignTrust))).either
        seen <- listening.seen.get
        serving <- stillServes(pem, listening)
      yield assertTrue(result.isLeft, seen.isEmpty, serving)
    },
    test("a certificate the listener does not trust is refused in the handshake") {
      // The guard on the first test: the listener validates what it is sent, so reaching its handler means the
      // certificate was accepted and not merely transmitted.
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        result <- fetch(edgeConfig(listening.port, Set(pem.serverTrust)), held = Some(material(untrusted))).either
        seen <- listening.seen.get
        serving <- stillServes(pem, listening)
      yield assertTrue(result.isLeft, seen.isEmpty, serving)
    },
    test("an edge that holds no certificate yet makes no call") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        result <- fetch(edgeConfig(listening.port, Set(pem.serverTrust)), held = None).either
        seen <- listening.seen.get
      yield assertTrue(result.isLeft, seen.isEmpty)
    },
    test("an auth with no feed (404) or that does not take the certificate (403) is reported as refusing, not as down") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        config = edgeConfig(listening.port, Set(pem.serverTrust))
        _ <- listening.answer.set(Response.status(Status.NotFound))
        notFound <- fetch(config).either
        _ <- listening.answer.set(Response.json("""{"error":"access_denied"}""").status(Status.Forbidden))
        forbidden <- fetch(config).either
        _ <- listening.answer.set(Response.json(pageJson))
      yield assertTrue(
        notFound == Left(RevocationFeedClient.Refused(404)),
        forbidden == Left(RevocationFeedClient.Refused(403)),
      )
    },
    test("an answer that is not a page is a failure, not an empty page") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.answer.set(Response.json("""{"unexpected":true}"""))
        result <- fetch(edgeConfig(listening.port, Set(pem.serverTrust))).either
        _ <- listening.answer.set(Response.json(pageJson))
      yield assertTrue(result.isLeft)
    },
  ).provideSome[Scope](
    pemFiles,
    serverLayer,
    listener,
    ClientCertificateFiles.live,
    Client.default,
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
