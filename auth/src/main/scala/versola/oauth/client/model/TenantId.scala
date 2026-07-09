package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.JsonCodec

type TenantId = TenantId.Type

object TenantId extends StringNewType:
  /** Default tenant carrying central admin roles. */
  val default: TenantId = apply("default")

  given JsonCodec[TenantId] = JsonCodec.string.transform(TenantId(_), identity[String])
