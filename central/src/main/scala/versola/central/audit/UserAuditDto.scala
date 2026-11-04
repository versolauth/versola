package versola.central.audit

import versola.util.audit.AuditEvent
import zio.json.ast.Json
import zio.json.{DecoderOps, JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** An event of the report: the stored event, flat, and the `country` of its address when auth has one (it looks it up when it serves
  * the event; central loads no country database). Central's own rows have none, and an older auth sends none. */
case class ReportedEvent(event: AuditEvent, country: Option[String])

object ReportedEvent:
  given JsonCodec[ReportedEvent] = JsonCodec(
    JsonEncoder[Json.Obj].contramap: item =>
      val stored = JsonEncoder[AuditEvent].toJsonAST(item.event).toOption.flatMap(_.asObject).getOrElse(Json.Obj())
      item.country.fold(stored)(code => Json.Obj(stored.fields :+ ("country" -> Json.Str(code))))
    ,
    JsonDecoder[Json.Obj].mapOrFail: obj =>
      obj.as[AuditEvent].map(event => ReportedEvent(event, obj.get("country").flatMap(_.asString))),
  )

/** What auth's `GET /audit/events?userId=` answers: a page of the user's events, newest first. */
case class UserAuditEventsResponse(
    events: List[ReportedEvent],
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
