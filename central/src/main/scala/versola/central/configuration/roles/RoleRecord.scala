package versola.central.configuration.roles

import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.*
import zio.prelude.Equal

case class RoleRecord(
    id: RoleId,
    tenantId: TenantId,
    description: Map[String, String],
    permissions: Set[Permission],
    active: Boolean,
) derives Schema, JsonCodec, CanEqual, Equal
