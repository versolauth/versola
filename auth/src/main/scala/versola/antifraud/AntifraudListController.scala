package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, PageLimit, PageOffset, Phone}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

import java.time.Instant
import java.util.UUID

/** The internal API of [[AntifraudLists]], called by central, which owns the admin API: auth only trusts the internal
  * token. `userId` is the admin who asked, which edge put in central's request from the verified token; it is what
  * `created_by` and the audit record. The rules (which values are valid) are the service's; this maps its failures to 400,
  * a missing entry to 404, a caller that goes outside its scope to 403.
  *
  * ==Scope==
  * Every call says whose scope it is made in: `scope` (`tenant` or `operator`, required: a call without it is `400`, never an
  * operator's) and, for a tenant, `callerTenantId` (a query parameter of a `GET` and a `DELETE`, a body field of the rest).
  * Central sets both from the route the admin called, which edge has checked the permission for. What a scope may do is
  * [[AntifraudScope]]'s to say.
  */
object AntifraudListController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudLists & AntifraudEvents

  def routes: Routes[Env, Throwable] = Routes(createEndpoint, listEndpoint, deleteEndpoint, previewEndpoint, auditEndpoint)

  val createEndpoint =
    Method.POST / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[ListEntryPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          scoped(body).flatMap(newEntry(body, _)).flatMap(lists.create(_, body.userId.toString)).map(entry =>
            Response.json(entry.toJson).status(Status.Created),
          )
      yield response
    }

  val previewEndpoint =
    Method.POST / "antifraud" / "lists" / "preview" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[ListEntryPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          scoped(body).flatMap(newEntry(body, _)).flatMap(lists.preview).map(preview => Response.json(preview.toJson))
      yield response
    }

  /** `tenantId` narrows to one tenant, `global=true` to the global entries. */
  val listEndpoint =
    Method.GET / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        listType <- request.queryZIO[Option[AntifraudListType]]("listType")
        valueType <- request.queryZIO[Option[AntifraudValueType]]("valueType")
        tenant <- request.queryZIO[Option[TenantId]]("tenantId")
        global <- request.queryZIO[Option[Boolean]]("global")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        offset <- request.queryZIO[Option[PageOffset]]("offset")
        callerScope <- queryScope(request)
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          for
            scope <- callerScope
            _ <- ZIO.fail(InvalidListEntry("tenantId and global exclude each other")).when(global.contains(true) && tenant.isDefined)
            _ <- ZIO.fail(ScopeDenied("a tenant admin lists its own tenant's entries and the global ones")).when(tenant.exists(!scope.mayNarrowTo(_)))
            filter = AntifraudListFilter(
              listType = listType,
              valueType = valueType,
              tenantId = if global.contains(true) then Some(None) else tenant.map(Some(_)),
              visibleTo = scope.visibleTo,
            )
            entries <- lists.list(filter, limit.getOrElse(PageLimit.Default), offset.getOrElse(PageOffset.First))
          yield Response.json(entries.toJson)
      yield response
    }

  val deleteEndpoint =
    Method.DELETE / "antifraud" / "lists" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        userId <- request.queryZIO[UserId]("userId")
        id <- request.queryZIO[UUID]("id")
        callerScope <- queryScope(request)
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          for
            scope <- callerScope
            // Whose it is is read first: a tenant admin may not remove another tenant's entry, or a global one. A missing
            // entry is 404 for every scope.
            entry <- lists.find(id)
            _ <- ZIO.foreachDiscard(entry)(e =>
              ZIO.fail(ScopeDenied("a tenant admin removes its own tenant's entries only")).unless(scope.mayChange(e.tenantId)),
            )
            removed <- if entry.isEmpty then ZIO.succeed(false) else lists.delete(id, userId.toString)
            response <- if removed then ZIO.succeed(Response.status(Status.NoContent))
            else Observability.setError("list_entry_not_found").as(Response.status(Status.NotFound))
          yield response
      yield response
    }

  /** The changes of the entries the scope may see. */
  val auditEndpoint =
    Method.GET / "antifraud" / "lists" / "audit" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        entryId <- request.queryZIO[Option[UUID]]("entryId")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        callerScope <- queryScope(request)
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          callerScope.flatMap(scope =>
            lists.audit(entryId, limit.getOrElse(PageLimit.Default), scope.visibleTo).map(records => Response.json(records.toJson)),
          )
      yield response
    }

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidListEntry =>
        Observability.setError("invalid_list_entry", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))
      case e: ScopeDenied =>
        Observability.setError("antifraud_scope_denied", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.Forbidden))

  /** The scope of a call that carries it in the query (a `GET` or a `DELETE`); a failure to read it is a 400 when it is run. */
  private def queryScope(request: Request): ZIO[Any, Throwable, IO[InvalidListEntry, AntifraudScope]] =
    for
      kind <- request.queryZIO[AntifraudScope.Kind]("scope")
      tenant <- request.queryZIO[Option[TenantId]]("callerTenantId")
    yield ZIO.fromEither(AntifraudScope.of(kind, tenant))

  private def scoped(body: ListEntryPayload): IO[InvalidListEntry, AntifraudScope] =
    ZIO.fromEither(AntifraudScope.of(body.scope, body.callerTenantId))

  /** A phone is hashed here, as an event's destination is, so that a `phone_hash` entry can be made from the number. */
  private def newEntry(entry: ListEntryPayload, scope: AntifraudScope): ZIO[AntifraudEvents, Throwable, NewListEntry] =
    for
      tenantId <- ZIO.fromEither(scope.entryTenant(entry.tenantId))
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
      tenantId = tenantId,
      expiresAt = entry.expiresAt,
      reason = entry.reason,
      source = entry.source.getOrElse("manual"),
      shadow = entry.shadow.getOrElse(false),
    )

  /** `value` is what is matched (for a `phone_hash` the hash); `phone` replaces it for a `phone_hash`, as a number. `scope` and
    * `callerTenantId` are central's, see [[AntifraudScope]]. */
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
      userId: UserId,
      scope: AntifraudScope.Kind,
      callerTenantId: Option[TenantId],
  ) derives JsonCodec
