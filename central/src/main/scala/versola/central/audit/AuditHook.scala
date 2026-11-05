package versola.central.audit

import versola.central.CentralConfig
import versola.central.audit.AuditRoutes.{Entry, Target}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.audit.*
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.logging.logContext

import java.nio.charset.StandardCharsets

/** Records what an administrator does through central's API (#469), on every route in one place, so that an endpoint added later
  * is recorded without anyone remembering to (and a route that [[AuditRoutes]] does not know fails its spec).
  *
  * The hook wraps a route's handler. After the handler ran it writes one event: the change's `admin.<resource>.<operation>` with
  * its outcome (2xx `success`, 401 and 403 `denied`, any other status a `failure` whose reason is the request's error code), and
  * for a response that hands a secret back, an `admin.secret.revealed` besides. A read is recorded only where it is an event of
  * its own (a look at a user, a query of the audit log), and then only when it succeeded. The event is handed to the audit
  * queue after the handler has answered, in the request's own fiber, and a log that does not take it costs a counter and a log
  * line, not the response.
  *
  * ==Who is the administrator==
  * Edge says so: it adds the query parameters `actorId` (the `sub` of the verified token), `actorTenantId`, `actorClientId` and
  * `actorSessionId` to every call (see `BootstrapService.centralInjects`), removing any a client sent. Query parameters because
  * they work on every method, and because a `userId` field of a body is the target user on the user routes. A call without them is
  * not refused, which would break a deployment whose edge has not yet read the new registrations and every direct caller of
  * central: it is recorded as made by a `service`, with no id, and counted in `audit_actor_missing_total`. A value that is not of the
  * type it names (an `actorId` that is no user id) is treated as absent and counted too. The service of the request also finds the
  * actor in the [[AuditContext]], which is how `AuthClient` tells auth on whose behalf it calls.
  */
