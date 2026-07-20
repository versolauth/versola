package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type Acr = Acr.Type

object Acr extends StringNewType:
  given JsonDecoder[Acr] = JsonDecoder.string.map(Acr(_))
  given JsonEncoder[Acr] = JsonEncoder.string.contramap(identity[String])
