package versola.oauth.user

import versola.audit.{AuditProbe, AuthAuditKinds}
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.UserRepository
import versola.user.model.*
import versola.util.{Email, Phone}
import zio.Task
import zio.json.ast.Json

// As the specs that are about something else than the audit call the repository: each change is handed a fresh event.
extension (repository: UserRepository)
  def register(userId: UserId, credential: Either[Email, Phone], tenantId: TenantId, roleIds: Set[RoleId]): Task[UserRecord] =
    AuditProbe.event(AuthAuditKinds.AccountRegistered, Some(userId)).flatMap(repository.register(userId, credential, tenantId, roleIds, _))

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountClaimsUpdated, Some(id)).flatMap(repository.patchClaims(id, patch, _))

  def delete(id: UserId): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountDeleted, Some(id)).flatMap(repository.delete(id, _))

  def updateRoles(userId: UserId, tenantId: TenantId, add: Set[RoleId], remove: Set[RoleId]): Task[Unit] =
    AuditProbe.event(AuthAuditKinds.AccountRolesUpdated, Some(userId)).flatMap(repository.updateRoles(userId, tenantId, add, remove, _))
