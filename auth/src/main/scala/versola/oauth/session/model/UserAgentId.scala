package versola.oauth.session.model

import versola.util.UUIDv7
import zio.json.JsonCodec

import java.util.UUID

type UserAgentId = UserAgentId.Type

object UserAgentId extends UUIDv7:
  given JsonCodec[UserAgentId] = JsonCodec.string.transform(
    s => UserAgentId(UUID.fromString(s)),
    uuid => uuid.toString,
  )