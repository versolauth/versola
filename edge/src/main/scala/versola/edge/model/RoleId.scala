package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type RoleId = RoleId.Type

object RoleId extends StringNewType:
  given JsonDecoder[RoleId] = JsonDecoder.string.map(RoleId(_))
  given JsonEncoder[RoleId] = JsonEncoder.string.contramap(identity[String])
