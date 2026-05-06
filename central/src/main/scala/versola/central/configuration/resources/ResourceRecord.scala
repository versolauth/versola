package versola.central.configuration.resources

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{AclRuleTree, ResourceUri}

case class ResourceRecord(
    tenantId: TenantId,
    id: ResourceId,
    resource: ResourceUri,
    endpoints: Vector[ResourceEndpointRecord]
)

case class ResourceEndpointRecord(
    id: ResourceEndpointId,
    path: String,
    method: String,
    fetchUserInfo: Boolean,
    allowRules: AclRuleTree,
    denyRules: AclRuleTree,
    injectHeaders: Map[String, String],
) derives CanEqual