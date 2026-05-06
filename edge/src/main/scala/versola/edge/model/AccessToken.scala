package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type AccessToken = AccessToken.Type

object AccessToken extends StringNewType:
  given JsonEncoder[AccessToken] = JsonEncoder.string.contramap(identity[String])
  given JsonDecoder[AccessToken] = JsonDecoder.string.map(AccessToken(_))
