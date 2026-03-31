package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type ScopeDescription = ScopeDescription.Type

object ScopeDescription extends StringNewType:
  given JsonDecoder[ScopeDescription] = JsonDecoder.string.map(ScopeDescription(_))
  given JsonEncoder[ScopeDescription] = JsonEncoder.string.contramap(identity[String])
