package versola.audit

import versola.oauth.session.model.UserAgentDetails
import versola.util.audit.{AuditContext, AuditDraft, AuditEvents}
import versola.util.http.Observability
import versola.util.http.Observability.AuthDetails
import zio.*
import zio.http.Request
import zio.logging.logContext

/** What the call sites of the audit log share. */
object AuditSupport:

  extension (events: AuditEvents)
    /** Queues the event of what a request did, once it did it: it does not fail the request, and a tenant that switched its audit log off
      * has none (see [[versola.util.audit.AuditTenants]]). */
    def log(draft: AuditDraft): UIO[Unit] =
      events.recordBestEffort(draft)

  /** The short parsed form of the request's `User-Agent` (`browser version / os`, or `other` for one that names nothing) as the user
    * agent the events of this request store: the raw header is attacker-chosen and up to 8 KB, and the log keeps the short form. */
  def noteUserAgent(request: Request): UIO[Unit] =
    ZIO.foreachDiscard(request.headers.get("User-Agent"))(header =>
      AuditContext.setUserAgent(UserAgentDetails.parse(header).summary.getOrElse("other")),
    )

  /** The `auth` log context of the request being handled: the client, user, session and the refresh-token prefixes the call sites
    * below have already told `Observability`. */
  val currentAuth: UIO[AuthDetails] =
    logContext.get.map(_.get(Observability.auth).getOrElse(AuthDetails.empty))
