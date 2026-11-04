package versola.util.audit

import versola.util.http.IpAddress
import zio.json.ast.Json

/** What the table can hold of an event whatever the caller sent. An event is made of the service's own values and of what a request
  * carried (a user agent, an address, a client or a user's name), and one row the table refuses used to stop the log for everyone:
  * so the event is made storable where it is made, and the writer only has the database's own refusals left to handle.
  *
  * A text column and a JSON string refuse U+0000, `ip` is an address or nothing, and a user agent is as long as a client cares to
  * write it. The rest of what the caller sent is kept as it is.
  *
  * Applied once, where a draft becomes an event ([[AuditEvents.build]]), and again by `AuditInsert` before the row is written, since
  * what reaches a repository may not have come through `build`; it changes nothing that is already clean. */
object AuditSanitizer:

  /** The longest user agent an event keeps, in UTF-16 units: browsers write a few hundred characters, and a longer one is a client
    * that wants its text in the log. */
  val MaxUserAgent = 512

  def apply(event: AuditEvent): AuditEvent =
    event.copy(
      tenantId = event.tenantId.map(text),
      nodeId = text(event.nodeId),
      kind = AuditKind(text(event.kind.value)),
      reason = event.reason.map(text),
      actorId = event.actorId.map(text),
      clientId = event.clientId.map(text),
      sessionId = event.sessionId.map(text),
      ip = event.ip.map(text).filter(address => IpAddress.normalize(address).isDefined),
      userAgent = event.userAgent.map(text).map(truncated(_, MaxUserAgent)),
      traceId = event.traceId.map(text),
      targetType = event.targetType.map(text),
      targetId = event.targetId.map(text),
      details = object_(event.details),
    )

  private def text(value: String): String = if value.indexOf('\u0000') < 0 then value else value.replace("\u0000", "")

  /** At most `max` UTF-16 units, not ending in the first half of a surrogate pair, which the column could not hold either. */
  private def truncated(value: String, max: Int): String =
    if value.length <= max then value
    else if Character.isHighSurrogate(value.charAt(max - 1)) then value.substring(0, max - 1)
    else value.substring(0, max)

  private def object_(value: Json.Obj): Json.Obj = Json.Obj(value.fields.map((key, member) => text(key) -> json(member)))

  private def json(value: Json): Json =
    value match
      case Json.Str(string) => Json.Str(text(string))
      case Json.Arr(items) => Json.Arr(items.map(json))
      case obj: Json.Obj => object_(obj)
      case other => other
