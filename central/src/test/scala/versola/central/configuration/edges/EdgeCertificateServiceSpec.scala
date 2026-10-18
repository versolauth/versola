package versola.central.configuration.edges

import org.bouncycastle.asn1.x509.{Extension, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.configuration.clients.certificates.{CertificateSigningRequest, ClientCertificateIssuer, ClientCertificateRequests}
import versola.util.{CertificateSubject, TestCertificates}
import zio.*
import zio.test.*

import java.io.{ByteArrayInputStream, StringReader}
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Date

/** What central does when an edge asks for its next certificate: it signs only a request that
  * says exactly what an edge's certificate must say, and pins what the CA returned -- the leaf's
  * thumbprint as current, the certificate the request was made with as old -- or pins nothing. */
object EdgeCertificateServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-a")
  private val presented = "thumbprint-of-the-certificate-in-the-handshake"
  private val ca = TestCertificates.generate(subject = "CN=test-edge-ca", ca = true)

  private class FakeCa(subjectOverride: Option[String] = None) extends ClientCertificateIssuer:
    var requests: List[CertificateSigningRequest] = Nil

    override def sign(request: CertificateSigningRequest): Task[String] =
      ZIO.attempt:
        requests = requests :+ request
        val csr = JcaPKCS10CertificationRequest(PEMParser(StringReader(request.csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest])
        val now = java.lang.System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
          ca.certificate,
          BigInteger.valueOf(now),
          Date(now - 60_000),
          Date(now + request.validity.toMillis),
          subjectOverride.fold(csr.getSubject)(org.bouncycastle.asn1.x500.X500Name(_)),
          csr.getPublicKey,
        )
        Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName))).foreach: san =>
          builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames.getInstance(san.getParsedValue))
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)))
        ClientCertificateRequests.pem("CERTIFICATE", certificate.getEncoded)

  private val failingCa: ClientCertificateIssuer = _ => ZIO.fail(RuntimeException("CA unavailable"))

  private def requestFor(subject: CertificateSubject) = CertificateSubject.generate(subject).map(_.csrPem)

  private def leafOf(chain: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
      .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
      .iterator.next.asInstanceOf[X509Certificate]

  def spec = suite("EdgeCertificateService")(
    test("signs a request for the edge's own subject and pins the leaf, keeping the presented certificate as old") {
      val edges = stub[EdgeService]
      val fakeCa = FakeCa()
      val service = EdgeCertificateService.Impl(fakeCa, edges, TestCentralConfig.config)
      for
        _ <- edges.pinCertificates.succeedsWith(())
        csr <- requestFor(CertificateSubject.forEdge(edgeId))
        chain <- service.sign(edgeId, presented, csr)
      yield assertTrue(
        fakeCa.requests.map(_.commonName) == List(edgeId.toString),
        fakeCa.requests.map(_.uris) == List(List(s"urn:versola:edge:$edgeId")),
        edges.pinCertificates.calls == List((edgeId, EdgeCertificate.thumbprint(leafOf(chain)), Some(presented))),
      )
    },
    test("refuses a request for another edge's subject: the CA is not asked and nothing is pinned") {
      val edges = stub[EdgeService]
      val fakeCa = FakeCa()
      val service = EdgeCertificateService.Impl(fakeCa, edges, TestCentralConfig.config)
      for
        _ <- edges.pinCertificates.succeedsWith(())
        csr <- requestFor(CertificateSubject.forEdge(EdgeId("edge-b")))
        exit <- service.sign(edgeId, presented, csr).exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[EdgeCertificateService.SigningRefused]),
        fakeCa.requests.isEmpty,
        edges.pinCertificates.calls.isEmpty,
      )
    },
    test("refuses a request that names more than the edge's subject") {
      val edges = stub[EdgeService]
      val fakeCa = FakeCa()
      val service = EdgeCertificateService.Impl(fakeCa, edges, TestCentralConfig.config)
      for
        _ <- edges.pinCertificates.succeedsWith(())
        csr <- requestFor(CertificateSubject.forEdge(edgeId).copy(dnsNames = List("auth.example.com")))
        exit <- service.sign(edgeId, presented, csr).exit
      yield assertTrue(exit.isFailure, fakeCa.requests.isEmpty, edges.pinCertificates.calls.isEmpty)
    },
    test("pins nothing when the CA returns a certificate for another subject") {
      val edges = stub[EdgeService]
      val service = EdgeCertificateService.Impl(FakeCa(Some("CN=edge-b,OU=edge,O=Versola")), edges, TestCentralConfig.config)
      for
        _ <- edges.pinCertificates.succeedsWith(())
        csr <- requestFor(CertificateSubject.forEdge(edgeId))
        exit <- service.sign(edgeId, presented, csr).exit
      yield assertTrue(exit.isFailure, edges.pinCertificates.calls.isEmpty)
    },
    test("pins nothing when the CA is unavailable") {
      val edges = stub[EdgeService]
      val service = EdgeCertificateService.Impl(failingCa, edges, TestCentralConfig.config)
      for
        _ <- edges.pinCertificates.succeedsWith(())
        csr <- requestFor(CertificateSubject.forEdge(edgeId))
        exit <- service.sign(edgeId, presented, csr).exit
      yield assertTrue(exit.isFailure, edges.pinCertificates.calls.isEmpty)
    },
    test("an edge id with RFC 4514 special characters yields a subject the request round-trips through") {
      val subject = CertificateSubject.forEdge("edge,a+b")
      for csr <- requestFor(subject)
      yield assertTrue(CertificateSubject.matches(csr, subject).isRight, subject.commonName == "edge,a+b")
    },
  )
