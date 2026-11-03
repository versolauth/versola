package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.util.http.BadRequest
import zio.{IO, ZIO}

/** Whose scope an admin call to the lists is made in, which auth enforces (see its `AntifraudScope`).
  *
  * It follows from the route that was called and from nothing the request says: `/antifraud/lists/...` is a [[Tenant]]'s
  * (the tenant of the verified token, which edge puts in `callerTenantId`), `/antifraud/global/lists/...` is the
  * [[Operator]]'s, and edge lets only a token with `antifraud:operate` reach those. The controller makes it here, in one
  * place, and passes it down; the body's `tenantId` (the entry's) is a different thing and never decides it.
  */
enum AntifraudCaller:
  case Tenant(tenantId: TenantId)
  case Operator

object AntifraudCaller:

  /** The caller of a tenant route, from the `callerTenantId` edge injected: a request without it is a bad request, never an
    * operator's. */
  def tenant(callerTenantId: Option[TenantId]): IO[BadRequest, AntifraudCaller] =
    ZIO.fromOption(callerTenantId).mapBoth(_ => BadRequest("callerTenantId is missing: edge adds it from the verified token"), Tenant(_))
