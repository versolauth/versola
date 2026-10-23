package versola.central.antifraud

import versola.central.CentralConfig
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.users.AuthClient
import versola.util.http.{BadRequest, Controller, Forbidden, Observability}
import zio.*
import zio.http.{Method, QueryParams, Request, Response, Routes, handler}
import zio.json.DecoderOps
import zio.json.ast.Json

/** The admin API of the antifraud lists (block, allow, watch and fake lists, see auth's `AntifraudLists`). The lists
  * are stored and enforced by auth; this is the console's door to them, as `UserController` is to the users: it names
  * the caller and passes the request on to auth, which validates it and applies the rules of who may change what.
  *
  * ==Who is the caller==
  * Central's admin API knows its caller only as the edge, so the edge is told (inject rules of these endpoints, see
  * `BootstrapService`) to send who the admin is, from the token it has verified: `adminSubject` and `adminTenant`. An admin of the tenant that carries central's own roles ([[CentralConfig.defaultTenantId]]) is an
  * operator: they may change global entries and other tenants', and read the audit. An admin of any other tenant (one
  * that was given these permissions in their tenant) is limited to that tenant's entries. A request that carries no
  * identity is refused: the fields are trusted because the edge overwrites them, never because a client sent them.
  */
object AntifraudListController extends Controller:
  type Env = Tracing & ResourceService & AuthClient

  /** Where edge puts the caller (inject rules of the endpoints, see `BootstrapService.centralInjects`): query parameters of a
    * `GET`, body fields of anything else, as for the account endpoints. Edge overwrites a field or parameter of the same name
    * a client sent. */
  val SubjectParam = "adminSubject"
  val TenantParam = "adminTenant"

  private val ListQuery = Set("listType", "valueType", "tenantId", "global", "limit", "offset")
  private val AuditQuery = Set("entryId", "limit")

  def routes: Routes[Env, Throwable] = Routes(createEndpoint, listEndpoint, deleteEndpoint, previewEndpoint, auditEndpoint)

  val createEndpoint =
    Method.POST / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        (actor, entry) <- callerAndBody(request)
        forwarded <- ZIO.serviceWithZIO[AuthClient](_.forwardAntifraudLists(Method.POST, Nil, QueryParams.empty, Some(withActor(entry, actor))))
      yield respond(forwarded)
    }

  val previewEndpoint =
    Method.POST / "antifraud" / "lists" / "preview" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        (actor, entry) <- callerAndBody(request)
        forwarded <-
          ZIO.serviceWithZIO[AuthClient](_.forwardAntifraudLists(Method.POST, List("preview"), QueryParams.empty, Some(withActor(entry, actor))))
      yield respond(forwarded)
    }

  val listEndpoint =
    Method.GET / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        actor <- actorOf(request.url.queryParams.queryParam(SubjectParam), request.url.queryParams.queryParam(TenantParam))
        forwarded <- ZIO.serviceWithZIO[AuthClient](_.forwardAntifraudLists(Method.GET, Nil, query(request, ListQuery, actor), None))
      yield respond(forwarded)
    }

  /** The entry is named in the body (`id`), like the other calls that change something, because that is where edge puts
    * the caller of a request that is not a `GET`. */
  val deleteEndpoint =
    Method.DELETE / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        (actor, body) <- callerAndBody(request)
        id <- ZIO.fromOption(body.get("id").flatMap(_.asString)).orElseFail(BadRequest("id is required"))
        forwarded <- ZIO.serviceWithZIO[AuthClient](_.forwardAntifraudLists(Method.DELETE, Nil, asking(QueryParams("id" -> id), actor), None))
      yield respond(forwarded)
    }

  val auditEndpoint =
    Method.GET / "antifraud" / "lists" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        actor <- actorOf(request.url.queryParams.queryParam(SubjectParam), request.url.queryParams.queryParam(TenantParam))
        forwarded <- ZIO.serviceWithZIO[AuthClient](_.forwardAntifraudLists(Method.GET, List("audit"), query(request, AuditQuery, actor), None))
      yield respond(forwarded)
    }

  /** The admin, and the tenant they are limited to; `None` for an operator. */
  private case class Actor(name: String, tenantId: Option[String])

  private def actorOf(subject: Option[String], tenant: Option[String]): IO[Forbidden.type, Actor] =
    (subject.filter(_.nonEmpty), tenant.filter(_.nonEmpty)) match
      case (Some(name), Some(tenant)) => ZIO.succeed(Actor(name, Option.unless(tenant == CentralConfig.defaultTenantId)(tenant)))
      case _ => Observability.setError("admin_identity_missing") *> ZIO.fail(Forbidden)

  /** Who is calling and the rest of the body: the caller's fields are taken out, so that what is forwarded is what the
    * admin sent. A body without them is a request edge did not add them to. */
  private def callerAndBody(request: Request): IO[BadRequest | Forbidden.type, (Actor, Json.Obj)] =
    for
      body <- request.body.asString.orElseFail(BadRequest("the body could not be read"))
      obj <- ZIO.fromEither(body.fromJson[Json.Obj]).orElseFail(BadRequest("the body is not a JSON object"))
      actor <- actorOf(obj.get(SubjectParam).flatMap(_.asString), obj.get(TenantParam).flatMap(_.asString))
    yield actor -> Json.Obj(obj.fields.filterNot((name, _) => name == SubjectParam || name == TenantParam))

  private def withActor(entry: Json.Obj, actor: Actor): Json =
    Json.Obj(
      "entry" -> entry,
      "actor" -> Json.Obj(
        Chunk("name" -> Json.Str(actor.name)) ++ actor.tenantId.map(t => "tenantId" -> Json.Str(t)),
      ),
    )

  /** The parameters the endpoint takes, and who is asking; anything else the caller sent is dropped. */
  private def query(request: Request, names: Set[String], actor: Actor): QueryParams =
    val taken = request.url.queryParams.map.filter((name, _) => names.contains(name))
    asking(QueryParams(taken), actor)

  private def asking(params: QueryParams, actor: Actor): QueryParams =
    val asked = params.addQueryParam("actor", actor.name)
    actor.tenantId.fold(asked)(asked.addQueryParam("actorTenantId", _))

  private def respond(forwarded: AuthClient.Forwarded): Response =
    val response = Response(status = forwarded.status, body = zio.http.Body.fromString(forwarded.body))
    forwarded.contentType.fold(response)(response.addHeader(_))
