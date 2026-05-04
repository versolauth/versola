package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

type Claim = Claim.Type

object Claim extends StringNewType:
  given JsonDecoder[Claim] = JsonDecoder.string.map(Claim(_))
  given JsonEncoder[Claim] = JsonEncoder.string.contramap(identity[String])
