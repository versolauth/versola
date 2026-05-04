package versola.central.configuration.roles

import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchDescription, PatchPermissions}
import versola.util.CacheSource
import zio.Task

trait RoleRepository extends CacheSource[Vector[RoleRecord]]:

  def getAll: Task[Vector[RoleRecord]]

  def findRole(
      tenantId: TenantId,
      id: RoleId,
  ): Task[Option[RoleRecord]]

  def createRole(
      tenantId: TenantId,
      id: RoleId,
      description: Map[String, String],
      permissions: List[Permission],
  ): Task[Unit]

  def updateRole(
      tenantId: TenantId,
      id: RoleId,
      description: PatchDescription,
      permissions: PatchPermissions,
  ): Task[Unit]

  def markRoleInactive(
      tenantId: TenantId,
      id: RoleId,
  ): Task[Unit]

  def deleteRole(
      tenantId: TenantId,
      id: RoleId,
  ): Task[Unit]
