package versola.central.configuration.edges

import versola.util.EdgeIdentityNames

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
  val NamePrefix: String = EdgeIdentityNames.EdgePrefix

  /** The name of the group an edge belongs to, `urn:versola:edge-group:<id>`. A client served by edge is
    * registered by this name (`san_uri`), so any edge of the group authenticates it to auth. */
  val GroupNamePrefix: String = EdgeIdentityNames.GroupPrefix

  def uriFor(edgeId: EdgeId): String = s"$NamePrefix$edgeId"

  def groupUriFor(groupId: EdgeGroupId): String = s"$GroupNamePrefix$groupId"

  /** Whether `uri` is in a namespace that means "an edge" or "a group of edges" to central or auth. Such a name
    * is only ever issued to an edge, by [[versola.central.configuration.edges.EdgeIdentityService]]; a client is
    * never issued one. */
  def isReserved(uri: String): Boolean = EdgeIdentityNames.isReserved(uri)

  /** The URI names `certificate` carries. Unreadable alternative names read as none. */
  def uriNames(certificate: Certificate): List[String] =
    certificate match
      case x509: X509Certificate =>
        Try(Option(x509.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil)).getOrElse(Nil)
          .collect:
            case entry if entry.size == 2 && entry.get(0) == Integer.valueOf(UriNameType) => String.valueOf(entry.get(1))
      case _ => Nil

  /** The edge ids `certificate` carries a name for. */
  def edgeIds(certificate: Certificate): Set[String] =
    uriNames(certificate).collect { case name if name.startsWith(NamePrefix) => name.stripPrefix(NamePrefix) }.toSet

  /** The edge group ids `certificate` carries a name for. */
  def groupIds(certificate: Certificate): Set[String] =
    uriNames(certificate).collect { case name if name.startsWith(GroupNamePrefix) => name.stripPrefix(GroupNamePrefix) }.toSet

  /** Only a certificate that names exactly this edge, and no other: one that named several would
    * let a holder of it call as any of them. */
  def isIssuedTo(certificate: Certificate, edgeId: EdgeId): Boolean =
    edgeIds(certificate) == Set(edgeId.toString)

  /** The same, and a group name the certificate may carry has to be this edge's own group. A certificate with no
    * group name (the ones cert-manager and `mtls-init` issued before groups) still passes; one naming a group other
    * than the edge's is refused, so an edge cannot present itself as a member of another group. */
  def isIssuedTo(certificate: Certificate, edgeId: EdgeId, groupId: EdgeGroupId): Boolean =
    isIssuedTo(certificate, edgeId) && groupIds(certificate).subsetOf(Set(groupId.toString))
