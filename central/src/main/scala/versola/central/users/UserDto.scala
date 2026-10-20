package versola.central.users

import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.ast.Json
import zio.json.{JsonCodec, jsonExplicitNull}
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
    expiresInSeconds: Long,
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
    /** When the session ends regardless of activity. */
    expiresAt: Instant,
    /** When the session ends if it stays idle; `None` when no idle timeout applies to it. */
    idleExpiresAt: Option[Instant],
    /** The user agent the session runs under; `None` only if its row has expired and been swept. */
    userAgent: Option[UserAgentResponse],
) derives JsonCodec, Schema

/** The user agent (device or browser install) as auth stores it. */
case class UserAgentResponse(
    id: String,
    /** The raw `User-Agent` header as last seen on sign-in. */
    userAgent: Option[String],
    platform: Option[String],
    os: Option[String],
    browser: Option[String],
    version: Option[String],
    createdAt: Instant,
    /** The device is remembered until then; it slides forward on every sign-in. */
    expiresAt: Instant,
) derives JsonCodec, Schema

/** One remembered device: its user agent and the live session under it, `null` once signed out. */
case class DeviceResponse(
    userAgent: Option[UserAgentResponse],
    @jsonExplicitNull session: Option[SessionResponse],
    /** Public ids of the sessions of this user agent that ended and are still stored. A refresh
     *  token names only its session, so these tie a token that outlived its session to the device. */
    endedSessionIds: List[String],
) derives JsonCodec, Schema

case class RefreshTokenResponse(
    /** The session this token was issued under. May no longer resolve to a live session:
     *  a refresh token's expiry is fixed at issuance and independent of a session's,
     *  so a token routinely outlives it. */
    sessionId: String,
    clientId: ClientId,
    scope: List[String],
    issuedAt: Instant,
    expiresAt: Instant,
    /** RFC 9449: true once this grant is bound to a DPoP key and can no longer be
     *  refreshed by anyone else who obtains a copy of it. */
    dpopBound: Boolean,
) derives JsonCodec, Schema
