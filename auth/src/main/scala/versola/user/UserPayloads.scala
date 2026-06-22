package versola.user

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, TenantId}
import versola.role.model.RoleId
import versola.user.model.{Login, UserId}
import versola.util.{Base64, Email, Patch, Phone}
import zio.json.JsonCodec
import zio.json.ast.Json
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

given JsonCodec[CredentialId] =
  JsonCodec.string.transformOrFail(s => CredentialId.fromBase64Url(s), id => Base64.urlEncode(id))
given JsonCodec[CredentialDeviceType] =
  JsonCodec.string.transform(CredentialDeviceType.valueOf, _.toString)
given JsonCodec[AuthenticatorTransport] =
  JsonCodec.string.transform(AuthenticatorTransport.valueOf, _.toString)

case class PasskeyInfoResponse(
    id: CredentialId,
    name: Option[String],
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
    name: Option[String],
) derives JsonCodec
