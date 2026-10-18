package versola.central.configuration.edges

import java.security.cert.{Certificate, X509Certificate}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** What names an edge in the certificate it presents on central's mutual-TLS listener: a URI
  * subject alternative name, `urn:versola:edge:<id>`.
  *
  * The TLS handshake has already established that the certificate chains to the listener's trust
  * anchors, which are meant to be a CA that issues edge certificates and nothing else; this is the
  * other half, that it was issued to the edge the token names. An edge's certificate is therefore
  * provisioned and renewed by whatever deployed the edge (cert-manager, step-ca), with no
  * registration in central that a renewal could outrun.
  *
  * A URI name rather than the subject CN: it is a name no human or hostname can collide with, and
  * issuers that fill a CN from a hostname cannot be made to produce one by accident.
  */
object EdgeCertificate:
  private val UriNameType = 6
  private val Prefix = "urn:versola:edge:"

  def uriFor(edgeId: EdgeId): String = s"$Prefix$edgeId"

  /** The edge ids `certificate` carries a name for. Unreadable alternative names read as none. */
  def edgeIds(certificate: Certificate): Set[String] =
    certificate match
      case x509: X509Certificate =>
        Try(Option(x509.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil)).getOrElse(Nil)
          .collect:
            case entry if entry.size == 2 && entry.get(0) == Integer.valueOf(UriNameType) => String.valueOf(entry.get(1))
          .collect:
            case name if name.startsWith(Prefix) => name.stripPrefix(Prefix)
          .toSet
      case _ => Set.empty

  /** Only a certificate that names exactly this edge, and no other: one that named several would
    * let a holder of it call as any of them. */
  def isIssuedTo(certificate: Certificate, edgeId: EdgeId): Boolean =
    edgeIds(certificate) == Set(edgeId.toString)
