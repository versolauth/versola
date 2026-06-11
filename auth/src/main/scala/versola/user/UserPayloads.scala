package versola.user

import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.{Login, UserId}
import versola.util.{Email, Patch, Phone}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.{Schema, derived}

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

case class UserClaimsResponse(claims: Json.Obj) derives JsonCodec, Schema

case class PatchUserClaimsPayload(id: UserId, claims: Json.Obj) derives JsonCodec, Schema

case class UserRolesResponse(roles: List[RoleId]) derives JsonCodec, Schema
