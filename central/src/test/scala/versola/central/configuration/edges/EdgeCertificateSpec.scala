package versola.central.configuration.edges

import versola.util.{Base64Url, TestCertificates}
import zio.test.*

import java.security.MessageDigest

object EdgeCertificateSpec extends ZIOSpecDefault:
  private val certificate = TestCertificates.generate()

  def spec = suite("EdgeCertificate")(
    test("the thumbprint is the base64url SHA-256 of the DER encoding (RFC 8705 §3.1)") {
      val expected = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(certificate.certificate.getEncoded))
      assertTrue(EdgeCertificate.thumbprint(certificate.certificate) == expected)
    },
    test("a PEM and the certificate it encodes have the same thumbprint") {
      assertTrue(
        EdgeCertificate.thumbprintOfPem(certificate.certificatePem) ==
          Right(EdgeCertificate.thumbprint(certificate.certificate)),
      )
    },
    test("refuse text that is not a certificate") {
      assertTrue(EdgeCertificate.thumbprintOfPem("not a certificate").isLeft)
    },
    test("refuse a private key") {
      assertTrue(EdgeCertificate.thumbprintOfPem(certificate.privateKeyPem).isLeft)
    },
    test("refuse a bundle of two certificates: it would not say which one the edge presents") {
      val bundle = certificate.certificatePem + "\n" + TestCertificates.generate().certificatePem
      assertTrue(EdgeCertificate.thumbprintOfPem(bundle).isLeft)
    },
  )
