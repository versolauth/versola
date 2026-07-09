package versola.user

import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserId
import zio.Task

trait UserRolesRepository:
  def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]]

  def findRolesByUser(userId: UserId): Task[Map[TenantId, List[RoleId]]]

  def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]
