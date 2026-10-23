package versola.user

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyName}
import versola.oauth.challenge.password.model.DeliveryChannel
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.{Login, UserId}
import versola.util.{Email, Patch, Phone}
import zio.json.ast.Json
import zio.json.{JsonCodec, jsonExplicitNull}
import zio.schema.{Schema, derived}

import java.time.Instant
import java.util.UUID

given JsonCodec[Email] = JsonCodec.string.transform(Email(_), identity)
given JsonCodec[Phone] = JsonCodec.string.transform(Phone(_), identity)
given JsonCodec[Login] = JsonCodec.string.transform(Login(_), identity)
given JsonCodec[RoleId] = JsonCodec.string.transform(RoleId(_), identity)

case class UpsertUserPayload(
    id: UserId,
    version: UUID,
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
) derives JsonCodec, Schema

case class UpdateUserRolesPayload(
    userId: UserId,
    tenantId: TenantId,
    add: Set[RoleId],
    remove: Set[RoleId],
) derives JsonCodec, Schema

case class ResetUserLimitsPayload(
    userId: UserId,
    tenantId: TenantId,
    email: Option[Email],
    phone: Option[Phone],
) derives JsonCodec, Schema

case class UserClaimsResponse(claims: Json.Obj) derives JsonCodec, Schema

case class PatchUserClaimsPayload(id: UserId, claims: Json.Obj) derives JsonCodec, Schema

case class UserRolesResponse(roles: List[RoleId]) derives JsonCodec, Schema

given JsonCodec[CredentialDeviceType] =
  JsonCodec.string.transform(CredentialDeviceType.valueOf, _.toString)
given JsonCodec[AuthenticatorTransport] =
  JsonCodec.string.transform(AuthenticatorTransport.valueOf, _.toString)

case class PasskeyInfoResponse(
    id: CredentialId,
    name: Option[PasskeyName],
    deviceType: CredentialDeviceType,
    transports: List[AuthenticatorTransport],
    backedUp: Boolean,
    backupEligible: Boolean,
    lastUsedAt: Option[Instant],
    createdAt: Instant,
) derives JsonCodec

case class ListPasskeysResponse(passkeys: List[PasskeyInfoResponse]) derives JsonCodec

case class RenamePasskeyPayload(
    userId: UserId,
    credentialId: CredentialId,
    name: Option[PasskeyName],
) derives JsonCodec

case class ResetPasswordPayload(
    userId: UserId,
    expiresInSeconds: Option[Long],
    channel: Option[DeliveryChannel],
) derives JsonCodec

/** Returned only for [[DeliveryChannel.show]] resets, which are non-prod only. */
case class ResetPasswordResponse(
    password: String,
) derives JsonCodec

case class SetPasswordPayload(
    userId: UserId,
    password: String,
) derives JsonCodec

case class ClientEntryResponse(
    clientId: String,
    enteredAt: Instant,
) derives JsonCodec

case class SessionResponse(
    /** Not rendered to end users, but forwarded so internal callers (e.g. central)
     *  can reference or invalidate this specific session. */
    publicId: String,
    clients: List[ClientEntryResponse],
    platform: Option[String],
    os: Option[String],
    browser: Option[String],
    version: Option[String],
    createdAt: Instant,
    expiresAt: Instant,
    /** `None` when the session has no idle timeout. */
    idleExpiresAt: Option[Instant],
    userAgent: Option[UserAgentResponse],
) derives JsonCodec

case class SessionListResponse(sessions: List[SessionResponse]) derives JsonCodec

/** The `user_agents` row: what auth stores about the device, no more. */
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
) derives JsonCodec

case class DeviceResponse(
    userAgent: Option[UserAgentResponse],
    @jsonExplicitNull session: Option[SessionResponse],
    /** Public ids of the sessions of this user agent that ended and are still stored, so a refresh
     *  token (which names only its session) can be tied back to the device. Empty while signed in. */
    endedSessionIds: List[String],
) derives JsonCodec

case class DeviceListResponse(devices: List[DeviceResponse]) derives JsonCodec

case class RefreshTokenResponse(
    /** The session this token was issued under. May no longer resolve to a live session:
     *  a refresh token's expiry is fixed at issuance and independent of a session's,
     *  so a token routinely outlives it. */
    sessionId: String,
    clientId: String,
    scope: List[String],
    issuedAt: Instant,
    expiresAt: Instant,
    /** RFC 9449: true once this grant is bound to a DPoP key and can no longer be
     *  refreshed by anyone else who obtains a copy of it. */
    dpopBound: Boolean,
) derives JsonCodec

case class RefreshTokenListResponse(refreshTokens: List[RefreshTokenResponse]) derives JsonCodec
