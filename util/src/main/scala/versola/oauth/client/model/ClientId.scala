package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.JsonCodec

type ClientId = ClientId.Type

object ClientId extends StringNewType:
  given JsonCodec[ClientId] = JsonCodec.string.transform(ClientId(_), identity[String])