object AuditHook:

  /** The `sub` of the caller's token. */
  val ActorIdParam = "actorId"

  /** The tenant of the caller's token. */
  val ActorTenantParam = "actorTenantId"

  /** The client the caller's token was issued to: equal to the actor for a client credentials token, whose subject is the client. */
  val ActorClientParam = "actorClientId"

  /** The session the caller's token belongs to, when it carries one. */
  val ActorSessionParam = "actorSessionId"

  /** Longest value of a header, a query parameter or a body field the log keeps. */
  private val MaxLength = 256

  /** Query parameters whose values are written to `details.query`: identifiers, never anything that could be a secret. */
  private val loggedQuery = Set(
    "tenantId",
    "clientId",
    "edgeId",
    "resourceId",
    "roleId",
    "scopeId",
    "permission",
    "type",
    "id",
    "userId",
    "kid",
    "alg",
    "dryRun",
    "userAgentId",
    "sessionId",
    "credentialId",
  )

  /** The route with its record: what the route's own declaration says is written after it ran. */
  def wrap[Env](route: AuditedRoute[Env]): Route[Env & AuditEvents & CentralConfig, Throwable] =
    val pattern = route.route.routePattern.pathCodec.render
    route.route.transform[Env & AuditEvents & CentralConfig](handler => audited(route.entry, pattern)(handler))

  private def audited[Env1 <: AuditEvents & CentralConfig](entry: Entry, pattern: String)(
      handler: Handler[Env1, Response, Request, Response],
  ): Handler[Env1, Response, Request, Response] =
    entry match
      case entry: Entry.Change => recording(handler, pattern, Some(entry), None)
      case entry: Entry.Read => recording(handler, pattern, None, Some(entry))
      // Unrecorded and internal routes record nothing.
      case _ => handler

  /** Who made the call, from what edge put in it. */
  private case class Actor(
      actorType: ActorType,
      id: Option[String],
      tenantId: Option[TenantId],
      clientId: Option[ClientId],
      sessionId: Option[String],
  )

  private def clipped(value: String): String = value.take(MaxLength)

  /** The caller, from the parameters edge injected; a call that has none, or an `actorId` that is no user id and no client, is a
    * service's and is counted. */
  private def actorOf(request: Request): UIO[Actor] =
    def param(name: String): Option[String] = queryValue(request, name).map(_.trim).filter(_.nonEmpty).map(clipped)
    val sub = param(ActorIdParam)
    val tenant = param(ActorTenantParam).map(TenantId(_))
    val client = param(ActorClientParam).map(ClientId(_))
    val session = param(ActorSessionParam)
    // A client credentials token's subject is the client itself.
    val isClient = sub.exists(id => client.contains(ClientId(id)))
    val admin = sub.filterNot(_ => isClient).flatMap(UserId.parse(_).toOption)
    val actor =
      if isClient then Actor(CentralAudit.Actors.Client, sub, tenant, client, session)
      else
        admin.fold(Actor(CentralAudit.Actors.Service, None, tenant, client, session))(id =>
          Actor(CentralAudit.Actors.Admin, Some(id.toString), tenant, client, session),
        )
    val missing = if sub.isEmpty then Some("absent") else Option.when(actor.id.isEmpty)("invalid")
    CentralAudit.actorMissing(missing).as(actor)

  private def recording[Env1 <: AuditEvents & CentralConfig](
      handler: Handler[Env1, Response, Request, Response],
      pattern: String,
      change: Option[Entry.Change],
      read: Option[Entry.Read],
  ): Handler[Env1, Response, Request, Response] =
    val target = change.map(_.target).orElse(read.map(_.target)).getOrElse(Target.Nowhere)
    // The body is read where it names the target, and where it says what an update changes (the names of its fields).
    val readsBody = target.isInstanceOf[Target.Body] || change.exists(_.operation == CentralAudit.AdminUpdate)
    Handler.scoped[Env1]:
      Handler.fromFunctionZIO[Request]: original =>
        for
          prepared <- withBody(original, readsBody)
          (request, body) = prepared
          actor <- actorOf(request)
          initial = AuditContext.State(
            tenantId = actor.tenantId.map(_.toString),
            actor = Some((actor.actorType, actor.id)),
            userAgent = request.headers.get("User-Agent").map(clipped),
          )
          // What the request's events say of where it came from (the client and the session of the token).
          _ <- ZIO.foreachDiscard(actor.clientId)(client => Observability.setClientId(client.toString))
          _ <- ZIO.foreachDiscard(actor.sessionId)(Observability.setSessionId)
          // The handler may be interrupted (the client went away, a timeout) after its change committed: what follows it is not
          // interrupted, the record is handed over by a fiber that the interruption of this one does not reach, and the interruption
          // goes on afterwards.
          response <- AuditContext.reset(initial):
            ZIO.uninterruptibleMask: restore =>
              restore(handler(request)).exit.flatMap: exit =>
                val status = exit match
                  case Exit.Success(response) => response.status
                  case Exit.Failure(cause) => cause.failureOption.fold(Status.InternalServerError)(_.status)
                val interrupted = exit.causeOption.exists(_.isInterruptedOnly)
                val recording = AuditContext.current.flatMap(state => record(request, pattern, status, interrupted, state, actor, body, change, read))
                // interruptible inside: a full queue under `block` is waited for up to its own timeout, which is an interruption
                recording.interruptible.forkDaemon.flatMap(fiber => restore(fiber.join)) *> ZIO.done(exit)
        yield response

  /** The request with its body read once and put back, where the entry names the target in the body: the handler reads it again. */
  private def withBody(request: Request, readsBody: Boolean): UIO[(Request, Option[Json.Obj])] =
    if readsBody && request.method != Method.GET then
      request.body.asArray.fold(
        _ => (request, None),
        bytes => (request.copy(body = Body.fromArray(bytes)), String(bytes, StandardCharsets.UTF_8).fromJson[Json.Obj].toOption),
      )
    else ZIO.succeed((request, None))

  private def record(
      request: Request,
      pattern: String,
      status: Status,
      interrupted: Boolean,
      state: AuditContext.State,
      actor: Actor,
      body: Option[Json.Obj],
      change: Option[Entry.Change],
      read: Option[Entry.Read],
  ): URIO[AuditEvents & CentralConfig, Unit] =
    val succeeded = status.isSuccess || status.isRedirection
    val entryTarget = change.map(_.target).orElse(read.map(_.target)).getOrElse(Target.Nowhere)
    val targetType = change.map(_.targetType).orElse(read.map(_.targetType))
    val targetId = state.target.map(_._2).orElse(targetOf(request, entryTarget, body))
    val query =
      request.url.queryParams.map.collect { case (name, values) if loggedQuery.contains(name) && values.nonEmpty => name -> clipped(values.head) }
    // What an update changes: what the service said, else the names of the fields the request carried (never their values), less
    // the ones that name what is updated.
    val changedNames =
      if state.changed.nonEmpty then state.changed.toList.sorted
      else if change.exists(_.operation == CentralAudit.AdminUpdate) then
        body.fold(List.empty[String])(_.fields.map(_._1).filterNot(namesTarget).toList.sorted)
      else Nil
    val details = AdminDetails(
      path = pattern,
      status = status.code,
      query = Option.when(query.nonEmpty)(query),
      changed = Option.when(changedNames.nonEmpty)(changedNames),
      tenantId = queryValue(request, "tenantId").map(clipped).orElse(body.flatMap(stringField("tenantId"))),
    )
    val resolvedTarget = state.target.orElse(for t <- targetType; id <- targetId yield (t, id))
    // The user the action was about, when the target is one; an id that is not a UUID is not a user of ours.
    val user = state.userId.orElse(resolvedTarget.filter(_._1 == "user").flatMap((_, id) => UserId.parse(id).toOption))
    def draft(kind: AuditKind, outcome: AuditOutcome, reason: Option[String]) = AuditDraft(
      kind = kind,
      outcome = outcome,
      reason = reason,
      actor = Some((actor.actorType, actor.id)),
      userId = user,
      target = resolvedTarget,
      details = details,
    )
    ZIO.serviceWithZIO[AuditEvents]: events =>
      (change, read) match
        case (Some(entry), _) =>
          for
            outcome <- outcomeOf(status, interrupted)
            (result, reason) = outcome
            _ <- events.recordBestEffort(draft(CentralAudit.admin(entry.resource, entry.operation), result, reason))
            _ <-
              ZIO.when(succeeded && entry.revealsSecret)(events.recordBestEffort(draft(CentralAudit.AdminSecretRevealed, AuditOutcome.success, None)))
          yield ()
        case (_, Some(entry)) =>
          // A look at a user can be left out of the log by the deployment (`events.admin-user-viewed`): it is the one read that is many.
          ZIO.serviceWith[CentralConfig](config => entry.kind != AuditRoutes.AdminUserViewed || config.audit.events.adminUserViewed).flatMap:
            wanted =>
              ZIO.when(succeeded && wanted)(events.recordBestEffort(draft(entry.kind, AuditOutcome.success, None))).unit
        case _ => ZIO.unit

  /** The outcome of a status, and the reason of one that is not a success: the error code the request set, else its status. */
  private def outcomeOf(status: Status, interrupted: Boolean): UIO[(AuditOutcome, Option[String])] =
    if interrupted then ZIO.succeed((AuditOutcome.failure, Some("interrupted")))
    else if status.isSuccess || status.isRedirection then ZIO.succeed((AuditOutcome.success, None))
    else
      logContext.get.map(_.get(Observability.error).map(_.code)).map: code =>
        val reason = Some(code.getOrElse(s"http_${status.code}"))
        if status == Status.Unauthorized || status == Status.Forbidden then (AuditOutcome.denied, reason) else (AuditOutcome.failure, reason)

  /** The members of a body that say what is updated and not what changes. */
  private val namesTarget = Set("id", "clientId", "tenantId", "userId", "resourceId", "edgeId", "kid", "version", "code")

  private def queryValue(request: Request, name: String): Option[String] =
    request.url.queryParams.map.get(name).flatMap(_.headOption)

  private def stringField(name: String)(body: Json.Obj): Option[String] =
    body.fields.collectFirst { case (`name`, Json.Str(value)) => clipped(value) }

  private def targetOf(request: Request, target: Target, body: Option[Json.Obj]): Option[String] =
    target match
      case Target.Query(params) => params.view.flatMap(queryValue(request, _)).headOption.map(clipped)
      case Target.Body(field) => body.flatMap(stringField(field))
      case Target.Nowhere => None

  /** What an administrator's action says about itself: the route (its pattern, a closed set), the status, the identifiers in the query, and the names of the fields an update
    * changed (never their values). */
  private case class AdminDetails(
      path: String,
      status: Int,
      query: Option[Map[String, String]],
      changed: Option[List[String]],
      tenantId: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    override def toJson: Json.Obj = AuditDetails.render(this)
