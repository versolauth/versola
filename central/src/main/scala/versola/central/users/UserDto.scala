package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.{Schema, derived}

case class CreateUserRequest(
    email: Option[Email],
    phone: Option[Phone],
    login: Option[Login],
    claims: Json.Obj,
) derives JsonCodec, Schema

case class CreateUserResponse(id: UserId) derives JsonCodec, Schema

case class PatchUserRequest(
    id: UserId,
    email: Option[Patch[Email]],
    phone: Option[Patch[Phone]],
    login: Option[Patch[Login]],
    claims: Option[Json.Obj],
) derives JsonCodec, Schema

case class UserRoleRequest(
    userId: UserId,
    tenantId: TenantId,
    roleId: RoleId,
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
