package versola.edge.nativeapp

import versola.util.TestCertificates
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The rotation story for auth's trust anchor (#440, relationship 1): `mutual-tls.trusted-certificates`
  * is a PEM file, and a PEM file may hold several CAs. Keeping the outgoing and the incoming CA
  * in one bundle for a window lets clients issued by either connect; dropping the outgoing one
  * ends it. No code on auth's side is involved -- this proves the property the documented
  * procedure (develop.md, k8s/README.md) relies on, against a listener configured the way
  * `PostgresOAuthApp.mutualTlsServerConfig` configures auth's own.
  */
object ClientCaBundleRotationSpec extends ZIOSpecDefault:

  private val outgoingCa = TestCertificates.generate(subject = "CN=client-ca-outgoing", ca = true)
  private val incomingCa = TestCertificates.generate(subject = "CN=client-ca-incoming", ca = true)
  private val server = TestCertificates.generate(subject = "CN=localhost", dnsName = Some("localhost"), issuer = Some(outgoingCa))
  private val oldClient = TestCertificates.generate(subject = "CN=old-client", issuer = Some(outgoingCa))
  private val newClient = TestCertificates.generate(subject = "CN=new-client", issuer = Some(incomingCa))

  private def write(directory: Path, name: String, content: String): Task[Path] =
    ZIO.attemptBlocking(Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8)).nn)

  /** A listener whose trust anchors are `anchors`, answering 200 to whoever completes the handshake. */
  private def listener(directory: Path, anchors: List[TestCertificates.Generated]): ZIO[Scope, Throwable, Int] =
    for
      cert <- write(directory, "server.crt", server.certificatePem)
      key <- write(directory, "server.key", server.privateKeyPem)
      bundle <- write(directory, s"anchors-${anchors.size}-${java.lang.System.nanoTime()}.pem", anchors.map(_.certificatePem).mkString("\n"))
      config = Server.Config.default.onAnyOpenPort.ssl(
        SSLConfig.fromFile(
          behaviour = SSLConfig.HttpBehaviour.Fail,
          certPath = cert.toString,
          keyPath = key.toString,
          clientAuth = Some(ClientAuth.Required),
          trustCertCollectionPath = Some(bundle.toString),
          includeClientCert = true,
        ),
      )
      // Built into this scope, not a `provide`'s: the server must outlive the install.
      environment <- (ZLayer.succeed(config) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized).build
      port <- Server.install(Handler.fromFunction[Request](_ => Response.ok).toRoutes).provideEnvironment(environment)
    yield port

  private def call(directory: Path, port: Int, client: TestCertificates.Generated): Task[Status] =
    for
      trust <- write(directory, "server-pin.crt", server.certificatePem)
      crt <- write(directory, s"client-${java.lang.System.nanoTime()}.crt", client.certificatePem)
      key <- write(directory, s"client-${java.lang.System.nanoTime()}.key", client.privateKeyPem)
      ssl = ClientSSLConfig.FromClientAndServerCert(
        ClientSSLConfig.FromCertFile(trust.toString),
        ClientSSLCertConfig.FromClientCertFile(crt.toString, key.toString),
      )
      status <- ZIO.scoped:
        Client.default.build.map(_.get[Client]).flatMap: http =>
          http.ssl(ssl).request(Request.get(URL.decode(s"https://localhost:$port/").toOption.get)).map(_.status)
    yield status

  private def connects(directory: Path, port: Int, client: TestCertificates.Generated): Task[Boolean] =
    call(directory, port, client).map(_ == Status.Ok).catchAll(_ => ZIO.succeed(false))

  private val tempDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("versola-ca-rotation").nn))(dir =>
      ZIO.attemptBlocking {
        import scala.jdk.CollectionConverters.*
        Files.walk(dir).nn.iterator.nn.asScala.toList.reverse.foreach(Files.deleteIfExists)
      }.ignoreLogged,
    )

  def spec = suite("auth's client-CA bundle through a rotation")(
    test("before the window only the outgoing CA's clients connect") {
      for
        directory <- tempDirectory
        port <- listener(directory, List(outgoingCa))
        old <- connects(directory, port, oldClient)
        incoming <- connects(directory, port, newClient)
      yield assertTrue(old, !incoming)
    },
    test("during the window clients issued by either CA connect") {
      for
        directory <- tempDirectory
        port <- listener(directory, List(outgoingCa, incomingCa))
        old <- connects(directory, port, oldClient)
        incoming <- connects(directory, port, newClient)
      yield assertTrue(old, incoming)
    },
    test("once the outgoing CA is dropped its clients are refused and the incoming CA's still connect") {
      for
        directory <- tempDirectory
        port <- listener(directory, List(incomingCa))
        old <- connects(directory, port, oldClient)
        incoming <- connects(directory, port, newClient)
      yield assertTrue(!old, incoming)
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
