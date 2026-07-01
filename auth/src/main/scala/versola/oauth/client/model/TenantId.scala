package versola.oauth.client.model

import versola.util.StringNewType
import zio.json.JsonCodec

type TenantId = TenantId.Type

object TenantId extends StringNewType:
  /** Sentinel tenant used for system-wide (super-admin) roles. */
  val global: TenantId = apply("*")

  given JsonCodec[TenantId] = JsonCodec.string.transform(TenantId(_), identity[String])
