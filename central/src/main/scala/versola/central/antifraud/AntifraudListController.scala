package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.{BadRequest, Controller, Forbidden, Observability}
import versola.util.{PageLimit, PageOffset}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

import java.util.UUID

/** The admin API of the antifraud lists (see [[AntifraudListService]]).
  *
  * ==Who is the caller==
  * Central's admin API knows its caller only as the edge, so the endpoints are registered with inject rules (see
  * `BootstrapService.centralInjects`) that make edge say who the admin is, from the token it has verified: `adminSubject`
  * and `adminTenant`, as query parameters of a `GET` and body fields of any other call, as for the account endpoints.
  * Edge overwrites a parameter or field of the same name that a client sent; that is the only reason they are trusted.
  * A request without them is refused.
  */
object AntifraudListController extends Controller:
  type Env = Tracing & ResourceService & AntifraudListService

  def routes: Routes[Env, Throwable] = Routes(createEndpoint, listEndpoint, deleteEndpoint, previewEndpoint, auditEndpoint)

  val createEndpoint =
    Method.POST / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[NewListEntryRequest]
        service <- ZIO.service[AntifraudListService]
        response <- guarded(service.create(body.entry, body.caller).map(entry => Response.json(entry.toJson).status(Status.Created)))
      yield response
    }

  val previewEndpoint =
    Method.POST / "antifraud" / "lists" / "preview" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[NewListEntryRequest]
        service <- ZIO.service[AntifraudListService]
        response <- guarded(service.preview(body.entry, body.caller).map(preview => Response.json(preview.toJson)))
      yield response
    }

  val listEndpoint =
    Method.GET / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudListService]
        listType <- request.queryZIO[Option[AntifraudListType]]("listType")
        valueType <- request.queryZIO[Option[AntifraudValueType]]("valueType")
        tenantId <- request.queryZIO[Option[TenantId]]("tenantId")
        global <- request.queryZIO[Option[Boolean]]("global")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        offset <- request.queryZIO[Option[PageOffset]]("offset")
        caller <- callerOf(request)
        query = ListEntriesQuery(listType = listType, valueType = valueType, tenantId = tenantId, global = global, limit = limit, offset = offset)
        response <- guarded(service.list(query, caller).map(entries => Response.json(entries.toJson)))
      yield response
    }

  /** The entry is named in the body, where edge puts the caller of a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[DeleteListEntryRequest]
        service <- ZIO.service[AntifraudListService]
        response <- guarded(service.delete(body.id, body.caller).as(Response.status(Status.NoContent)))
      yield response
    }

  val auditEndpoint =
    Method.GET / "antifraud" / "lists" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudListService]
        entryId <- request.queryZIO[Option[UUID]]("entryId")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        caller <- callerOf(request)
        response <- guarded(service.audit(entryId, limit, caller).map(records => Response.json(records.toJson)))
      yield response
    }

  private def callerOf(request: Request): ZIO[Any, Throwable, AdminCaller] =
    for
      subject <- request.queryZIO[Option[String]]("adminSubject")
      tenant <- request.queryZIO[Option[TenantId]]("adminTenant")
    yield AdminCaller(subject = subject, tenant = tenant)

  /** What the service refused becomes the answer through the usual mechanism: a [[BadRequest]] or [[Forbidden]] that
    * `Observability.handleErrors` turns into a 400 or 403, and the outcome in the request's error context. */
  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    effect.catchAll:
      case AntifraudListError.Invalid(message) => Observability.setError("invalid_list_entry", Some(message)) *> ZIO.fail(BadRequest(message))
      case AntifraudListError.Forbidden(message) => Observability.setError("list_forbidden", Some(message)) *> ZIO.fail(Forbidden)
      case AntifraudListError.NoCaller => Observability.setError("admin_identity_missing") *> ZIO.fail(Forbidden)
      case AntifraudListError.NotFound => Observability.setError("list_entry_not_found").as(Response.status(Status.NotFound))
      case error: Throwable => ZIO.fail(error)
