package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.PageLimit
import versola.util.http.{BadRequest, Controller, Observability}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

/** The admin API of the antifraud settings (see [[AntifraudSettingsService]]). The caller is known as for
  * [[AntifraudListController]]: edge injects `userId`, as a query parameter of a `GET` and a body field of any other call. */
object AntifraudSettingsController extends Controller:
  type Env = Tracing & ResourceService & AntifraudSettingsService

  def routes: Routes[Env, Throwable] = Routes(getEndpoint, putEndpoint, deleteEndpoint, auditEndpoint)

  /** `tenantId` for a tenant's row, none for the global one. */
  val getEndpoint =
    Method.GET / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudSettingsService]
        tenantId <- request.queryZIO[Option[TenantId]]("tenantId")
        userId <- request.queryZIO[UserId]("userId")
        response <- guarded(service.get(tenantId, userId).map(settings => Response.json(settings.toJson)))
      yield response
    }

  val putEndpoint =
    Method.PUT / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[PutAntifraudSettingsRequest]
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.put(body).map(settings => Response.json(settings.toJson)))
      yield response
    }

  /** The scope is named in the body, where edge puts the caller of a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[DeleteAntifraudSettingsRequest]
        service <- ZIO.service[AntifraudSettingsService]
        response <- guarded(service.delete(body).as(Response.status(Status.NoContent)))
      yield response
    }

  val auditEndpoint =
    Method.GET / "antifraud" / "settings" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudSettingsService]
        tenantId <- request.queryZIO[Option[TenantId]]("tenantId")
        global <- request.queryZIO[Option[Boolean]]("global")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        userId <- request.queryZIO[UserId]("userId")
        response <- guarded(
          service.audit(AntifraudSettingsAuditQuery(tenantId = tenantId, global = global, limit = limit), userId)
            .map(records => Response.json(records.toJson)),
        )
      yield response
    }

  /** What auth refused becomes the answer through the usual mechanism: a [[BadRequest]] that
    * `Observability.handleErrors` turns into a 400, and the outcome in the request's error context. */
  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    effect.catchAll:
      case AntifraudListError.Invalid(message) => Observability.setError("invalid_antifraud_settings", Some(message)) *> ZIO.fail(BadRequest(message))
      case AntifraudListError.NotFound => Observability.setError("antifraud_settings_not_found").as(Response.status(Status.NotFound))
      case error: Throwable => ZIO.fail(error)
