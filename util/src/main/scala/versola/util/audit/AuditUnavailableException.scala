package versola.util.audit

import scala.util.control.NoStackTrace

/** [[AuditUnavailable]] as a failure a request can carry out of a service: `Observability.handleErrors` answers it with 503 and
  * the error code `audit_unavailable`, so no endpoint maps it by hand. */
case object AuditUnavailableException extends RuntimeException("the audit log could not take the event in time"), NoStackTrace
