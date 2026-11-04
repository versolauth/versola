package versola.audit

import versola.util.audit.{AuditDraft, AuditEvents, AuditUnavailableException}
import versola.util.http.Observability
import versola.util.http.Observability.AuthDetails
import zio.*
import zio.logging.logContext

/** What the call sites of the audit log share. */
object AuditSupport:

  extension (events: AuditEvents)
    /** `record`, with the refusal as the failure the global handler answers with 503 `audit_unavailable`: the endpoint maps nothing by
      * hand. Under `overflow = drop` it cannot fail. */
    def log(draft: AuditDraft): IO[AuditUnavailableException.type, Unit] =
      events.record(draft).mapError(_ => AuditUnavailableException)

  /** The `auth` log context of the request being handled: the client, user, session and the refresh-token prefixes the call sites
    * below have already told `Observability`. */
  val currentAuth: UIO[AuthDetails] =
    logContext.get.map(_.get(Observability.auth).getOrElse(AuthDetails.empty))
