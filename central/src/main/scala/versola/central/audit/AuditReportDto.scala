package versola.central.audit

import versola.central.users.UserId
import versola.util.audit.AuditEvent
import zio.json.JsonCodec
import zio.json.ast.Json

import java.time.Instant

/** The report of one user (`GET /audit/reports/user`): what auth recorded about them (sign-ins, tokens, sessions, account
  * changes) and what administrators did to them, as one list, newest first.
  *
  * @param summary    auth's summary of the window as it answered it, with `adminActions` from central's own rows added; only on
  *                   the first page. Without auth's part (see `partial`) it holds `adminActions` alone.
  * @param nextCursor opaque; absent on the last page
  * @param partial    the services whose part is missing from the report: `["auth"]` when auth did not answer
  */
case class UserAuditReport(
    userId: UserId,
    window: ReportWindow,
    summary: Option[Json.Obj],
    events: List[AuditEvent],
    nextCursor: Option[String],
    partial: List[String],
    truncated: Boolean = false,
) derives JsonCodec

/** The time the report covers, and how far back the log reaches at all. */
case class ReportWindow(from: Instant, to: Instant, retentionFrom: Instant) derives JsonCodec

/** What an administrator did to the user, for the summary: one line per action, newest first. */
case class AdminAction(
    at: Instant,
    actorId: Option[String],
    kind: String,
    targetType: Option[String],
    targetId: Option[String],
    changed: List[String],
) derives JsonCodec

enum AuditReportError:
  /** The window, the page size or the cursor is not valid. */
  case Invalid(message: String)

  /** Too many reports are running: none of the places freed in time. */
  case Busy
