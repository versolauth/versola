package versola.util.audit

import zio.json.JsonEncoder

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** The details of a `cert.*` audit event, the same in `central` (which issues and records) and `auth` (which records the EST
  * attempts it refuses): which certificate the event is about, so that "what was issued to this client, and until when" can be read
  * from the log.
  *
  * Everything here is public: the serial, the end of the validity and the subject name are in the certificate itself. Nothing of the
  * private key, no PEM and no request body can be put in, since the fields are these strings and a `Secret` has no encoder. `Option`
  * fields are left out of the JSON when empty.
  *
  * @param operation which flow recorded it: `enroll`, `renew`, `sign` (an edge obtained a certificate for a client), `issue` (central
  *                  issued it when the client was registered), `revoke`; auth records the EST names, `enroll` and `reenroll`
  * @param clientId  the client the certificate is of, `None` for an edge's own
  * @param serial    the serial of the certificate issued, in hex as the stores keep it; `None` where none was issued
  * @param notAfter  the end of its validity, an ISO-8601 instant; `None` where none was issued
  * @param subject   the subject name, RFC 4514 as `X500Principal.getName(RFC2253)` writes it: the issued certificate's, or the
  *                  one a refused request asked for */
case class CertAuditDetails(
    operation: String,
    clientId: Option[String],
    serial: Option[String],
    notAfter: Option[String],
    subject: Option[String],
) extends AuditDetails derives JsonEncoder:
  def toJson = AuditDetails.render(this)

object CertAuditDetails:

  /** A subject comes from a request nobody authenticated yet, in the refusals auth records: a name is short, so this only bounds the
    * event. */
  private val MaxSubject = 256

  /** The event of the certificate that was issued (or, for `revoke`, the one that was given up). */
  def of(operation: String, clientId: Option[String], certificate: X509Certificate): CertAuditDetails =
    CertAuditDetails(
      operation = operation,
      clientId = clientId,
      serial = Some(certificate.getSerialNumber.toString(16)),
      notAfter = Some(certificate.getNotAfter.toInstant.toString),
      subject = Some(subjectName(certificate.getSubjectX500Principal)),
    )

  /** The event of an attempt that issued nothing: only the subject the request asked for, if it could be read. */
  def refused(operation: String, clientId: Option[String], subject: Option[X500Principal]): CertAuditDetails =
    CertAuditDetails(
      operation = operation,
      clientId = clientId,
      serial = None,
      notAfter = None,
      subject = subject.map(subjectName),
    )

  private def subjectName(principal: X500Principal): String = principal.getName(X500Principal.RFC2253).take(MaxSubject)
