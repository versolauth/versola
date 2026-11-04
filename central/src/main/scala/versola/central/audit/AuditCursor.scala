package versola.central.audit

import versola.util.Base64Url
import zio.json.*
import zio.schema.Schema

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Where the previous page of a report ended, as the client hands it back: `until` is where central's rows resume (exclusive);
  * `auth` is auth's own cursor, which central passes on as it came; `key` is the position in central's rows when they are paged
  * alone (`centralOnly`). It travels as one URL-safe string, and a string that is not one of these is a bad request before the
  * service reads anything. */
case class AuditCursor(until: Instant, auth: Option[String], key: Option[AuditCursor.Key], centralOnly: Boolean) derives JsonCodec

object AuditCursor:
  case class Key(at: Instant, id: UUID) derives JsonCodec

  def encode(cursor: AuditCursor): String = Base64Url.encode(cursor.toJson.getBytes("UTF-8")).toString

  def decode(raw: String): Either[String, AuditCursor] =
    Try(Base64Url.decodeStr(raw)).toEither.left.map(_ => "the cursor is not valid").flatMap(
      _.fromJson[AuditCursor].left.map(_ => "the cursor is not valid"),
    )

  given Schema[AuditCursor] = Schema.primitive[String].transformOrFail(decode, cursor => Right(encode(cursor)))
