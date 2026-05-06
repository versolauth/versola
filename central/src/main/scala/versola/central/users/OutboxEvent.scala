package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.JsonCodec
import zio.json.ast.Json

/** Events queued in `user_outbox` and dispatched to auth by [[UserOutboxProcessor]]. */
enum OutboxEvent(val eventType: String) derives JsonCodec:
  case CreateUser(
      id: UserId,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
      claims: Json.Obj,
  ) extends OutboxEvent("CreateUser")
  case PatchUser(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
      claims: Option[Json.Obj],
  ) extends OutboxEvent("PatchUser")
  case AssignRole(userId: UserId, tenantId: TenantId, roleId: RoleId) extends OutboxEvent("AssignRole")
  case RemoveRole(userId: UserId, tenantId: TenantId, roleId: RoleId) extends OutboxEvent("RemoveRole")
