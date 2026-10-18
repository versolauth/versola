package versola.edge

import org.bouncycastle.asn1.x509.{Extension, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import versola.edge.model.EdgeId
import versola.util.{CertificateSubject, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.io.StringReader
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.util.Date

/** The edge's side of central issuing its own certificate: when it asks, what it asks for, which
  * certificate it asks with, and that a failure leaves the current one in use. Central is played
  * by a function that signs with a CA of its own, as `EdgeCertificateService` does. */
object CentralCertificateSpec extends ZIOSpecDefault:

  private val ca = TestCertificates.generate(subject = "CN=edge-ca", ca = true)

  private case class SignRequest(csr: String) derives JsonCodec
  private case class SignResponse(certificate: String) derives JsonCodec

  private val rsa =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private def write(suffix: String, pem: String): Path =
    val path = Files.createTempFile("central-certificate-spec", suffix).nn
    path.toFile.nn.deleteOnExit()
    Files.write(path, pem.getBytes(StandardCharsets.UTF_8)).nn

  private val centralLeaf = write(".pem", TestCertificates.generate(subject = "CN=central.internal,O=Versola").certificatePem)

  /** A certificate for `CN=edge-1` valid for `lifetime`, already past `elapsed` of it. */
  private def initial(lifetime: Duration, elapsed: Duration): TestCertificates.Generated =
    val now = java.lang.System.currentTimeMillis()
    TestCertificates.generate(
      subject = "CN=edge-1,OU=edge,O=Versola",
      validity = Some((Date(now - elapsed.toMillis), Date(now + lifetime.minus(elapsed).toMillis))),
    )

  private def configFor(certificate: TestCertificates.Generated) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = rsa.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(
      url = URL.decode("https://central.example:8093").toOption.get,
      mutualTls = Some(
        EdgeConfig.CentralMutualTls(
          certificate = write(".crt", certificate.certificatePem).toString,
          privateKey = write(".key", certificate.privateKeyPem).toString,
          trustedCertificates = Set(centralLeaf.toString),
        ),
      ),
    ),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  /** Central: signs the CSR for whatever subject it names, for `lifetime`, and records each call. */
  private final class FakeCentral(lifetime: Duration, answer: Option[Int] = None):
    var calls: List[(ClientSSLConfig, Request, String)] = Nil

    val send: CentralCertificate.Send = (ssl, request) =>
      for
        body <- request.body.asString
        csr = body.fromJson[SignRequest].toOption.get.csr
        _ = calls = calls :+ ((ssl, request, csr))
      yield answer match
        case Some(code) => Response.status(Status.fromInt(code))
        case None => Response.json(SignResponse(sign(csr)).toJson)

    private def sign(csrPem: String): String =
      val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
      val now = java.lang.System.currentTimeMillis()
      val builder = JcaX509v3CertificateBuilder(ca.certificate, BigInteger.valueOf(now), Date(now - 60_000), Date(now + lifetime.toMillis), csr.getSubject, csr.getPublicKey)
      Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName))).foreach: san =>
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames.getInstance(san.getParsedValue))
      val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)))
      CertificateSubject.pem("CERTIFICATE", certificate.getEncoded)

  private def certificateWith(certificate: TestCertificates.Generated, central: FakeCentral): ZIO[Scope, Throwable, CentralCertificate] =
    for
      files <- ClientCertificateFiles.live.build.map(_.get[ClientCertificateFiles])
      cert <- CentralCertificate.make(configFor(certificate), files, ZIO.succeed("edge-token"), central.send)
    yield cert

  def spec = suite("CentralCertificate")(
    test("does not ask central while more than a third of the lifetime is left") {
      val central = FakeCentral(30.days)
      ZIO.scoped:
        for
          certificate <- certificateWith(initial(30.days, 1.day), central)
          _ <- certificate.renewIfDue
        yield assertTrue(central.calls.isEmpty)
    },
    test("asks for the edge's own subject, authenticated with the certificate it holds, once a third is left") {
      val central = FakeCentral(30.days)
      ZIO.scoped:
        for
          certificate <- certificateWith(initial(30.days, 21.days), central)
          before <- certificate.ssl
          _ <- certificate.renewIfDue
          after <- certificate.ssl
          (sentOver, request, csr) = central.calls.head
          matched = CertificateSubject.matches(csr, CertificateSubject.forEdge("edge-1"))
        yield assertTrue(
          central.calls.size == 1,
          request.url.path.encode.stripPrefix("/") == "edge/certificate/sign",
          request.headers.get("Authorization").contains("Bearer edge-token"),
          sentOver == before,
          matched.isRight,
          after != before,
        )
    },
    test("having renewed, is not due again") {
      val central = FakeCentral(30.days)
      ZIO.scoped:
        for
          certificate <- certificateWith(initial(30.days, 21.days), central)
          _ <- certificate.renewIfDue
          _ <- certificate.renewIfDue
        yield assertTrue(central.calls.size == 1)
    },
    test("keeps presenting the current certificate when central refuses, and says so") {
      val central = FakeCentral(30.days, answer = Some(422))
      ZIO.scoped:
        for
          certificate <- certificateWith(initial(30.days, 21.days), central)
          before <- certificate.ssl
          exit <- certificate.renewIfDue.exit
          after <- certificate.ssl
        yield assertTrue(exit.isFailure, after == before)
    },
    test("refuses to start when the files hold no certificate") {
      val config = configFor(initial(30.days, 1.day))
      val broken = config.copy(central = config.central.copy(mutualTls = config.central.mutualTls.map(_.copy(certificate = centralLeaf.toString))))
      ZIO.scoped:
        for
          files <- ClientCertificateFiles.live.build.map(_.get[ClientCertificateFiles])
          exit <- CentralCertificate.make(broken, files, ZIO.succeed("t"), FakeCentral(1.day).send).exit
        yield assertTrue(exit.isFailure)
    },
  ) @@ TestAspect.silentLogging @@ TestAspect.withLiveClock
