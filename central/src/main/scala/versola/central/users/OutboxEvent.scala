package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.JsonCodec

import java.util.UUID

/** Events queued in `user_outbox` and dispatched to auth by [[UserOutboxProcessor]]. */
enum OutboxEvent(val eventType: String) derives JsonCodec:
  case UpsertUser(
      userId: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ) extends OutboxEvent("UpsertUser")

  /** A change of some of a user's identifiers. Central keeps only their MACs (versolauth/versola#1041), so it can say
    * what changed and not what the rest is: an absent field is left as `auth` has it. */
  case PatchUser(
      userId: UserId,
      version: UUID,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  ) extends OutboxEvent("PatchUser")

  case UpdateUserRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ) extends OutboxEvent("UpdateUserRoles")

  case DeleteUser(
      userId: UserId,
  ) extends OutboxEvent("DeleteUser")
