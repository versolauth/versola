package versola.edge

import versola.edge.model.{ClientCredential, ClientId, Code, CodeVerifier, EdgeId}
import versola.util.{PrivateClientCertificate, RedirectUri, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

/** RFC 8705 §2 over a real handshake, which is the half [[SSOClientSpec]] cannot reach: there
  * the `Client` is a stub, so a certificate "presented" is one recorded by a test double and
  * every question that only a TLS stack answers stays unasked.
  *
  * What is actually unknown, and what each test here settles: that the PEM pair
  * [[ClientCertificateFiles]] writes is one Netty can load at all, that the certificate a
  * client authenticates with arrives at the server as the peer certificate rather than
  * silently not being sent, and that `versolaInternalTrustedCertificates` authenticates the
  * far side rather than merely being read — the property that keeps this credential off a
  * connection to whatever answered the address.
  *
  * The server here stands in for whatever terminates TLS in front of auth. It demands a
  * client certificate (`ClientAuth.Required`) and validates it against its own trust file, so
  * a test that reaches the handler has been through a handshake that could have refused it —
  * the refusal cases below are what keep that from being a claim about a server that accepts
  * anything.
  */
object SSOClientMutualTlsHandshakeSpec extends ZIOSpecDefault:

  /** `localhost` in both the subject and a SAN: the client verifies the hostname it dialled
    * against the certificate, and which of the two it reads is the TLS stack's business. */
  private val server = TestCertificates.generate(
    subject = "CN=localhost,O=Versola,C=KZ",
    dnsName = Some("localhost"),
  )

  /** The client certificate edge is provisioned with, and — being self-signed — its own trust
    * anchor, which is what the server validates the handshake against. */
  private val client = TestCertificates.generate(dnsName = Some("web-app.versola.test"))

  /** A second self-signed certificate, trusted by nobody. Same shape as `client`, so the only
    * thing that can make the server treat it differently is the trust file. */
  private val untrusted = TestCertificates.generate(subject = "CN=not-registered,O=Versola,C=KZ")

  private val tokenJson =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"scope":"openid"}"""

  private val clientId = ClientId("web-app")
  private val redirectUri = RedirectUri("https://app.example/callback")

  private def material(generated: TestCertificates.Generated): PrivateClientCertificate.Material =
    PrivateClientCertificate(generated.bundle).material.toOption.get

  private case class Pem(
      serverCertificate: Path,
      serverKey: Path,
      clientTrust: Path,
      serverTrust: Path,
      foreignTrust: Path,
  )

  /** The TLS endpoint, and what the last handshake through it presented. One for the suite:
    * a server started per test would be torn down with that test, and a later connection to
    * the port it had would be refused rather than refused *by TLS* — which is the difference
    * the two negative tests below turn on. */
  private case class Listener(port: Int, seen: Ref[Option[X509Certificate]])

  /** The PEM files the TLS stack on either side reads. Written once for the suite, under a
    * directory that goes away with it. */
  private val pemFiles: ZLayer[Scope, Throwable, Pem] =
    ZLayer.fromZIO(
      for
        directory <- ZIO.acquireRelease(
          ZIO.attemptBlocking(Files.createTempDirectory("versola-mtls-handshake").nn),
        )(remove)
        serverCertificate <- write(directory, "server.crt", server.certificatePem)
        serverKey <- write(directory, "server.key", server.privateKeyPem)
        // The server validates the client's certificate against this, and the client
        // validates the server's against the other -- each side trusting exactly the one
        // certificate the other presents, which is what an internal hop looks like.
        clientTrust <- write(directory, "client-trust.pem", client.certificatePem)
        serverTrust <- write(directory, "server-trust.pem", server.certificatePem)
        foreignTrust <- write(directory, "foreign-trust.pem", untrusted.certificatePem)
      yield Pem(serverCertificate, serverKey, clientTrust, serverTrust, foreignTrust),
    )

  /** Requires `Server` rather than building one, so the endpoint's lifetime is the suite's
    * and not this effect's: a layer provided here would be finalised the moment
    * `Server.install` returned, leaving every test connecting to a closed port. */
  private val listener: ZLayer[Server, Throwable, Listener] =
    ZLayer.fromZIO(
      for
        seen <- Ref.make(Option.empty[X509Certificate])
        port <- Server.install(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(request.remoteCertificate.collect { case x509: X509Certificate => x509 })
              .as(Response.json(tokenJson))
          }.toRoutes,
        )
      yield Listener(port, seen),
    )

  private val serverLayer: ZLayer[Pem, Throwable, Server] =
    ZLayer.fromFunction((pem: Pem) =>
      Server.Config.default.onAnyOpenPort.ssl(
        SSLConfig.fromFile(
          behaviour = SSLConfig.HttpBehaviour.Fail,
          certPath = pem.serverCertificate.toString,
          keyPath = pem.serverKey.toString,
          // The point of the suite: a server that did not demand a certificate would answer
          // a client that never sent one, and every assertion below would hold vacuously.
          clientAuth = Some(ClientAuth.Required),
          trustCertCollectionPath = Some(pem.clientTrust.toString),
          // What populates `Request.remoteCertificate`; without it the handshake still
          // happens and the certificate is simply not readable from the handler.
          includeClientCert = true,
        ),
      ),
    ) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized

  private def write(directory: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(
      Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8)).nn,
    )

  private def remove(directory: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      import scala.jdk.CollectionConverters.*
      Files.walk(directory).nn.iterator.nn.asScala.toList.reverse.foreach(Files.deleteIfExists)
    }.ignoreLogged

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  /** Edge as it is configured to reach auth: `internalUrl` is the TLS endpoint the server
    * above is listening on, and the trust file is the production setting under test. */
  private def edgeConfig(port: Int, trust: Option[Path]): EdgeConfig = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    versolaInternalUrl = Some(URL.decode(s"https://localhost:$port").toOption.get),
    versolaInternalTrustedCertificates = trust.map(_.toString),
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  /** One token call as a client that authenticates by certificate, over the real stack:
    * the real [[ClientCertificateFiles]] writes the PEM pair, and zio-http does the rest. */
  private def exchange(
      certificate: TestCertificates.Generated,
      trust: Option[Path],
      port: Int,
  ): ZIO[Client & ClientCertificateFiles, Throwable, Unit] =
    for
      httpClient <- ZIO.service[Client]
      files <- ZIO.service[ClientCertificateFiles]
      sso = SSOClient.Impl(httpClient, edgeConfig(port, trust), files)
      _ <- sso.exchangeAuthorizationCode(
        Code("c-1"),
        CodeVerifier("v-1"),
        redirectUri,
        clientId,
        ClientCredential.MutualTls(material(certificate)),
      )
    yield ()

  /** A rejected handshake reaches the caller as a closed channel: whichever side refuses,
    * zio-http reports `PrematureChannelClosureException` and the TLS alert behind it is not
    * in the cause chain. So the failure's type says nothing, and what the negative tests
    * assert instead is that the *same listener* still answers a correctly configured call --
    * which is what separates "refused by TLS" from "nothing was listening", the way these
    * tests would otherwise pass without a server at all. */
  private def stillServes(
      pem: Pem,
      listening: Listener,
  ): ZIO[Client & ClientCertificateFiles, Throwable, Boolean] =
    for
      _ <- listening.seen.set(None)
      result <- exchange(client, Some(pem.serverTrust), listening.port).either
      presented <- listening.seen.get
    yield result.isRight && presented.contains(client.certificate)

  def spec = suite("SSOClient mutual TLS handshake")(
    test("the certificate edge holds is the one the server sees on the connection") {
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        _ <- exchange(client, Some(pem.serverTrust), listening.port)
        presented <- listening.seen.get
      yield assertTrue(
        // Compared as the whole certificate rather than by subject: the thumbprint is what
        // RFC 8705 §3.1 binds a token to, and two certificates can share a subject.
        presented.contains(client.certificate),
      )
    },
    test("a certificate the server does not trust is refused in the handshake") {
      // The guard on the test above: the server validates what it is sent, so reaching the
      // handler means the certificate was accepted, not merely transmitted.
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        result <- exchange(untrusted, Some(pem.serverTrust), listening.port).either
        presented <- listening.seen.get
        serving <- stillServes(pem, listening)
      yield assertTrue(
        result.isLeft,
        presented.isEmpty,
        serving,
      )
    },
    test("a server outside the configured anchors is refused before the certificate is sent") {
      // What `versolaInternalTrustedCertificates` buys, and why it is not optional for this
      // credential: the anchors are what authenticate the far side, so an endpoint that is
      // not one of them is refused -- on *server* trust, before the client certificate is
      // presented to it, which is why the handler sees nothing at all.
      for
        pem <- ZIO.service[Pem]
        listening <- ZIO.service[Listener]
        _ <- listening.seen.set(None)
        // Anchors vouching for a different certificate entirely. The server is the same one
        // the first test reached, so trust is the only thing that changed.
        result <- exchange(client, Some(pem.foreignTrust), listening.port).either
        presented <- listening.seen.get
        serving <- stillServes(pem, listening)
      yield assertTrue(
        result.isLeft,
        presented.isEmpty,
        serving,
      )
    },
  ).provideSome[Scope](
    pemFiles,
    serverLayer,
    listener,
    ClientCertificateFiles.live,
    Client.default,
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
