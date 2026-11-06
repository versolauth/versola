package versola.central.users

import versola.util.UUIDv7
import zio.json.JsonCodec

import java.util.UUID
import scala.util.Try

type UserId = UserId.Type

object UserId extends UUIDv7:
  /** A value that is not a UUID is a decoding error (a bad request), not an exception. */
  given JsonCodec[UserId] = JsonCodec.string.transformOrFail(
    s => Try(UserId(UUID.fromString(s))).toEither.left.map(_ => "not a UUID"),
    uuid => uuid.toString,
  )
