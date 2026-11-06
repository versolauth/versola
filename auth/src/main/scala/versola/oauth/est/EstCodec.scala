package versola.oauth.est

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.{CMSAbsentContent, CMSSignedDataGenerator}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import versola.util.CertificateSubject

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The wire formats of RFC 7030: a PKCS#10 request in a base64 body (§4.2.1), and certificates in a certs-only PKCS#7
  * (§4.1.3, §4.2.3), also base64. */
object EstCodec:

  /** The PKCS#10 in `body` (base64 of the DER, line breaks allowed; the PEM armour some clients leave on is tolerated) as a
    * PEM request. It has to parse: what it says is for central to judge. */
  def requestPem(body: String): Either[String, String] =
    val base64 = body.linesIterator.map(_.trim).filterNot(line => line.isEmpty || line.startsWith("-----")).mkString
    for
      der <- Try(Base64.getMimeDecoder.decode(base64)).toEither.left.map(_ => "the request is not base64")
      _ <- Either.cond(der.nonEmpty, (), "the request is empty")
      request <- Try(PKCS10CertificationRequest(der)).toEither.left.map(error => s"the request is not a PKCS#10: ${error.getMessage}")
      // RFC 7030 §3.5: a server MUST verify the tls-unique value a client puts in `challengePassword`, and tls-unique is
      // not defined for TLS 1.3. Verifying it where the session allows would be a promise kept for some clients and not
      // others, so a request that asks for channel binding is refused, saying so, instead of being served without it.
      _ <- Either.cond(
        request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_challengePassword).isEmpty,
        (),
        "channel binding is not supported: send the request without challengePassword (RFC 7030 §3.5)",
      )
    yield CertificateSubject.pem("CERTIFICATE REQUEST", der)

  /** A certs-only PKCS#7 holding `certificates`, base64 in lines of 64 characters. */
  def certsOnly(certificates: List[X509Certificate]): Array[Byte] =
    val generator = CMSSignedDataGenerator()
    generator.addCertificates(JcaCertStore(certificates.asJava))
    base64Lines(generator.generate(CMSAbsentContent()).getEncoded)

  /** The certificates of a PEM chain, in order. */
  def certificates(pem: String): Try[List[X509Certificate]] =
    Try:
      CertificateFactory.getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
        .asScala.toList.map(_.asInstanceOf[X509Certificate])

  private def base64Lines(der: Array[Byte]): Array[Byte] =
    Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encode(der) ++ "\n".getBytes(StandardCharsets.US_ASCII)
