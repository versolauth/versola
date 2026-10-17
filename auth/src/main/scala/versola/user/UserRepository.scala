package versola.user

import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.{Email, Patch, Phone}
import zio.Task
import zio.json.ast.Json

import java.util.UUID

trait UserRepository:
  /** Atomically resolves or creates a registration account and grants its tenant roles. Central
    * has already claimed the credential and returned the canonical `userId` before this is called.
    */
  def register(
      userId: UserId,
      credential: Either[Email, Phone],
      tenantId: TenantId,
      roleIds: Set[RoleId],
  ): Task[UserRecord]

  def find(id: UserId): Task[Option[UserRecord]]

  def findByLogin(login: Login): Task[Option[UserRecord]]

  def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]]

  def upsert(
      id: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ): Task[Unit]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]

  /** Removes the account and every row keyed by it, in one transaction: sessions and their user
    * agents, refresh tokens, authorization codes, in-flight conversations, passkeys, passwords,
    * roles, consents, and the throttling records of the account's identifiers. A no-op for an
    * unknown id. */
  def delete(id: UserId): Task[Unit]

  def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]]

  def findRolesByUser(userId: UserId): Task[Map[TenantId, List[RoleId]]]

  def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit]
