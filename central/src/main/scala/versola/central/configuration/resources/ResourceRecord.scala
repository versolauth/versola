package versola.central.configuration.resources

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{InjectRule, ResourceUri}

case class ResourceRecord(
    tenantId: TenantId,
    resourceId: ResourceId,
    resource: ResourceUri,
    endpoints: Vector[ResourceEndpointRecord]
)

case class ResourceEndpointRecord(
    id: ResourceEndpointId,
    path: String,
    method: String,
    fetchUserInfo: Boolean,
    allowExpression: Option[String],
    inject: Vector[InjectRule],
) derives CanEqual