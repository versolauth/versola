package versola.central.configuration.tenants

import versola.central.configuration.edges.EdgeGroupId
import zio.schema.{DeriveSchema, Schema}

case class TenantRecord(
    id: TenantId,
    description: String,
    /** The group of edges that serves the tenant (#557): every edge of it reads the tenant's configuration. An operator's
      * own group only; `None` for a tenant no edge serves. */
    edgeGroupId: Option[EdgeGroupId],
)

object TenantRecord:
  given Schema[TenantRecord] = DeriveSchema.gen
