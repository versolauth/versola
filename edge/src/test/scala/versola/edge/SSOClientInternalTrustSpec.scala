package versola.edge

import versola.edge.model.{ClientCredential, ClientId, Code, CodeVerifier, EdgeId}
import versola.util.http.Observability
import versola.util.{RedirectUri, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator

/** What authenticates auth on a call that carries its credential in the request (a secret here; an assertion takes
  * the same connection): `versola-internal-trusted-certificates` when the operator pinned the internal endpoint,
  * the shared client's own trust when not. The server stands in for a terminator with a private certificate, the
  * common case behind `versola-internal-url`, and demands nothing of the client: the pin is the only thing that
  * decides whether the call reaches it, which the negative cases turn on. [[SSOClientMutualTlsHandshakeSpec]] is the
  * certificate client's half of the same question.
  */
object SSOClientInternalTrustSpec extends ZIOSpecDefault:

  private val server = TestCertificates.generate(
    subject = "CN=localhost,O=Versola,C=KZ",
    dnsName = Some("localhost"),
  )

  /** A certificate of the same shape, trusted by nobody: a pin naming it vouches for a different server entirely. */
  private val foreign = TestCertificates.generate(subject = "CN=not-auth,O=Versola,C=KZ", dnsName = Some("localhost"))

  private val tokenJson =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"scope":"openid"}"""

  private case class Pem(serverCertificate: Path, serverKey: Path, serverTrust: Path, foreignTrust: Path)

  private val pemFiles: ZLayer[Scope, Throwable, Pem] =
    ZLayer.fromZIO(
      for
        directory <- ZIO.acquireRelease(
          ZIO.attemptBlocking(Files.createTempDirectory("versola-internal-trust").nn),
        )(remove)
        serverCertificate <- write(directory, "server.crt", server.certificatePem)
        serverKey <- write(directory, "server.key", server.privateKeyPem)
        serverTrust <- write(directory, "server-trust.pem", server.certificatePem)
        foreignTrust <- write(directory, "foreign-trust.pem", foreign.certificatePem)
      yield Pem(serverCertificate, serverKey, serverTrust, foreignTrust),
    )

  private val serverLayer: ZLayer[Pem, Throwable, Server] =
    ZLayer.fromFunction((pem: Pem) =>
      Server.Config.default.onAnyOpenPort.ssl(
        SSLConfig.fromFile(
          behaviour = SSLConfig.HttpBehaviour.Fail,
          certPath = pem.serverCertificate.toString,
          keyPath = pem.serverKey.toString,
        ),
      ),
    ) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized

  /** The port the suite's server answers on; one for the suite, as in the handshake spec, so a refused call is refused by
    * TLS and not by a port nobody listens on any more. */
  private val listener: ZLayer[Server, Throwable, Int] =
    ZLayer.fromZIO(Server.install(Handler.fromResponse(Response.json(tokenJson)).toRoutes))

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
    versolaInternalTrustedCertificates = trust.map(_.toString).toSet,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val noIdentity: EdgeIdentity = new EdgeIdentity:
    override def certificate = ZIO.succeed(None)

  private val certificateFiles: ClientCertificateFiles = _ =>
    ZIO.succeed(ClientSSLCertConfig.FromClientCertFile("client.crt", "client.key"))

  /** One token call as a client that authenticates by secret, over the shared client as the services build it. */
  private def exchange(trust: Option[Path], port: Int): ZIO[Client, Throwable, Unit] =
    for
      httpClient <- ZIO.service[Client]
      sso = SSOClient.Impl(httpClient, edgeConfig(port, trust), certificateFiles, noIdentity)
      _ <- sso.exchangeAuthorizationCode(
        Code("c-1"),
        CodeVerifier("v-1"),
        RedirectUri("https://app.example/callback"),
        ClientId("web-app"),
        ClientCredential.ClientSecret(Secret("s3cret".getBytes("UTF-8").nn)),
      )
    yield ()

  def spec = suite("SSOClient internal trust")(
    test("a secret client's token call reaches a pinned terminator") {
      for
        pem <- ZIO.service[Pem]
        port <- ZIO.service[Int]
        _ <- exchange(Some(pem.serverTrust), port)
      yield assertCompletes
    },
    test("without a pin the terminator's private certificate is refused") {
      // The shared client trusts the JVM's authorities; a certificate none of them signed is not reached, and
      // the credential never leaves this edge. The same server still answers a pinned call, so the refusal is
      // the handshake's, not a dead port.
      for
        pem <- ZIO.service[Pem]
        port <- ZIO.service[Int]
        result <- exchange(None, port).either
        serving <- exchange(Some(pem.serverTrust), port).either
      yield assertTrue(result.isLeft, serving.isRight)
    },
    test("a pin naming another certificate is refused") {
      for
        pem <- ZIO.service[Pem]
        port <- ZIO.service[Int]
        result <- exchange(Some(pem.foreignTrust), port).either
        serving <- exchange(Some(pem.serverTrust), port).either
      yield assertTrue(result.isLeft, serving.isRight)
    },
  ).provideSome[Scope](
    pemFiles,
    serverLayer,
    listener,
    Observability.pooledClient(1),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
