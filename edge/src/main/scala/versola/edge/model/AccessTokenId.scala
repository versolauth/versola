package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type AccessTokenId = AccessTokenId.Type

object AccessTokenId extends StringNewType:
  given JsonDecoder[AccessTokenId] = JsonDecoder.string.map(AccessTokenId(_))
  given JsonEncoder[AccessTokenId] = JsonEncoder.string.contramap(identity[String])
