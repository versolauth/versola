package versola.central.configuration.edges

import versola.util.Base64Url

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{Certificate, CertificateFactory}
import scala.util.Try

/** RFC 8705 §3.1 `x5t#S256`: the base64url-encoded SHA-256 of a certificate's DER encoding.
  * It is what an edge is registered by and what the listener compares the presented certificate
  * to -- the certificate itself is never stored, so there is no second parse that could read it
  * differently.
  */
object EdgeCertificate:
  def thumbprint(certificate: Certificate): String =
    Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded))

  /** The thumbprint of a single PEM certificate. A bundle is refused: pinning "whichever of
    * these" would mean the registration does not say which certificate the edge presents. */
  def thumbprintOfPem(pem: String): Either[String, String] =
    Try {
      val certificates = CertificateFactory.getInstance("X.509")
        .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
      require(certificates.size == 1, "expected exactly one certificate")
      thumbprint(certificates.iterator.next)
    }.toEither.left.map(e => s"Not a single PEM-encoded X.509 certificate: ${e.getMessage}")
