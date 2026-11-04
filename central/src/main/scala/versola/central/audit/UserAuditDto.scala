package versola.central.audit

import versola.util.audit.AuditEvent
import zio.json.JsonCodec
import zio.json.ast.Json

import java.time.Instant

/** What auth's `GET /audit/users/{userId}/events` answers: a page of the user's events, newest first. */
case class UserAuditEventsResponse(
    events: List[AuditEvent],
    nextCursor: Option[String],
    retentionFrom: Instant,
) derives JsonCodec

/** The query of that endpoint; a bound left out is the whole retained window. */
case class UserAuditEventsQuery(
    from: Option[Instant],
    to: Option[Instant],
    cursor: Option[String],
    limit: Int,
)
