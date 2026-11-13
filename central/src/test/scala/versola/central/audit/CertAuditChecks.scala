package versola.central.audit

import versola.util.audit.{AuditDraft, CertAuditDetails, CertOperation}
import zio.json.*
import zio.json.ast.Json

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.security.auth.x500.X500Principal

/** What the specs of the services that record `cert.*` events check of the details: which certificate they name, and that nothing of
  * a key or a PEM is in them. */
object CertAuditChecks:

  /** A member of the details of a recorded event, if it is a string. */
  def detail(draft: AuditDraft, name: String): Option[String] =
    draft.details.toJson.get(name).collect { case Json.Str(value) => value }

  /** The leaf of a PEM chain, leaf first. */
  def leafOf(chain: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
      .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
      .iterator.next.asInstanceOf[X509Certificate]

  /** The details are those [[CertAuditDetails.of]] writes for `leaf`: its serial in hex, the end of its validity, its subject. */
  def describe(draft: AuditDraft, operation: CertOperation, clientId: Option[String], leaf: X509Certificate): Boolean =
    detail(draft, "operation") == Some(operation.name) &&
      detail(draft, "clientId") == clientId &&
      detail(draft, "serial") == Some(leaf.getSerialNumber.toString(16)) &&
      detail(draft, "notAfter") == Some(leaf.getNotAfter.toInstant.toString) &&
      detail(draft, "subject") == Some(leaf.getSubjectX500Principal.getName(X500Principal.RFC2253))

  /** No private key, no PEM and no request in what is stored: not a label, and not any 40 characters of the base64 body of what must
    * not appear (a private key, a CSR, a certificate chain). Every window is checked, not the start: the first characters of most
    * certificates are the same ASN.1 header, so a leak of the middle or the end would pass a check of those alone. */
  def withoutPemMaterial(draft: AuditDraft, secrets: String*): Boolean =
    val stored = draft.details.toJson.toJson
    !stored.contains("PRIVATE KEY") && !stored.contains("BEGIN") && !stored.contains("CERTIFICATE REQUEST") &&
    secrets.forall(pem => leakedWindow(stored, pem).isEmpty && bodyOf(pem).nonEmpty)

  /** The base64 of a PEM, without its delimiters and line breaks. */
  def bodyOf(pem: String): String = pem.linesIterator.filterNot(_.startsWith("-----")).mkString

  /** The first 40-character window of the body of `pem` found in `text`, if any; a body shorter than 40 is one window. */
  def leakedWindow(text: String, pem: String): Option[String] =
    bodyOf(pem).sliding(40).find(text.contains)

  /** The members the details of a certificate event may have: nothing outside this set can carry a key. */
  val allowedMembers: Set[String] = Set("operation", "clientId", "serial", "notAfter", "subject")

  def onlyKnownMembers(draft: AuditDraft): Boolean = draft.details.toJson.fields.map(_._1).toSet.subsetOf(allowedMembers)
