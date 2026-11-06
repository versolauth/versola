package versola.util.http

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import versola.util.CertificateSubject
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*
import zio.test.TestAspect.{sequential, silentLogging, withLiveClock}

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.util.Date

/** What the shared client trusts when it opens an HTTPS connection: the JVM's trust store, and only for the name the
  * certificate was issued to. zio-http's own default trusts whatever certificate the far side presents, which the first
  * case here would fail on.
  */
object ClientTrustSpec extends ZIOSpecDefault:

  private case class Pem(directory: Path, certificate: Path, key: Path)

  /** A certificate nobody but itself signed, issued to `localhost` and nothing else. */
  private def selfSigned: ZIO[Scope, Throwable, Pem] =
    ZIO.acquireRelease(
      Clock.instant.flatMap: now =>
        ZIO.attemptBlocking:
          val generator = KeyPairGenerator.getInstance("RSA")
          generator.initialize(2048)
          val pair = generator.generateKeyPair()
          val name = X500Name("CN=localhost")
          val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now.toEpochMilli),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            name,
            pair.getPublic,
          )
          builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "localhost")))
          val certificate =
            JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate)))
          val directory = Files.createTempDirectory("client-trust")
          val certificatePath = Files.write(
            directory.resolve("server.crt"),
            CertificateSubject.pem("CERTIFICATE", certificate.getEncoded).getBytes(StandardCharsets.UTF_8),
          )
          val keyPath = Files.write(
            directory.resolve("server.key"),
            CertificateSubject.pem("PRIVATE KEY", pair.getPrivate.getEncoded).getBytes(StandardCharsets.UTF_8),
          )
          Pem(directory = directory, certificate = certificatePath, key = keyPath),
    )(pem =>
      ZIO.attemptBlocking {
        Files.deleteIfExists(pem.certificate)
        Files.deleteIfExists(pem.key)
        Files.deleteIfExists(pem.directory)
      }.ignoreLogged,
    )

  private def server(pem: Pem): ZIO[Scope, Throwable, Int] =
    val config = Server.Config.default.onAnyOpenPort.ssl(
      SSLConfig.fromFile(behaviour = SSLConfig.HttpBehaviour.Fail, certPath = pem.certificate.toString, keyPath = pem.key.toString),
    )
    for
      environment <- (ZLayer.succeed(config) ++ ZLayer.succeed(NettyConfig.default) >>> Server.customized).build
      port <- Server.install(Handler.fromResponse(Response.ok).toRoutes).provideEnvironment(environment)
    yield port

  private def get(client: Client, url: String): IO[Throwable, Response] =
    ZIO.fromEither(URL.decode(url)).flatMap(url => client.batched(Request.get(url)))

  def spec = suite("the shared client's trust")(
    test("refuses a server whose certificate no trusted authority signed") {
      for
        pem <- selfSigned
        port <- server(pem)
        client <- ZIO.service[Client]
        result <- get(client, s"https://localhost:$port/").exit
      yield assertTrue(result.isFailure)
    },
    test("accepts a server whose certificate a caller pinned, under the name it was issued to") {
      for
        pem <- selfSigned
        port <- server(pem)
        client <- ZIO.service[Client]
        response <- get(client.ssl(ClientSSLConfig.FromCertFile(pem.certificate.toString)), s"https://localhost:$port/")
      yield assertTrue(response.status == Status.Ok)
    },
    test("refuses a pinned certificate presented under another name") {
      for
        pem <- selfSigned
        port <- server(pem)
        client <- ZIO.service[Client]
        result <- get(client.ssl(ClientSSLConfig.FromCertFile(pem.certificate.toString)), s"https://127.0.0.1:$port/").exit
      yield assertTrue(result.isFailure)
    },
  ).provideSome[Scope](Observability.pooledClient(1)) @@ withLiveClock @@ silentLogging @@ sequential
