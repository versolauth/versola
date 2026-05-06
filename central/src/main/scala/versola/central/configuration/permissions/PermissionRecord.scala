package versola.central.configuration.permissions

import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.tenants.TenantId
import zio.prelude.Equal

case class PermissionRecord(
    tenantId: Option[TenantId],
    id: Permission,
    description: Map[String, String],
    endpointIds: Set[ResourceEndpointId],
) derives CanEqual, Equal