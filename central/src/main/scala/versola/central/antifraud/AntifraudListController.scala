package versola.central.antifraud

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.BadRequest
import versola.util.{PageLimit, PageOffset}
import zio.*
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.EncoderOps

import java.util.UUID

/** The admin API of the antifraud lists (see [[AntifraudListService]]).
  *
  * ==Who is the caller==
  * Central's admin API knows its caller only as the edge, so the endpoints are registered with inject rules (see
  * `BootstrapService.centralInjects`) that make edge say who the admin is, from the token it has verified: `userId` (on the
  * calls that change something, which is what auth records) and, on the tenant routes, `callerTenantId`, the tenant of the
  * token; a query parameter of a `GET`, a body field of any other call, as for the account endpoints. Edge overwrites a
  * parameter or field of the same name that a client sent; that is the only reason it is trusted. A request without what the
  * route needs does not decode and is a bad request.
  *
  * ==Two families of routes, two scopes==
  * `/antifraud/lists/...` is for the admin of a tenant (`antifraud:read` and `antifraud:manage`): its calls are made in that
  * tenant's scope. `/antifraud/global/lists/...` is for the operator of the deployment (`antifraud:operate`): global entries
  * and any tenant's, no restriction. The scope is decided here from which route was called (see [[AntifraudCaller]]) and is
  * what auth enforces; nothing in a body or a query can widen it. Adding a scope or moving a route between them is a change
  * to this object and to nothing else of central.
  */
object AntifraudListController extends AuditedController:
  type Env = Tracing & ResourceService & AntifraudListService

  def endpoints =
    List(
      createEndpoint,
      listEndpoint,
      deleteEndpoint,
      previewEndpoint,
      auditEndpoint,
      createGlobalEndpoint,
      listGlobalEndpoint,
      deleteGlobalEndpoint,
      previewGlobalEndpoint,
      auditGlobalEndpoint,
    )

  /** Which scope a call is in, given the `callerTenantId` of the request. */
  private type CallerOf = Option[TenantId] => IO[BadRequest, AntifraudCaller]

  private val tenantCaller: CallerOf = AntifraudCaller.tenant
  private val operatorCaller: CallerOf = _ => ZIO.succeed(AntifraudCaller.Operator)

  val createEndpoint = (Method.POST / "antifraud" / "lists").audited(Audit.change("antifraud-list", Audit.create)) -> handler((request: Request) =>
    create(request, tenantCaller),
  )
  val previewEndpoint =
    (Method.POST / "antifraud" / "lists" / "preview").audited(Audit.Unrecorded) -> handler((request: Request) => preview(request, tenantCaller))
  val listEndpoint = (Method.GET / "antifraud" / "lists").audited(Audit.Unrecorded) -> handler((request: Request) => list(request, tenantCaller))

  /** The entry is named in the body, where edge puts the caller of a request that is not a `GET`. */
  val deleteEndpoint = (Method.DELETE / "antifraud" / "lists").audited(Audit.change("antifraud-list", Audit.delete)) -> handler((request: Request) =>
    delete(request, tenantCaller),
  )
  val auditEndpoint =
    (Method.GET / "antifraud" / "lists" / "audit").audited(Audit.Unrecorded) -> handler((request: Request) => audit(request, tenantCaller))

  val createGlobalEndpoint =
    (Method.POST / "antifraud" / "global" / "lists").audited(Audit.change("antifraud-list", Audit.create)) -> handler((request: Request) =>
      create(request, operatorCaller),
    )
  val previewGlobalEndpoint =
    (Method.POST / "antifraud" / "global" / "lists" / "preview").audited(Audit.Unrecorded) -> handler((request: Request) =>
      preview(request, operatorCaller),
    )
  val listGlobalEndpoint =
    (Method.GET / "antifraud" / "global" / "lists").audited(Audit.Unrecorded) -> handler((request: Request) => list(request, operatorCaller))
  val deleteGlobalEndpoint =
    (Method.DELETE / "antifraud" / "global" / "lists").audited(Audit.change("antifraud-list", Audit.delete)) -> handler((request: Request) =>
      delete(request, operatorCaller),
    )
  val auditGlobalEndpoint =
    (Method.GET / "antifraud" / "global" / "lists" / "audit").audited(Audit.Unrecorded) -> handler((request: Request) =>
      audit(request, operatorCaller),
    )

  private def create(request: Request, callerOf: CallerOf): ZIO[Env, Throwable, Response] =
    for
      _ <- authorizeBasic(request)
      body <- request.bodyAs[NewListEntryRequest]
      caller <- callerOf(body.callerTenantId)
      service <- ZIO.service[AntifraudListService]
      response <- guarded(
        service.create(body, caller).tap(entry => Audit.targetByText(entry.id.toString)).map(entry =>
          Response.json(entry.toJson).status(Status.Created),
        ),
      )
    yield response

  private def preview(request: Request, callerOf: CallerOf): ZIO[Env, Throwable, Response] =
    for
      _ <- authorizeBasic(request)
      body <- request.bodyAs[NewListEntryRequest]
      caller <- callerOf(body.callerTenantId)
      service <- ZIO.service[AntifraudListService]
      response <- guarded(service.preview(body, caller).map(preview => Response.json(preview.toJson)))
    yield response

  private def list(request: Request, callerOf: CallerOf): ZIO[Env, Throwable, Response] =
    for
      _ <- authorizeBasic(request)
      service <- ZIO.service[AntifraudListService]
      listType <- request.queryZIO[Option[AntifraudListType]]("listType")
      valueType <- request.queryZIO[Option[AntifraudValueType]]("valueType")
      tenantId <- request.queryZIO[Option[TenantId]]("tenantId")
      global <- request.queryZIO[Option[Boolean]]("global")
      limit <- request.queryZIO[Option[PageLimit]]("limit")
      offset <- request.queryZIO[Option[PageOffset]]("offset")
      callerTenantId <- request.queryZIO[Option[TenantId]]("callerTenantId")
      caller <- callerOf(callerTenantId)
      query = ListEntriesQuery(listType = listType, valueType = valueType, tenantId = tenantId, global = global, limit = limit, offset = offset)
      response <- guarded(service.list(query, caller).map(entries => Response.json(entries.toJson)))
    yield response

  private def delete(request: Request, callerOf: CallerOf): ZIO[Env, Throwable, Response] =
    for
      _ <- authorizeBasic(request)
      body <- request.bodyAs[DeleteListEntryRequest]
      _ <- Audit.targetByText(body.id.toString)
      caller <- callerOf(body.callerTenantId)
      service <- ZIO.service[AntifraudListService]
      response <- guarded(service.delete(body, caller).as(Response.status(Status.NoContent)))
    yield response

  private def audit(request: Request, callerOf: CallerOf): ZIO[Env, Throwable, Response] =
    for
      _ <- authorizeBasic(request)
      service <- ZIO.service[AntifraudListService]
      entryId <- request.queryZIO[Option[UUID]]("entryId")
      limit <- request.queryZIO[Option[PageLimit]]("limit")
      callerTenantId <- request.queryZIO[Option[TenantId]]("callerTenantId")
      caller <- callerOf(callerTenantId)
      response <- guarded(service.audit(entryId, limit, caller).map(records => Response.json(records.toJson)))
    yield response

  private def guarded(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    AntifraudGuard("invalid_list_entry", "list_entry_not_found")(effect)
