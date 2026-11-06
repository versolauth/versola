package versola.audit

import versola.util.Base64
import zio.schema.Schema

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Where a page of the report stopped: the time and id of the last event on it, which is the order of the pages. On the wire it is
  * opaque, `base64url(at|id)`; a value that is not one fails to decode, so a request carrying it is a bad request before anything reads it. */
case class AuditCursor(at: Instant, id: UUID):
  def render: String = Base64.urlEncode(s"$at|$id".getBytes(StandardCharsets.UTF_8))

object AuditCursor:
  def parse(value: String): Either[String, AuditCursor] =
    val malformed = Left("the cursor is not one this endpoint issued")
    Try(Base64.urlDecode(value)).toOption match
      case None => malformed
      case Some(bytes) =>
        String(bytes, StandardCharsets.UTF_8).split('|') match
          case Array(at, id) =>
            (for
              parsedAt <- Try(Instant.parse(at)).toOption
              parsedId <- Try(UUID.fromString(id)).toOption
            yield AuditCursor(parsedAt, parsedId)).toRight("the cursor is not one this endpoint issued")
          case _ => malformed

  given Schema[AuditCursor] = Schema.primitive[String].transformOrFail(parse, cursor => Right(cursor.render))
