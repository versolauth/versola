package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.user.authorizeInternal
import versola.util.http.{BadRequest, Controller, Observability}
import versola.util.{CoreConfig, Phone}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

import java.time.Instant
import java.util.UUID

/** The internal API of [[AntifraudLists]], called by central, which owns the admin API and decides who the caller is
  * ([[ListActorPayload]]): auth only trusts the internal token. The rules (who may change what, which values are valid)
  * are the service's; this maps its failures to 400 and 403, a missing entry to 404.
  */
object AntifraudListController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudLists & AntifraudEvents

  private val DefaultLimit = 100
  private val MaxLimit = 500

  def routes: Routes[Env, Throwable] = Routes(createEndpoint, listEndpoint, deleteEndpoint, previewEndpoint, auditEndpoint)

  val createEndpoint =
    Method.POST / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[CreateListEntryPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          newEntry(body.entry).flatMap(lists.create(_, body.actor.toActor)).map(entry => Response.json(entry.toJson).status(Status.Created))
      yield response
    }

  val previewEndpoint =
    Method.POST / "antifraud" / "lists" / "preview" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[CreateListEntryPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          newEntry(body.entry).flatMap(lists.preview(_, body.actor.toActor)).map(preview => Response.json(preview.toJson))
      yield response
    }

  /** `tenantId` narrows to one tenant, `global=true` to the global entries; a tenant's admin sees their own either way. */
  val listEndpoint =
    Method.GET / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        actor <- actorOf(request)
        listType <- optional(request, "listType")(parseEnum("listType", AntifraudListType.valueOf))
        valueType <- optional(request, "valueType")(parseEnum("valueType", AntifraudValueType.valueOf))
        tenant = request.url.queryParams.queryParam("tenantId")
        global = request.url.queryParams.queryParam("global").contains("true")
        _ <- ZIO.fail(BadRequest("tenantId and global exclude each other")).when(global && tenant.isDefined)
        limit <- limitOf(request)
        offset <- offsetOf(request)
        lists <- ZIO.service[AntifraudLists]
        filter = AntifraudListFilter(
          listType = listType,
          valueType = valueType,
          tenantId = if global then Some(None) else tenant.map(t => Some(TenantId(t))),
        )
        response <- guarded(lists.list(filter, actor, limit, offset).map(entries => Response.json(entries.toJson)))
      yield response
    }

  val deleteEndpoint =
    Method.DELETE / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        actor <- actorOf(request)
        id <- uuidOf(request, "id")
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          lists.delete(id, actor).flatMap:
            case true => ZIO.succeed(Response.status(Status.NoContent))
            case false => Observability.setError("list_entry_not_found").as(Response.status(Status.NotFound))
      yield response
    }

  /** Operators only (the service refuses anyone else). */
  val auditEndpoint =
    Method.GET / "antifraud" / "lists" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        actor <- actorOf(request)
        entryId <- optional(request, "entryId")(_ => uuidOf(request, "entryId"))
        limit <- limitOf(request)
        lists <- ZIO.service[AntifraudLists]
        response <- guarded(lists.audit(entryId, actor, limit).map(records => Response.json(records.toJson)))
      yield response
    }

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidListEntry =>
        Observability.setError("invalid_list_entry", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))
      case e: ListForbidden => Observability.setError("list_forbidden", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.Forbidden))

  /** A phone is hashed here, as an event's destination is, so that a `phone_hash` entry can be made from the number. */
  private def newEntry(entry: ListEntryPayload): ZIO[AntifraudEvents, Throwable, NewListEntry] =
    for
      value <- (entry.value, entry.phone) match
        case (Some(value), None) => ZIO.succeed(value)
        case (None, Some(phone)) if entry.valueType == AntifraudValueType.phone_hash =>
          ZIO.fromEither(Phone.parse(phone)).mapError(_ => InvalidListEntry("phone is not a valid phone number")).flatMap: parsed =>
            ZIO.serviceWithZIO[AntifraudEvents](_.subjectHash(Right(parsed)))
        case (None, Some(_)) => ZIO.fail(InvalidListEntry("phone is for a phone_hash entry only"))
        case _ => ZIO.fail(InvalidListEntry("give either value or phone, not both and not neither"))
    yield NewListEntry(
      listType = entry.listType,
      valueType = entry.valueType,
      value = value,
      tenantId = entry.tenantId,
      expiresAt = entry.expiresAt,
      reason = entry.reason,
      source = entry.source.getOrElse("manual"),
      shadow = entry.shadow.getOrElse(false),
    )

  private def actorOf(request: Request): IO[BadRequest, ListActor] =
    ZIO.fromOption(request.url.queryParams.queryParam("actor").filter(_.nonEmpty))
      .orElseFail(BadRequest("actor is required"))
      .map(name => ListActor(name = name, tenantId = request.url.queryParams.queryParam("actorTenantId").filter(_.nonEmpty).map(TenantId(_))))

  private def uuidOf(request: Request, name: String): IO[BadRequest, UUID] =
    ZIO.fromOption(request.url.queryParams.queryParam(name))
      .orElseFail(BadRequest(s"$name is required"))
      .flatMap(raw => ZIO.attempt(UUID.fromString(raw)).orElseFail(BadRequest(s"$name is not a UUID")))

  private def optional[A](request: Request, name: String)(parse: String => IO[BadRequest, A]): IO[BadRequest, Option[A]] =
    ZIO.foreach(request.url.queryParams.queryParam(name))(parse)

  private def parseEnum[A](name: String, parse: String => A)(raw: String): IO[BadRequest, A] =
    ZIO.attempt(parse(raw)).orElseFail(BadRequest(s"$name is not one of the known values"))

  private def intOf(request: Request, name: String, default: Int, max: Int): IO[BadRequest, Int] =
    request.url.queryParams.queryParam(name) match
      case None => ZIO.succeed(default)
      case Some(raw) =>
        ZIO.fromOption(raw.toIntOption.filter(n => n >= 0 && n <= max)).orElseFail(BadRequest(s"$name must be between 0 and $max"))

  private def limitOf(request: Request) = intOf(request, "limit", DefaultLimit, MaxLimit).filterOrFail(_ > 0)(BadRequest("limit must be positive"))

  private def offsetOf(request: Request) = intOf(request, "offset", 0, Int.MaxValue)

  case class ListActorPayload(name: String, tenantId: Option[TenantId]) derives JsonCodec:
    def toActor: ListActor = ListActor(name = name, tenantId = tenantId)

  /** `value` is what is matched (for a `phone_hash` the hash); `phone` replaces it for a `phone_hash`, as a number. */
  case class ListEntryPayload(
      listType: AntifraudListType,
      valueType: AntifraudValueType,
      value: Option[String],
      phone: Option[String],
      tenantId: Option[TenantId],
      expiresAt: Option[Instant],
      reason: Option[String],
      source: Option[String],
      shadow: Option[Boolean],
  ) derives JsonCodec

  case class CreateListEntryPayload(entry: ListEntryPayload, actor: ListActorPayload) derives JsonCodec
