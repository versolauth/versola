package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type PermissionId = PermissionId.Type

object PermissionId extends StringNewType:
  given JsonDecoder[PermissionId] = JsonDecoder.string.map(PermissionId(_))
  given JsonEncoder[PermissionId] = JsonEncoder.string.contramap(identity[String])
