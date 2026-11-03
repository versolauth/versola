package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.util.JsonStringSchema
import zio.json.JsonCodec
import zio.schema.Schema

/** The caller refused a change or a look outside its scope; `403`. */
final class ScopeDenied(message: String) extends RuntimeException(message)

/** Which entries of the lists the caller of the internal API may see and change. The one place that decides: the
  * controller asks it and does nothing of its own.
  *
  * Central derives the scope from the admin route that was called (`/antifraud/lists/...` is [[Tenant]]'s,
  * `/antifraud/global/lists/...` is [[Operator]]'s, and edge only lets a token with `antifraud:operate` reach the second) and
  * never from the request body; auth takes it as central says it.
  *
  *  - [[Tenant]] is an admin of one tenant, the one in the verified token. It sees the global entries and its tenant's, and
  *    adds and removes only its tenant's: an entry it adds is its tenant's whether the body names the tenant or not.
  *  - [[Operator]] is not limited to a tenant: any entry, global ones included.
  */
enum AntifraudScope:
  case Tenant(tenantId: TenantId)
  case Operator

  /** The tenant of an entry this scope adds (or previews), given the one the request named: `None` is a global entry. */
  def entryTenant(requested: Option[TenantId]): Either[ScopeDenied, Option[TenantId]] = this match
    case Tenant(own) =>
      requested match
        case Some(other) if other != own => Left(ScopeDenied("a tenant admin adds entries of its own tenant only"))
        case _ => Right(Some(own))
    case Operator => Right(requested)

  /** Whether this scope may remove an entry of `tenantId`. */
  def mayChange(tenantId: Option[TenantId]): Boolean = this match
    case Tenant(own) => tenantId.contains(own)
    case Operator => true

  /** What a listing is narrowed to for this scope: the entries that are global or the tenant's (`None`: all of them). */
  def visibleTo: Option[TenantId] = this match
    case Tenant(own) => Some(own)
    case Operator => None

  /** The tenant a listing may be narrowed to by its `tenantId` parameter: a tenant admin may name its own and no other. */
  def mayNarrowTo(tenantId: TenantId): Boolean = this match
    case Tenant(own) => own == tenantId
    case Operator => true

object AntifraudScope:

  /** The `scope` field or parameter as central sends it. */
  enum Kind derives JsonCodec:
    case tenant, operator

  object Kind:
    given Schema[Kind] = JsonStringSchema.of

  /** From what central sent: the kind, and the tenant of the token for a tenant scope (without it a tenant scope is not one,
    * and is never taken to be an operator's). */
  def of(kind: Kind, callerTenantId: Option[TenantId]): Either[InvalidListEntry, AntifraudScope] = kind match
    case Kind.operator => Right(Operator)
    case Kind.tenant =>
      callerTenantId.map(Tenant(_)).toRight(InvalidListEntry("a tenant scope names callerTenantId"))
