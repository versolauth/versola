package versola.util.audit

import versola.util.http.IpAddress
import zio.json.*
import zio.json.ast.Json

/** What the table can hold of an event whatever the caller sent, and what an event may weigh. An event is made of the service's own
  * values and of what a request carried (a user agent, an address, a client or a user's name), and one row the table refuses fails
  * the batch it is in, while the queue holds events by count: so the event is made storable and bounded where it is made, and the
  * writer only has the database's own refusals left to handle.
  *
  * A text column and a JSON string refuse U+0000; `ip` is an address or nothing; every text is cut to [[MaxText]] (a user agent to
  * [[MaxUserAgent]]) and the details to [[MaxDetails]] serialized characters, past which they are replaced by a marker. What was
  * changed is returned by field, from the closed set of [[Field]], so that the caller can count it and mark the event. The rest of
  * what the caller sent is kept as it is. */
private[audit] object AuditSanitizer:

  /** The longest user agent an event keeps, in UTF-16 units: browsers write a few hundred characters. */
  val MaxUserAgent = 512

  /** The longest any other text of an event keeps. */
  val MaxText = 256

  /** The longest the details of an event are, serialized. */
  val MaxDetails = 8192

  /** The names of what was changed. */
  object Field:
    val Text = "text"
    val Ip = "ip"
    val UserAgent = "user_agent"
    val Details = "details"

  def apply(event: AuditEvent): AuditEvent = run(event)._1

  /** The event made storable and bounded, and the fields that took a change, in a fixed order. */
  def run(event: AuditEvent): (AuditEvent, List[String]) =
    def short(value: String): String = truncated(text(value), MaxText)
    val cleaned = event.copy(
      tenantId = event.tenantId.map(short),
      nodeId = short(event.nodeId),
      kind = AuditKind(short(event.kind.value)),
      reason = event.reason.map(short),
      actorId = event.actorId.map(short),
      clientId = event.clientId.map(short),
      sessionId = event.sessionId.map(short),
      ip = event.ip.map(text).filter(storableAddress),
      userAgent = event.userAgent.map(text).map(truncated(_, MaxUserAgent)),
      traceId = event.traceId.map(short),
      targetType = event.targetType.map(short),
      targetId = event.targetId.map(short),
      details = bounded(object_(event.details)),
    )
    val texts = (e: AuditEvent) =>
      List(e.tenantId, Some(e.nodeId), Some(e.kind.value), e.reason, e.actorId, e.clientId, e.sessionId, e.traceId, e.targetType, e.targetId)
    val fields = List(
      Field.Text -> (texts(cleaned) != texts(event)),
      Field.Ip -> (cleaned.ip != event.ip),
      Field.UserAgent -> (cleaned.userAgent != event.userAgent),
      Field.Details -> (cleaned.details != event.details),
    ).collect { case (field, true) => field }
    (cleaned, fields)

  private val Octet = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
  private val DottedQuad = s"$Octet(\\.$Octet){3}".r

  /** What the column takes of an address. [[IpAddress.normalize]] is the rate limiter's notion of one and is more lenient than the
    * database's (Java reads `01.2.3.4` and `::ffff:01.2.3.4` where `inet` refuses a leading zero), so a literal is also required to be
    * ASCII and to carry any dotted part in its canonical form. */
  private def storableAddress(literal: String): Boolean =
    IpAddress.normalize(literal).isDefined && literal.forall(_ < 128) && {
      if !literal.contains('.') then true
      else DottedQuad.matches(literal.substring(literal.lastIndexOf(':') + 1))
    }

  private def text(value: String): String = if value.indexOf('\u0000') < 0 then value else value.replace("\u0000", "")

  /** At most `max` UTF-16 units, not ending in the first half of a surrogate pair, which the column could not hold either. */
  private def truncated(value: String, max: Int): String =
    if value.length <= max then value
    else if Character.isHighSurrogate(value.charAt(max - 1)) then value.substring(0, max - 1)
    else value.substring(0, max)

  private def bounded(details: Json.Obj): Json.Obj =
    if details.toJson.length <= MaxDetails then details else Json.Obj("truncated" -> Json.Bool(true))

  private def object_(value: Json.Obj): Json.Obj = Json.Obj(value.fields.map((key, member) => text(key) -> json(member)))

  private def json(value: Json): Json =
    value match
      case Json.Str(string) => Json.Str(text(string))
      case Json.Arr(items) => Json.Arr(items.map(json))
      case obj: Json.Obj => object_(obj)
      case other => other
