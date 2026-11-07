package versola.central.audit

import versola.central.CentralConfig
import versola.central.audit.AuditRoutes.Entry
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

/** Records what an administrator does through central's API (#469), on every route in one place: a controller lists only
  * [[AuditedRoute]]s, each with its declaration, and the app wraps each with [[wrap]].
  *
  * The hook wraps a route's handler. After the handler ran it writes one event: the change's `admin.<resource>.<operation>` with
  * its outcome (2xx `success`, 401 and 403 `denied`, any other status a `failure` whose reason is the request's error code), and
  * for a response that hands a secret back, an `admin.secret.revealed` besides. A read is recorded only where it is an event of
  * its own (a look at a user, a query of the audit log), and then only when it succeeded. What the event says it acts on, and the
  * names of the fields an update set, are what the handler told the log with [[Audit]] once it had decoded its request; the type of
  * the target is the route's. A request that fails before that is recorded without a target. The event is handed to the audit
  * queue after the handler has answered, in the request's own fiber, and a log that does not take it costs a counter and a log
  * line, not the response.
  *
  * ==Who is the administrator==
  * Edge says so: it adds the query parameters `actorId` (the `sub` of the verified token), `actorTenantId`, `actorClientId` and
  * `actorSessionId` to every call (see `BootstrapService.actorInjects`), removing any a client sent. Query parameters because
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
    def param(name: String): Option[String] = actorParam(request, name)
    val sub = param(ActorIdParam)
    val tenant = param(ActorTenantParam).map(TenantId(_))
    val client = param(ActorClientParam).map(ClientId(_))
    val session = param(ActorSessionParam)
    // A client credentials token's subject is the client itself.
    val isClient = isClientSubject(sub, client)
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
    Handler.scoped[Env1]:
      Handler.fromFunctionZIO[Request]: request =>
        for
          actor <- actorOf(request)
          initial = AuditContext.State(
            tenantId = actor.tenantId.map(_.toString),
            actor = Some((actor.actorType, actor.id)),
            userId = None,
            target = None,
            changed = Set.empty,
            userAgent = request.headers.get("User-Agent").map(clipped),
          )
          // What the request's events say of where it came from (the client and the session of the token).
          _ <- ZIO.foreachDiscard(actor.clientId)(client => Observability.setClientId(client.toString))
          _ <- ZIO.foreachDiscard(actor.sessionId)(Observability.setSessionId)
          // The handler may be interrupted (the client went away, a timeout) after its change committed: the record that follows it
          // is not interrupted, and the interruption goes on afterwards.
          response <- Audit.tenantOfTarget.locally(None):
            AuditContext.reset(initial):
              ZIO.uninterruptibleMask: restore =>
                restore(handler(request)).exit.flatMap: exit =>
                  val status = exit match
                    case Exit.Success(response) => response.status
                    case Exit.Failure(cause) => cause.failureOption.fold(Status.InternalServerError)(_.status)
                  val interrupted = exit.causeOption.exists(_.isInterruptedOnly)
                  AuditContext.current.flatMap(state => record(pattern, status, interrupted, state, actor, change, read)) *> ZIO.done(exit)
        yield response

  private def record(
      pattern: String,
      status: Status,
      interrupted: Boolean,
      state: AuditContext.State,
      actor: Actor,
      change: Option[Entry.Change],
      read: Option[Entry.Read],
  ): URIO[AuditEvents & CentralConfig, Unit] =
    Audit.tenantOfTarget.get.flatMap(tenantOfTarget => recordWith(pattern, status, interrupted, state, actor, change, read, tenantOfTarget))

  private def recordWith(
      pattern: String,
      status: Status,
      interrupted: Boolean,
      state: AuditContext.State,
      actor: Actor,
      change: Option[Entry.Change],
      read: Option[Entry.Read],
      tenantOfTarget: Option[String],
  ): URIO[AuditEvents & CentralConfig, Unit] =
    val succeeded = status.isSuccess || status.isRedirection
    val targetType = change.map(_.targetType).orElse(read.map(_.targetType))
    // The handler names the target (`Audit.target`) and what an update changes (`Audit.changed`); the type of the target is the route's.
    val changedNames = state.changed.toList.sorted
    val resolvedTarget = for t <- targetType; id <- state.target.map(target => clipped(target._2)) yield (t, id)
    val details = AdminDetails(
      path = pattern,
      status = status.code,
      changed = Option.when(changedNames.nonEmpty)(changedNames),
      tenantId = tenantOfTarget.map(clipped),
    )
    def draft(kind: AuditKind, outcome: AuditOutcome, reason: Option[String]) = AuditDraft(
      kind = kind,
      outcome = outcome,
      reason = reason,
      actor = Some((actor.actorType, actor.id)),
      userId = state.userId,
      target = resolvedTarget,
      details = details,
    )
    ZIO.serviceWithZIO[AuditEvents]: events =>
      (change, read) match
        case (Some(entry), _) =>
          for
            outcome <- outcomeOf(status, interrupted)
            (result, reason) = outcome
            kind = CentralAudit.admin(entry.resource, entry.operation)
            _ <- ZIO.when(succeeded && entry.targeted && (resolvedTarget.isEmpty || (entry.targetType == "user" && state.userId.isEmpty)))(
              CentralAudit.targetMissing(kind),
            )
            _ <- events.recordBestEffort(draft(kind, result, reason))
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

  private def actorParam(request: Request, name: String): Option[String] =
    queryValue(request, name).map(_.trim).filter(_.nonEmpty).map(clipped)

  private def isClientSubject(sub: Option[String], client: Option[ClientId]): Boolean = sub.exists(id => client.contains(ClientId(id)))

  /** The administrator a call is made by, as the audit log reads it: the user the `sub` of the caller's token names. Nobody for a call
    * edge did not tell the actor of, for one of a client credentials token, and for a `sub` that is no user id. A route that acts on
    * the caller's own record (`GET`/`PATCH /users/preferences`) takes the user from here and from nowhere else, so what the caller sent
    * in a body or under another name is never the subject. */
  def administrator(request: Request): Option[UserId] =
    val sub = actorParam(request, ActorIdParam)
    sub.filterNot(_ => isClientSubject(sub, actorParam(request, ActorClientParam).map(ClientId(_)))).flatMap(UserId.parse(_).toOption)

  private def queryValue(request: Request, name: String): Option[String] =
    request.url.queryParams.map.get(name).flatMap(_.headOption)

  /** What an administrator's action says about itself: the route (its pattern, a closed set), the status, the identifiers in the query, and the names of the fields an update
    * changed (never their values). */
  private case class AdminDetails(
      path: String,
      status: Int,
      changed: Option[List[String]],
      tenantId: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    override def toJson: Json.Obj = AuditDetails.render(this)
