package versola.oauth.mtls

import versola.util.{CoreConfig, EdgeIdentityNames}
import zio.{UIO, ZIO, ZLayer}

import java.io.FileInputStream
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Which certificates on auth's mutual-TLS listener are an edge's, rather than a client's.
  *
  * An edge that fronts an API (`/resources`) has verified the holder of a token at its own boundary,
  * and calls `/userinfo` on the holder's behalf: it can neither forward the holder's DPoP proof nor
  * present the holder's certificate. Auth accepts that call without either check only from a
  * certificate this recognises, because the certificate is then the proof that an edge it trusts
  * did the checking.
  *
  * A certificate is an edge's when it names exactly one edge (`urn:versola:edge:<id>`, the name central
  * puts in every edge certificate it issues) **and** was signed by one of the certificates in
  * `mutual-tls.edge-trusted-certificates`. The second condition is what matters. The listener's own
  * trust anchors (`trusted-certificates`) also vouch for clients' certificates, and an issuer of those
  * can put any name in a certificate it signs; the name alone proves nothing. The edge issuer is
  * therefore a separate, dedicated one, and a certificate it did not sign is a client's whatever it says.
  *
  * Absent `edge-trusted-certificates` no certificate is an edge's, and `/userinfo` behaves as it did.
  */
trait EdgeCertificateTrust:
  /** The id of the edge `certificate` belongs to, or `None` when it is not a certificate of one. */
  def edgeOf(certificate: X509Certificate): Option[String]

object EdgeCertificateTrust:
  private val UriName = 6

  val live: ZLayer[CoreConfig, Throwable, EdgeCertificateTrust] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig]: config =>
        config.mutualTls.flatMap(_.edgeTrustedCertificates) match
          case None => ZIO.succeed(none)
          case Some(path) => load(path).map(Impl(_))

  /** No certificate is an edge's. */
  val none: EdgeCertificateTrust = _ => None

  private[mtls] def load(path: String): ZIO[Any, Throwable, List[X509Certificate]] =
    ZIO.attemptBlocking:
      val factory = CertificateFactory.getInstance("X.509")
      val certificates = Using.resource(FileInputStream(path)): stream =>
        factory.generateCertificates(stream).asScala.toList.collect { case x509: X509Certificate => x509 }
      if certificates.isEmpty then
        throw IllegalArgumentException(s"mutual-tls.edge-trusted-certificates ($path) holds no certificate")
      certificates

  class Impl(issuers: List[X509Certificate]) extends EdgeCertificateTrust:
    override def edgeOf(certificate: X509Certificate): Option[String] =
      for
        edgeId <- edgeIdOf(certificate)
        // A leaf, not an issuer in its own right: a CA certificate carrying an edge's name would
        // let its holder sign certificates for any edge.
        if certificate.getBasicConstraints == -1
        if issuers.exists(signedBy(certificate, _))
      yield edgeId

  /** The edge id the certificate names, when it names exactly one edge. A certificate naming
    * several would let its holder call as any of them, the rule central applies when it accepts
    * one (`EdgeCertificate.isIssuedTo`). */
  private[mtls] def edgeIdOf(certificate: X509Certificate): Option[String] =
    val names =
      Try(Option(certificate.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil)).getOrElse(Nil)
        .collect:
          case entry if entry.size == 2 && entry.get(0) == Integer.valueOf(UriName) => String.valueOf(entry.get(1))
        .collect:
          case name if name.startsWith(EdgeIdentityNames.EdgePrefix) => name.stripPrefix(EdgeIdentityNames.EdgePrefix)
        .toSet
    names.headOption.filter(id => names.size == 1 && id.nonEmpty)

  /** Whether `issuer` signed `certificate`: the signature verifies under its key and the names
    * agree, so a certificate that merely claims an issuer it was not signed by is not accepted. */
  private def signedBy(certificate: X509Certificate, issuer: X509Certificate): Boolean =
    certificate.getIssuerX500Principal == issuer.getSubjectX500Principal &&
      Try(certificate.verify(issuer.getPublicKey)).isSuccess
