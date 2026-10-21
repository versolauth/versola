package versola.util

import java.util.Locale
import javax.security.auth.x500.X500Principal

/** What an edge's certificate says, shared by central, which decides it, and by the edge, which asks for exactly
  * that: a URI name for the edge (`urn:versola:edge:<id>`) and one for the group of edges it belongs to
  * (`urn:versola:edge-group:<group>`).
  *
  * Names no human or hostname can collide with, in namespaces that mean "an edge" and "a group of edges" to
  * central and auth. Only an edge is ever issued one, so a client certificate cannot be mistaken for an edge's.
  */
object EdgeIdentityNames:
  val EdgePrefix: String = "urn:versola:edge:"
  val GroupPrefix: String = "urn:versola:edge-group:"

  def edgeUri(edgeId: String): String = s"$EdgePrefix$edgeId"

  def groupUri(groupId: String): String = s"$GroupPrefix$groupId"

  /** The namespace as a whole, every name below it. */
  val Namespace: String = "urn:versola:"

  /** Whether `uri` is in a namespace that is only ever issued to an edge: anything below `urn:versola:`.
    *
    * The scheme and the namespace identifier of a URN are case-insensitive (RFC 8141 §3.1), and a name with surrounding
    * whitespace is one a lenient parser may read as the plain one, so a name is compared lowercased and trimmed:
    * `URN:Versola:Edge:x` is as reserved as `urn:versola:edge:x`. This is the check that keeps a client from being issued
    * an edge's name. The check that accepts an edge's name is the opposite and stays exact (see `EdgeCertificate`):
    * a variant spelling is refused as an identity, and refused as a client's name. */
  def isReserved(uri: String): Boolean = uri.trim.toLowerCase(Locale.ROOT).startsWith(Namespace)

  /** The certificate of `edgeId`, member of `groupId`: CN the edge's id, the two names, nothing else. */
  def subject(edgeId: String, groupId: String): CertificateSubject =
    CertificateSubject(
      distinguishedName = X500Principal(s"CN=$edgeId").getName(X500Principal.RFC2253),
      commonName = edgeId,
      dnsNames = Nil,
      uris = List(edgeUri(edgeId), groupUri(groupId)),
      emailAddresses = Nil,
      ipAddresses = Nil,
    )
