package versola.util.audit

import zio.json.JsonEncoder

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import scala.annotation.tailrec

/** The flow a `cert.*` event is about: one name for each flow, the same in `central` and `auth`, so that a filter on `operation` finds
  * the attempts that were refused with the ones that succeeded. A closed set, written as `name`.
  *
  *  - `enroll`: the first certificate, for a one-time token (an edge's, a client's through EST)
  *  - `renew`: a new certificate for the one in hand; in `auth` this is the EST re-enrolment (`simplereenroll`)
  *  - `sign`: an edge obtained a certificate for a client whose key it generated
  *  - `issue`: central generated the key and issued the certificate when the client was registered
  *  - `revoke_renewal`: the right of a client's certificates to renew was ended (a chain closed as forked); the certificate itself
  *    keeps authenticating until it expires */
enum CertOperation(val name: String):
  case Enroll extends CertOperation("enroll")
  case Renew extends CertOperation("renew")
  case Sign extends CertOperation("sign")
  case Issue extends CertOperation("issue")
  case RevokeRenewal extends CertOperation("revoke_renewal")

object CertOperation:
  given JsonEncoder[CertOperation] = JsonEncoder[String].contramap(_.name)

/** The details of a `cert.*` audit event, the same in `central` (which issues and records) and `auth` (which records the EST
  * attempts it refuses): which certificate the event is about, so that "what was issued to this client, and until when" can be read
  * from the log.
  *
  * Everything here is public: the serial, the end of the validity and the subject name are in the certificate itself. Nothing of the
  * private key, no PEM and no request body can be put in, since the fields are these strings and a `Secret` has no encoder. `Option`
  * fields are left out of the JSON when empty.
  *
  * @param operation which flow recorded it, see [[CertOperation]]
  * @param clientId  the client the certificate is of, `None` for an edge's own
  * @param serial    the serial of the certificate issued, in hex as the stores keep it; `None` where none was issued
  * @param notAfter  the end of its validity, an ISO-8601 instant; `None` where none was issued
  * @param subject   the subject name, RFC 4514 as `X500Principal.getName(RFC2253)` writes it: the issued certificate's, or the
  *                  one a refused request asked for */
case class CertAuditDetails(
    operation: CertOperation,
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

  /** The event of the certificate that was issued (or, for `revoke_renewal`, the one that was presented when the chain was closed:
    * what ends is its right to renew, not the certificate). */
  def of(operation: CertOperation, clientId: Option[String], certificate: X509Certificate): CertAuditDetails =
    CertAuditDetails(
      operation = operation,
      clientId = clientId,
      serial = Some(certificate.getSerialNumber.toString(16)),
      notAfter = Some(certificate.getNotAfter.toInstant.toString),
      subject = Some(subjectName(certificate.getSubjectX500Principal)),
    )

  /** The event of an attempt that issued nothing: only the subject the request asked for, if it could be read. */
  def refused(operation: CertOperation, clientId: Option[String], subject: Option[X500Principal]): CertAuditDetails =
    CertAuditDetails(
      operation = operation,
      clientId = clientId,
      serial = None,
      notAfter = None,
      subject = subject.map(subjectName),
    )

  /** The subject, cut to [[MaxSubject]] characters at the end of a whole piece: not inside an escape (`\,`, or `\C3` of the two-digit
    * form) and not between the halves of a surrogate pair, either of which would leave a name that does not read. */
  private def subjectName(principal: X500Principal): String = wholeEnd(principal.getName(X500Principal.RFC2253))

  /** [[subjectName]]'s cut, on the written name. */
  private[audit] def wholeEnd(name: String): String =
    @tailrec def end(at: Int): Int =
      if at >= name.length then at
      else
        val width =
          if name.charAt(at) == '\\' then
            if at + 2 < name.length && isHex(name.charAt(at + 1)) && isHex(name.charAt(at + 2)) then 3 else 2
          else if Character.isHighSurrogate(name.charAt(at)) && at + 1 < name.length && Character.isLowSurrogate(name.charAt(at + 1)) then 2
          else 1
        if at + width > MaxSubject then at else end(at + width)
    name.substring(0, end(0))

  private def isHex(c: Char): Boolean = Character.digit(c, 16) >= 0
