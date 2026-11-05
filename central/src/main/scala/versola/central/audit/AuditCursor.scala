package versola.central.audit

import versola.util.Base64Url
import zio.json.*
import zio.schema.Schema

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Where the previous page of a report ended, as the client hands it back: the key `(at, id)` of the last event it held, in the order of
  * both logs (newest first, an id by its bytes). The next page is what is strictly older. `centralOnly` says an earlier page was served
  * without auth, and the rest of the walk goes on the same way. It travels as one URL-safe string, and a string that is not one of
  * these is a bad request before the service reads anything. */
case class AuditCursor(at: Instant, id: UUID, centralOnly: Boolean) derives JsonCodec

object AuditCursor:
  def encode(cursor: AuditCursor): String = Base64Url.encode(cursor.toJson.getBytes("UTF-8")).toString

  def decode(raw: String): Either[String, AuditCursor] =
    Try(Base64Url.decodeStr(raw)).toEither.left.map(_ => "the cursor is not valid").flatMap(
      _.fromJson[AuditCursor].left.map(_ => "the cursor is not valid"),
    )

  given Schema[AuditCursor] = Schema.primitive[String].transformOrFail(decode, cursor => Right(encode(cursor)))
