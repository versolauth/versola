package versola.central.configuration.tenants

import versola.central.configuration.edges.EdgeId
import zio.schema.{DeriveSchema, Schema}

case class TenantRecord(
    id: TenantId,
    description: String,
    edgeId: Option[EdgeId],
)

object TenantRecord:
  given Schema[TenantRecord] = DeriveSchema.gen

