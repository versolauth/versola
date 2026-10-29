package versola.central.antifraud

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.http.{BadRequest, Controller, Observability}
import versola.util.{PageLimit, PageOffset}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

import java.util.UUID

/** The admin API of the antifraud lists (see [[AntifraudListService]]).
  *
  * ==Who is the caller==
  * Central's admin API knows its caller only as the edge, so the endpoints are registered with an inject rule (see
  * `BootstrapService.centralInjects`) that makes edge say who the admin is, from the token it has verified: `userId`, as a
  * query parameter of a `GET` and a body field of any other call, as for the account endpoints. Edge overwrites a
  * parameter or field of the same name that a client sent; that is the only reason it is trusted. A request without it
  * does not decode and is a bad request.
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
        response <- guarded(service.create(body).map(entry => Response.json(entry.toJson).status(Status.Created)))
      yield response
    }

  val previewEndpoint =
    Method.POST / "antifraud" / "lists" / "preview" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[NewListEntryRequest]
        service <- ZIO.service[AntifraudListService]
        response <- guarded(service.preview(body).map(preview => Response.json(preview.toJson)))
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
        userId <- request.queryZIO[UserId]("userId")
        query = ListEntriesQuery(listType = listType, valueType = valueType, tenantId = tenantId, global = global, limit = limit, offset = offset)
        response <- guarded(service.list(query, userId).map(entries => Response.json(entries.toJson)))
      yield response
    }

  /** The entry is named in the body, where edge puts the caller of a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[DeleteListEntryRequest]
        service <- ZIO.service[AntifraudListService]
        response <- guarded(service.delete(body).as(Response.status(Status.NoContent)))
      yield response
    }

  val auditEndpoint =
    Method.GET / "antifraud" / "lists" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AntifraudListService]
        entryId <- request.queryZIO[Option[UUID]]("entryId")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        userId <- request.queryZIO[UserId]("userId")
        response <- guarded(service.audit(entryId, limit, userId).map(records => Response.json(records.toJson)))
      yield response
    }

  /** What the service refused becomes the answer through the usual mechanism: a [[BadRequest]] that
    * `Observability.handleErrors` turns into a 400, and the outcome in the request's error context. */
  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    effect.catchAll:
      case AntifraudListError.Invalid(message) => Observability.setError("invalid_list_entry", Some(message)) *> ZIO.fail(BadRequest(message))
      case AntifraudListError.NotFound => Observability.setError("list_entry_not_found").as(Response.status(Status.NotFound))
      case error: Throwable => ZIO.fail(error)
