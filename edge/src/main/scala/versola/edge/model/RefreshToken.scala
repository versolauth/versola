package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type RefreshToken = RefreshToken.Type

object RefreshToken extends StringNewType:
  given JsonEncoder[RefreshToken] = JsonEncoder.string.contramap(identity[String])
  given JsonDecoder[RefreshToken] = JsonDecoder.string.map(RefreshToken(_))
