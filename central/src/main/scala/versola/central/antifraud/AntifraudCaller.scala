package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.util.http.BadRequest
import zio.{IO, ZIO}

/** Whose scope an admin call to the lists is made in, and what that scope may see and change: the one place that decides,
  * the service asks it and does nothing of its own.
  *
  * It follows from the route that was called and from nothing the request says: `/antifraud/lists/...` is a [[Tenant]]'s
  * (the tenant of the verified token, which edge puts in `callerTenantId`), `/antifraud/global/lists/...` is the
  * [[Operator]]'s, and edge lets only a token with `antifraud:operate` reach those. The controller makes it here, in one
  * place, and passes it down; the body's `tenantId` (the entry's) is a different thing and never decides it.
  *
  *  - [[Tenant]] is an admin of one tenant. It sees the global entries and its tenant's, and adds and removes only its
  *    tenant's: an entry it adds is its tenant's whether the body names the tenant or not.
  *  - [[Operator]] is not limited to a tenant: any entry, global ones included.
  */
enum AntifraudCaller:
  case Tenant(tenantId: TenantId)
  case Operator

  /** The tenant of an entry this caller adds (or previews), given the one the request named: `None` is a global entry. */
  def entryTenant(requested: Option[TenantId]): Either[AntifraudListError.Forbidden, Option[TenantId]] = this match
    case Tenant(own) =>
      requested match
        case Some(other) if other != own => Left(AntifraudListError.Forbidden("a tenant admin adds entries of its own tenant only"))
        case _ => Right(Some(own))
    case Operator => Right(requested)

  /** Whether this caller may remove an entry of `tenantId`. */
  def mayChange(tenantId: Option[TenantId]): Boolean = this match
    case Tenant(own) => tenantId.contains(own)
    case Operator => true

  /** What a listing is narrowed to for this caller: the entries that are global or the tenant's (`None`: all of them). */
  def visibleTo: Option[TenantId] = this match
    case Tenant(own) => Some(own)
    case Operator => None

  /** The tenant a listing may be narrowed to by its `tenantId` parameter: a tenant admin may name its own and no other. */
  def mayNarrowTo(tenantId: TenantId): Boolean = this match
    case Tenant(own) => own == tenantId
    case Operator => true

object AntifraudCaller:

  /** The caller of a tenant route, from the `callerTenantId` edge injected: a request without it is a bad request, never an
    * operator's. */
  def tenant(callerTenantId: Option[TenantId]): IO[BadRequest, AntifraudCaller] =
    ZIO.fromOption(callerTenantId).mapBoth(_ => BadRequest("callerTenantId is missing: edge adds it from the verified token"), Tenant(_))
