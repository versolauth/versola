package versola.central.audit

import versola.central.audit.AuditRoutes.Entry
import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.audit.*
import versola.util.http.{IpAddress, Observability}
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
  * line, not the response. Nothing is recorded for a tenant that has switched its audit log off (Security settings): the tenant of
  * the change is the one the route names with [[Audit.tenant]] (the tenant a client, a role or the settings belong to), else the
  * administrator's own; a call that names none is recorded.
  *
  * ==Who is the administrator==
  * Edge says so: it adds the query parameters `actorId` (the `sub` of the verified token), `actorTenantId`, `actorClientId` and
  * `actorSessionId` to every call, and `actorIp`, the address the call came from, where the edge's own `client-ip` setting finds one
  * (see `BootstrapService.actorInjects`), removing any a client sent. Query parameters because
  * they work on every method, and because a `userId` field of a body is the target user on the user routes. A call without them, or
  * with an `actorId` that is no user id and no client, names no actor: it is counted in `audit_actor_missing_total` (`absent`,
  * `invalid`) and refused, see below. An `actorIp` that is no address is treated as absent and counted too (`invalid_ip`); it costs the
  * row its `ip`, not the call. The service of the request also finds the actor in the [[AuditContext]], which is how `AuthClient` tells
  * auth on whose behalf it calls.
  *
  * ==An actor is required==
  * A call of an administrator's route (any route that is not declared [[Entry.Internal]], the one declaration that says a route has no
  * administrator) that names no valid actor is refused with `401`, the status of any unauthenticated call, and the handler does not run.
  * The refusal is counted in `audit_actor_refused_total{reason}`; a refused change is recorded as a denial (`actor_required`, actor
  * `service`), like any other `401` of a recorded route.
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

  /** The address the call came from, as the edge that took it found it (its `client-ip` setting); absent where the edge does not
    * trust one. */
  val ActorIpParam = "actorIp"

  /** Longest value of a header, a query parameter or a body field the log keeps. */
  private val MaxLength = 256

  /** The route with its record: what the route's own declaration says is written after it ran. */
  def wrap[Env](route: AuditedRoute[Env]): Route[Env & AuditEvents & AuditTenants, Throwable] =
    val pattern = route.route.routePattern.pathCodec.render
    route.route.transform[Env & AuditEvents & AuditTenants](handler => audited(route.entry, pattern)(handler))

  private def audited[Env1 <: AuditEvents & AuditTenants](entry: Entry, pattern: String)(
      handler: Handler[Env1, Response, Request, Response],
  ): Handler[Env1, Response, Request, Response] =
    entry match
      case entry: Entry.Change => recording(handler, pattern, Some(entry), None)
      case entry: Entry.Read => recording(handler, pattern, None, Some(entry))
      // An administrator's read that nobody records needs no event, only an actor.
      case Entry.Unrecorded => requiringActor(handler)
      // An internal route is not an administrator's call: it has no actor to require.
      case Entry.Internal => handler

  /** Who made the call, from what edge put in it. */
  private case class Actor(
      actorType: ActorType,
      id: Option[String],
      tenantId: Option[TenantId],
      clientId: Option[ClientId],
      sessionId: Option[String],
      ip: Option[String],
      missing: Option[String],
  ):
    def named: Boolean = missing.isEmpty

  private def clipped(value: String): String = value.take(MaxLength)

  /** The caller, from the parameters edge injected; a call that has none, or an `actorId` that is no user id and no client, is a
    * service's and is counted. */
  private def actorOf(request: Request): UIO[Actor] =
    def param(name: String): Option[String] = queryValue(request, name).map(_.trim).filter(_.nonEmpty).map(clipped)
    val sub = param(ActorIdParam)
    val tenant = param(ActorTenantParam).map(TenantId(_))
    val client = param(ActorClientParam).map(ClientId(_))
    val session = param(ActorSessionParam)
    val stated = param(ActorIpParam)
    val ip = stated.flatMap(IpAddress.literal)
    // A client credentials token's subject is the client itself.
    val isClient = sub.exists(id => client.contains(ClientId(id)))
    val admin = sub.filterNot(_ => isClient).flatMap(UserId.parse(_).toOption)
    val kind =
      if isClient then (CentralAudit.Actors.Client, sub)
      else admin.fold((CentralAudit.Actors.Service, Option.empty[String]))(id => (CentralAudit.Actors.Admin, Some(id.toString)))
    val missing = if sub.isEmpty then Some("absent") else Option.when(kind._2.isEmpty)("invalid")
    val actor = Actor(
      actorType = kind._1,
      id = kind._2,
      tenantId = tenant,
      clientId = client,
      sessionId = session,
      ip = ip,
      missing = missing,
    )
    // An address that is no address is not recorded, and is counted as an actor that is not one is.
    CentralAudit.actorMissing(missing) *> CentralAudit.actorMissing(Option.when(stated.isDefined && ip.isEmpty)("invalid_ip")).as(actor)

  /** What a call that names no valid actor gets: the status of any unauthenticated call. The error code is the request's, so a recorded
    * call says why it was denied. */
  private def refusal(actor: Actor): UIO[Response] =
    Observability.setError("actor_required", Some("the call names no valid actor")) *>
      ZIO.foreachDiscard(actor.missing)(CentralAudit.actorRefused).as(Response.unauthorized)

  /** An administrator's route that is not recorded: served as it is, unless the call names no valid actor. */
  private def requiringActor[Env1](
      handler: Handler[Env1, Response, Request, Response],
  ): Handler[Env1, Response, Request, Response] =
    Handler.scoped[Env1]:
      Handler.fromFunctionZIO[Request]: request =>
        actorOf(request).flatMap(actor => if actor.named then handler(request) else refusal(actor))

  private def recording[Env1 <: AuditEvents & AuditTenants](
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
          // The address the events of the request carry, and `AuthClient` passes on to auth for what it records there.
          _ <- ZIO.foreachDiscard(actor.ip)(Observability.setIp)
          // The handler may be interrupted (the client went away, a timeout) after its change committed: the record that follows it
          // is not interrupted, and the interruption goes on afterwards.
          response <- Audit.tenantOfTarget.locally(None):
            Audit.partnerOfTarget.locally(None):
              Audit.noteOfAction.locally(None):
                AuditContext.reset(initial):
                  ZIO.uninterruptibleMask: restore =>
                    // A call that names no valid actor is answered here and the handler is not run; the answer is recorded (a denial) like any
                    // other the route gives.
                    restore(if actor.named then handler(request) else refusal(actor)).exit.flatMap: exit =>
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
  ): URIO[AuditEvents & AuditTenants, Unit] =
    Audit.tenantOfTarget.get.zip(Audit.partnerOfTarget.get).zip(Audit.noteOfAction.get).flatMap((tenantOfTarget, partnerOfTarget, note) =>
      recordWith(pattern, status, interrupted, state, actor, change, read, tenantOfTarget, partnerOfTarget, note),
    )

  private def recordWith(
      pattern: String,
      status: Status,
      interrupted: Boolean,
      state: AuditContext.State,
      actor: Actor,
      change: Option[Entry.Change],
      read: Option[Entry.Read],
      tenantOfTarget: Option[String],
      partnerOfTarget: Option[String],
      note: Option[String],
  ): URIO[AuditEvents & AuditTenants, Unit] =
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
      partnerId = partnerOfTarget.map(clipped),
      note = note.map(clipped),
    )
    // What the tenant keeps no log of is not recorded, and is not counted as an event that lacked a target either.
    ZIO.serviceWithZIO[AuditTenants](tenants => tenantOfTarget.orElse(state.tenantId).fold(ZIO.succeed(true))(tenants.recording))
      .flatMap(ZIO.when(_)(recordWanted(status, interrupted, state, actor, change, read, details, resolvedTarget, succeeded)))
      .unit

  private def recordWanted(
      status: Status,
      interrupted: Boolean,
      state: AuditContext.State,
      actor: Actor,
      change: Option[Entry.Change],
      read: Option[Entry.Read],
      details: AdminDetails,
      resolvedTarget: Option[(String, String)],
      succeeded: Boolean,
  ): URIO[AuditEvents, Unit] =
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
        case (_, Some(entry)) => ZIO.when(succeeded)(events.recordBestEffort(draft(entry.kind, AuditOutcome.success, None))).unit
        case _ => ZIO.unit

  /** The outcome of a status, and the reason of one that is not a success: the error code the request set, else its status. */
  private def outcomeOf(status: Status, interrupted: Boolean): UIO[(AuditOutcome, Option[String])] =
    if interrupted then ZIO.succeed((AuditOutcome.failure, Some("interrupted")))
    else if status.isSuccess || status.isRedirection then ZIO.succeed((AuditOutcome.success, None))
    else
      logContext.get.map(_.get(Observability.error).map(_.code)).map: code =>
        val reason = Some(code.getOrElse(s"http_${status.code}"))
        if status == Status.Unauthorized || status == Status.Forbidden then (AuditOutcome.denied, reason) else (AuditOutcome.failure, reason)

  private def queryValue(request: Request, name: String): Option[String] =
    request.url.queryParams.map.get(name).flatMap(_.headOption)

  /** What an administrator's action says about itself: the route (its pattern, a closed set), the status, the identifiers in the query, and the names of the fields an update
    * changed (never their values). `partnerId` is the partner the action is about (#962), the column the partner's feed is read by. */
  private case class AdminDetails(
      path: String,
      status: Int,
      changed: Option[List[String]],
      tenantId: Option[String],
      partnerId: Option[String],
      note: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    override def toJson: Json.Obj = AuditDetails.render(this)
