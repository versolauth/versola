package versola.central.users

import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.{Schema, derived}

import java.time.Instant

case class CreateUserRequest(
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec, Schema

case class CreateUserResponse(id: UserId) derives JsonCodec, Schema

/** An account auth created through self-service registration. */
case class RegisteredUserRequest(
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec, Schema

case class RegisteredUserResponse(userId: UserId) derives JsonCodec, Schema

case class PatchUserRequest(
    id: UserId,
    email: Option[Patch[Email]],
    phone: Option[Patch[Phone]],
    login: Option[Patch[Login]],
) derives JsonCodec, Schema

case class PatchUserClaimsRequest(
    id: UserId,
    claims: Json.Obj,
) derives JsonCodec, Schema

case class UpdateUserRolesRequest(
    userId: UserId,
    tenantId: TenantId,
    add: Set[RoleId],
    remove: Set[RoleId],
) derives JsonCodec, Schema

case class ResetUserLimitsRequest(
    userId: UserId,
    tenantId: TenantId,
    email: Option[Email],
    phone: Option[Phone],
) derives JsonCodec, Schema

case class UserSearchRecord(
    id: UserId,
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
    claims: Json.Obj,
) derives JsonCodec, Schema

case class UserSearchResponse(users: Vector[UserSearchRecord]) derives JsonCodec, Schema

case class UserRolesResponse(roles: List[RoleId]) derives JsonCodec, Schema

case class PasskeyInfo(
    id: String,
    name: Option[String],
    deviceType: String,
    transports: List[String],
    backedUp: Boolean,
    backupEligible: Boolean,
    lastUsedAt: Option[String],
    createdAt: String,
) derives JsonCodec, Schema

case class ListPasskeysResponse(passkeys: List[PasskeyInfo]) derives JsonCodec, Schema

case class RenamePasskeyRequest(
    userId: UserId,
    credentialId: String,
    name: Option[String],
) derives JsonCodec, Schema

/** Channel used to deliver an admin-issued temporary password to the user.
  * `show` returns the plaintext to the calling admin instead of delivering it,
  * and is rejected in production.
  */
enum DeliveryChannel derives JsonCodec, Schema:
  case email, sms, show

case class ResetPasswordRequest(
    userId: UserId,
    expiresInSeconds: Option[Long],
    channel: Option[DeliveryChannel],
) derives JsonCodec, Schema

/** Returned only for [[DeliveryChannel.show]] resets, which are non-prod only. */
case class ResetPasswordResponse(
    password: String,
) derives JsonCodec, Schema

case class SetPasswordRequest(
    userId: UserId,
    password: String,
) derives JsonCodec, Schema

/** Client entry within a session, enriched with the computed expiration (derived from the
  * client's access token TTL fetched from [[versola.central.configuration.clients.OAuthClientService]]).
  */
case class ClientSessionEntry(
    clientId: ClientId,
    enteredAt: Instant,
    expiresAt: Instant,
) derives JsonCodec, Schema

case class SessionResponse(
    /** Not rendered to end users, but kept available for internal use (e.g. a future
     *  per-session invalidation call). */
    publicId: String,
    clients: List[ClientSessionEntry],
    platform: Option[String],
    os: Option[String],
    browser: Option[String],
    version: Option[String],
    createdAt: String,
) derives JsonCodec, Schema
