package versola.oauth.conversation.model

import versola.util.UUIDv7
import zio.json.JsonCodec

type AuthId = AuthId.Type

object AuthId extends UUIDv7:
  given JsonCodec[AuthId] =
    JsonCodec.string.transformOrFail(parse(_).left.map(_.getMessage), _.toString)
