package versola.oauth.mtls

import versola.oauth.client.model.{MtlsCertificateEncoding, MutualTlsAuth, MutualTlsSubjectType}
import versola.util.Base64

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.security.auth.x500.X500Principal
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** The client certificate the tenant's reverse proxy validated and forwarded, reduced to the
  * two things RFC 8705 asks of it: the thumbprint §3.1 binds a token to, and the subject
  * values §2.1.2 recognises a client by.
  *
  * The certificate itself is not carried further. Its chain was validated by the proxy — `auth`
  * never sees the trust anchors and so has nothing to re-check — and keeping the parsed object
  * around would invite code that re-derives either of these differently.
  */
case class ClientCertificate(
    /** RFC 8705 §3.1 `x5t#S256`: base64url-encoded SHA-256 hash of the DER certificate. */
    thumbprint: String,
    /** RFC 4514 string form of the subject, which is what `subject_dn` registers. */
    subjectDn: String,
    /** Subject alternative names, keyed by the subject type that reads them. Keyed rather
      * than four lists because matching only ever asks for the one the client registered. */
    subjectAlternativeNames: Map[MutualTlsSubjectType, Set[String]],
):

  /** RFC 8705 §2.1.2: the certificate authenticates the client when the value the client
    * registered appears in the field its subject type names. Compared literally — the
    * registered value is normalised at registration, not here. */
  def matches(auth: MutualTlsAuth): Boolean =
    auth.subjectType match
      case MutualTlsSubjectType.subject_dn =>
        subjectDn == auth.subjectValue
      case sanType =>
        subjectAlternativeNames.getOrElse(sanType, Set.empty).contains(auth.subjectValue)

object ClientCertificate:

  /** X.509 `GeneralName` tags of the alternative names RFC 8705 §2.1.2 registers a client by;
    * the rest of the tags have no registered subject type and are dropped. */
  private val SanTypes = Map(
    1 -> MutualTlsSubjectType.san_email,
    2 -> MutualTlsSubjectType.san_dns,
    6 -> MutualTlsSubjectType.san_uri,
    7 -> MutualTlsSubjectType.san_ip,
  )

  private val PercentEscape: Regex = "%([0-9A-Fa-f]{2})".r

  /** Parses the value of the tenant's configured certificate header.
    *
    * @return the reason the value could not be read as a certificate, which is a fact about
    *         the deployment's proxy rather than about the client, so callers log it instead of
    *         returning it.
    */
  def parse(headerValue: String, encoding: MtlsCertificateEncoding): Either[String, ClientCertificate] =
    for
      bytes <- decode(leaf(headerValue), encoding)
      certificate <- Try(
        CertificateFactory.getInstance("X.509")
          .generateCertificate(ByteArrayInputStream(bytes)),
      ).toEither.left.map(error => s"not a valid X.509 certificate: ${error.getMessage}")
      x509 <- certificate match
        case x509: X509Certificate => Right(x509)
        case other => Left(s"not an X.509 certificate: ${other.getType}")
    yield from(x509)

  /** Traefik's `passTLSClientCert` forwards the whole chain the proxy validated, comma
    * separated, leaf first. Only the leaf is the client, and the chain was already validated
    * where the trust anchors are, so the rest is dropped rather than parsed. A header carrying
    * a single certificate is unaffected: it has no comma to split on. */
  private def leaf(headerValue: String): String =
    headerValue.split(',').headOption.getOrElse(headerValue).trim

  private def decode(value: String, encoding: MtlsCertificateEncoding): Either[String, Array[Byte]] =
    encoding match
      case MtlsCertificateEncoding.urlEncodedPem =>
        // Percent escapes are undone by hand rather than with `URLDecoder`, which also maps
        // `+` to a space per `application/x-www-form-urlencoded`. nginx does not escape spaces
        // that way, and `+` is one of base64's own characters, so decoding it as a space
        // corrupts roughly every certificate that happens to contain one.
        val pem = PercentEscape.replaceAllIn(
          value,
          matched => Regex.quoteReplacement(Integer.parseInt(matched.group(1), 16).toChar.toString),
        )
        // PEM is ASCII; the factory reads the delimiters and base64 body itself.
        Right(pem.getBytes(StandardCharsets.US_ASCII))

      case MtlsCertificateEncoding.base64Der =>
        // The MIME decoder ignores line breaks and other non-alphabet characters, so a proxy
        // that wraps the base64 or leaves a trailing newline still decodes.
        Try(java.util.Base64.getMimeDecoder.decode(value)).toEither.left
          .map(error => s"not valid base64: ${error.getMessage}")

  private def from(certificate: X509Certificate): ClientCertificate =
    ClientCertificate(
      thumbprint = Base64.urlEncode(
        MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded),
      ),
      subjectDn = certificate.getSubjectX500Principal.getName(X500Principal.RFC2253),
      subjectAlternativeNames = subjectAlternativeNames(certificate),
    )

  /** `getSubjectAlternativeNames` returns `null` when the extension is absent, and throws on a
    * malformed one — a certificate with an unreadable SAN extension still authenticates by
    * `subject_dn`, so that is treated as having no alternative names rather than as a parse
    * failure of the whole certificate. */
  private def subjectAlternativeNames(
      certificate: X509Certificate,
  ): Map[MutualTlsSubjectType, Set[String]] =
    Try(Option(certificate.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil))
      .getOrElse(Nil)
      .flatMap: entry =>
        entry.asScala.toList match
          case (tag: Integer) :: (value: String) :: Nil =>
            SanTypes.get(tag.intValue).map(_ -> value)
          case _ =>
            None
      .groupMap(_._1)(_._2)
      .view.mapValues(_.toSet).toMap
