package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import java.util.UUID
import zio.json.JsonCodec

/** Events queued in `user_outbox` and dispatched to auth by [[UserOutboxProcessor]]. */
enum OutboxEvent(val eventType: String) derives JsonCodec:
  case UpsertUser(
      userId: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ) extends OutboxEvent("UpsertUser")

  case UpdateUserRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ) extends OutboxEvent("UpdateUserRoles")

