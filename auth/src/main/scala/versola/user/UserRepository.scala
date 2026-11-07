package versola.user

import versola.oauth.client.model.TenantId
import versola.oauth.revocation.RevocationTerms
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.{Email, Patch, Phone}
import zio.Task
import zio.json.ast.Json

import java.util.UUID

trait UserRepository:
  /** Atomically resolves or creates a registration account and grants its tenant roles. Central
    * has already claimed the credential and returned the canonical `userId` before this is called.
    *
    * The credential is always one a confirmed OTP was just sent to, so the account comes back with
    * its `email_verified` / `phone_number_verified` claim already set, in the same statement.
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

  /** Adds and removes roles of the user in one tenant, in one transaction.
    *
    * When it takes at least one role away, `sub:<user>` is recorded in the same transaction (see
    * [[versola.oauth.revocation.PostgresAuthRevocationRepository.upsertUser]]), so a token issued with the role
    * is refused from the moment the removal is visible. "Takes away" means a row was there to delete: removing
    * a role the user does not hold, or one the same call adds back, records nothing, and adding revokes
    * nothing. Refresh tokens are not touched: refreshing is how the user gets a token with the roles they hold now.
    *
    * The row names the user, not the tenant: sessions and tokens of auth are not tenant-scoped, and the token of
    * another tenant's client carries no role of this one but is refused all the same (documented). */
  def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
      revocation: RevocationTerms,
  ): Task[Unit]

  /** Marks the given credential as verified for this user by setting its `email_verified` /
    * `phone_number_verified` claim in `claims`, and returns the user's claims after the update.
    * Only fires when OTP verification actually succeeded – never on failure or expiry.
    * Idempotent: an already-true claim is rewritten with the same value.
    * Guards against contact replacement: the UPDATE also checks that the stored value
    * still matches the credential the OTP was sent to, so a concurrent upsert that
    * swapped in a different email/phone cannot accidentally get verified; `None` then.
    */
  def markVerified(userId: UserId, credential: Either[Email, Phone]): Task[Option[Json.Obj]]
