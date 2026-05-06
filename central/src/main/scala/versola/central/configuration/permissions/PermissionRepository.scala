package versola.central.configuration.permissions

import versola.central.configuration.PatchDescription
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task
trait PermissionRepository
  extends CacheSource[Vector[PermissionRecord]]:

  def getAll: Task[Vector[PermissionRecord]]

  def findPermission(
      tenantId: Option[TenantId],
      permission: Permission,
  ): Task[Option[PermissionRecord]]

  def createPermission(
      tenantId: Option[TenantId],
      permission: Permission,
      description: Map[String, String],
      endpointIds: Set[ResourceEndpointId],
  ): Task[Unit]

  def updatePermission(
      tenantId: Option[TenantId],
      permission: Permission,
      descriptionPatch: PatchDescription,
      endpointIds: Option[Set[ResourceEndpointId]],
  ): Task[Unit]

  def deletePermission(
      tenantId: Option[TenantId],
      permission: Permission,
  ): Task[Unit]
