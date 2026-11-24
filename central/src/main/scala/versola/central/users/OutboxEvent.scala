package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.CellId
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

  /** The deletion of a user. The directory row is gone by the time it is delivered, so the event carries the cell that
    * held the user (`None` for a user of no cell): it is the only place left that says which `auth` to tell.
    * The default is the documented exception: an event stored before the field existed decodes as `None`.
    */
  case DeleteUser(
      userId: UserId,
      cell: Option[CellId] = None,
  ) extends OutboxEvent("DeleteUser")
