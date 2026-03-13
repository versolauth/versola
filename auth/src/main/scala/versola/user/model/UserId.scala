package versola.user.model

import versola.util.UUIDv7
import zio.json.JsonCodec

import java.util.UUID

type UserId = UserId.Type

object UserId extends UUIDv7:
  given JsonCodec[UserId] = JsonCodec.string.transform(
    s => UserId(UUID.fromString(s)),
    uuid => uuid.toString
  )