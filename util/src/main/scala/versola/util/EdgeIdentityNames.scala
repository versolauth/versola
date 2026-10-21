package versola.util

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

  /** Whether `uri` is in either namespace. */
  def isReserved(uri: String): Boolean = uri.startsWith(EdgePrefix) || uri.startsWith(GroupPrefix)

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
