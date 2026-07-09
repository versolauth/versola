package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder, JsonFieldDecoder, JsonFieldEncoder}

type TenantId = TenantId.Type

object TenantId extends StringNewType:
  /** Default tenant carrying central admin roles. */
  val default: TenantId = apply("default")

  given JsonDecoder[TenantId] = JsonDecoder.string.map(TenantId(_))
  given JsonEncoder[TenantId] = JsonEncoder.string.contramap(identity[String])
  given JsonFieldDecoder[TenantId] = JsonFieldDecoder.string.map(TenantId(_))
  given JsonFieldEncoder[TenantId] = JsonFieldEncoder.string.contramap(identity[String])
