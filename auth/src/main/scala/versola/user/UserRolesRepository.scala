package versola.user

import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserId
import zio.Task

trait UserRolesRepository:
  def findRolesByUser(userId: UserId): Task[List[RoleId]]

  def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]]

  def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]
