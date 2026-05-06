package versola.central.configuration.scopes

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

case class ScopeRecord(
    tenantId: TenantId,
    id: ScopeToken,
    description: Map[String, String],
    claims: Vector[ClaimRecord],
) derives CanEqual, Equal
