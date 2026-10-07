package versola.edge.nativeapp

import versola.edge.model.EdgeId
import versola.edge.{ClientCertificateFiles, EdgeConfig}
import versola.util.{PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate

/** The native back channel over a real handshake against a listener shaped like auth's own
  * (#417): `ClientAuth.Required` against a CA, the client certificate issued by that CA, the
  * listener's own certificate pinned on edge's side. What [[NativeServiceSpec]] stubs away.
  */
object NativeAuthClientHandshakeSpec extends ZIOSpecDefault:

  private val ca = TestCertificates.generate(subject = "CN=test-internal-ca", ca = true)
  private val server = TestCertificates.generate(subject = "CN=localhost", dnsName = Some("localhost"), issuer = Some(ca))
  private val client = TestCertificates.generate(subject = "CN=mobile-app", issuer = Some(ca))

  private def material(generated: TestCertificates.Generated): PrivateClientCertificate.Material =
    PrivateClientCertificate(generated.bundle).material.toOption.get

  private final case class Seen(certificate: Option[X509Certificate], form: Form, dpop: Option[String], path: String)
  private final case class Listener(port: Int, seen: Ref[Option[Seen]], serverPin: Path)

  private def write(directory: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8)).nn)

  private val files: ZLayer[Scope, Throwable, (Path, Path, Path, Path)] =
    ZLayer.fromZIO(
      for
        directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("versola-native-mtls").nn))(dir =>
          ZIO.attemptBlocking {
            import scala.jdk.CollectionConverters.*
            Files.walk(dir).nn.iterator.nn.asScala.toList.reverse.foreach(Files.deleteIfExists)
          }.ignoreLogged,
        )
        cert <- write(directory, "server.crt", server.certificatePem)
        key <- write(directory, "server.key", server.privateKeyPem)
        anchors <- write(directory, "ca.crt", ca.certificatePem)
        pin <- write(directory, "pin.crt", server.certificatePem)
      yield (cert, key, anchors, pin),
    )

  private val serverLayer: ZLayer[(Path, Path, Path, Path), Throwable, Server] =
    ZLayer.fromFunction((paths: (Path, Path, Path, Path)) =>
      Server.Config.default.onAnyOpenPort.ssl(
        SSLConfig.fromFile(
          behaviour = SSLConfig.HttpBehaviour.Fail,
          certPath = paths._1.toString,
          keyPath = paths._2.toString,
          clientAuth = Some(ClientAuth.Required),
          trustCertCollectionPath = Some(paths._3.toString),
          includeClientCert = true,
        ),
      ),
    ) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized

  private val listener: ZLayer[Server & (Path, Path, Path, Path), Throwable, Listener] =
    ZLayer.fromZIO(
      for
        paths <- ZIO.service[(Path, Path, Path, Path)]
        seen <- Ref.make(Option.empty[Seen])
        port <- Server.install(
          Handler.fromFunctionZIO[Request] { request =>
            request.body.asURLEncodedForm.orDie.flatMap: form =>
              seen.set(Some(Seen(
                request.remoteCertificate.collect { case x509: X509Certificate => x509 },
                form,
                request.rawHeader("DPoP"),
                request.path.encode,
              ))).as(
                Response.json("""{"error":"use_dpop_nonce"}""")
                  .status(Status.BadRequest)
                  .addHeader(Header.Custom("DPoP-Nonce", "nonce-1"))
                  .addHeader(Header.CacheControl.NoStore),
              )
          }.toRoutes,
        )
      yield Listener(port, seen, paths._4),
    )

  private val rsa =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private def edgeConfig(listening: Listener) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = rsa.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    native = Some(EdgeConfig.Native(
      authMutualTlsUrl = URL.decode(s"https://localhost:${listening.port}").toOption.get,
      trustedCertificates = Set(listening.serverPin.toString),
      blobKey = Secret.Bytes32(Array.fill(32)(7.toByte)),
    )),
  )

  def spec = suite("NativeAuthClient over auth's mutual-TLS listener")(
    test("presents the synced certificate, names the client and forwards the device proof as sent") {
      for
        listening <- ZIO.service[Listener]
        httpClient <- ZIO.service[Client]
        certificateFiles <- ZIO.service[ClientCertificateFiles]
        authClient = NativeAuthClient.Impl(httpClient, edgeConfig(listening), certificateFiles)
        relayed <- authClient.token(
          "mobile-app",
          material(client),
          Form(FormField.simpleField("grant_type", "refresh_token"), FormField.simpleField("refresh_token", "rt")),
          Headers(Header.Custom("DPoP", "proof.jwt.value")),
        )
        seen <- listening.seen.get
        response = relayed.toResponse
        body <- response.body.asString
      yield assertTrue(
        seen.flatMap(_.certificate).contains(client.certificate),
        seen.exists(_.path == "/token"),
        seen.flatMap(_.form.get("client_id").flatMap(_.stringValue)).contains("mobile-app"),
        seen.flatMap(_.dpop).contains("proof.jwt.value"),
        response.status == Status.BadRequest,
        response.rawHeader("DPoP-Nonce").contains("nonce-1"),
        body.contains("use_dpop_nonce"),
        // Hop-by-hop framing belongs to the connection it came on.
        response.rawHeader("Content-Length").isEmpty,
      )
    },
    test("refuses to talk to a listener whose certificate is not the pinned one") {
      for
        listening <- ZIO.service[Listener]
        paths <- ZIO.service[(Path, Path, Path, Path)]
        httpClient <- ZIO.service[Client]
        certificateFiles <- ZIO.service[ClientCertificateFiles]
        _ <- listening.seen.set(None)
        otherPin <- write(paths._4.getParent, "other.crt", TestCertificates.generate(subject = "CN=someone-else").certificatePem)
        authClient = NativeAuthClient.Impl(httpClient, edgeConfig(listening.copy(serverPin = otherPin)), certificateFiles)
        result <- authClient.par("mobile-app", material(client), Form(FormField.simpleField("scope", "openid"))).either
        seen <- listening.seen.get
      yield assertTrue(result.isLeft, seen.isEmpty)
    },
    // The rotation window: the outgoing pin and the incoming one are both published, and auth
    // may be presenting either. A peer matching any entry is accepted.
    test("accepts the listener when its certificate is any one of several pins") {
      for
        listening <- ZIO.service[Listener]
        paths <- ZIO.service[(Path, Path, Path, Path)]
        httpClient <- ZIO.service[Client]
        certificateFiles <- ZIO.service[ClientCertificateFiles]
        otherPin <- write(paths._4.getParent, "other-rotated.crt", TestCertificates.generate(subject = "CN=outgoing").certificatePem)
        config = edgeConfig(listening).copy(native = edgeConfig(listening).native.map(native =>
          native.copy(trustedCertificates = Set(otherPin.toString, listening.serverPin.toString)),
        ))
        authClient = NativeAuthClient.Impl(httpClient, config, certificateFiles)
        relayed <- authClient.par("mobile-app", material(client), Form(FormField.simpleField("scope", "openid")))
      yield assertTrue(relayed.status == Status.BadRequest)
    },
  ).provideSome[Scope](
    files,
    serverLayer,
    listener,
    ClientCertificateFiles.live,
    Client.default,
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
