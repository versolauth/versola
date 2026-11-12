package versola.edge.partner

import versola.edge.model.{RefreshTokenFamilyId, SessionId}
import versola.edge.partner.PartnerCentral.{Answer, Failure}
import versola.edge.{EdgeConfig, EdgeService, SSOClient}
import versola.util.audit.AuditEvent
import versola.util.http.Observability
import zio.http.*
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}
import zio.{Chunk, Clock, Duration, IO, Schedule, Task, UIO, ZIO, durationInt}

import java.time.Instant
import java.util.UUID

/** `/partner/api`: the partner console's API, served by the partner's own edge (#549 decision 5, `partner-api.yaml`).
  *
  * Every route first establishes who is asking, in this order, and refuses at the first that fails:
  *
  * 1. the `EDGE_SESSION` cookie holds a live token ([[EdgeService.consoleSession]]), else `401 unauthenticated` (the console goes to
  *    `/partner/login`);
  * 2. that token is one of the partner's console: issued to the console client for the console's preset, else `401` (a session of
  *    one of the partner's own applications is not a staff session);
  * 3. the person is on the team ([[StaffService.admit]]), else `403 not_a_member` with the masked identifier they signed in with;
  * 4. their level reaches the route's, else `403 insufficient_level`.
  *
  * What is then local is answered here (`me`, the team); the rest is a call to `/edge/partner` of central with the staff access
  * token forwarded as the principal ([[PartnerCentral]]), translated to and from the console's shapes. A change of the console's
  * shapes (names of clients are one string there and a map of locales in central, a resource is replaced as a whole there and
  * patched in central) is done here and nowhere else.
  */
final class PartnerApi(
    edgeService: EdgeService,
    staff: StaffService,
    state: PartnerState,
    central: PartnerCentral,
    config: EdgeConfig,
):
  import PartnerApi.*

  def routes: Routes[Any, Throwable] =
    Routes(
      Method.GET / "partner" / "api" / "me" -> endpoint(StaffLevel.viewer)(me),
      // clients
      Method.GET / "partner" / "api" / "clients" -> endpoint(StaffLevel.viewer)(listClients),
      Method.POST / "partner" / "api" / "clients" -> endpoint(StaffLevel.developer)(createClient),
      Method.PUT / "partner" / "api" / "clients" / string("id") -> endpointWith(StaffLevel.developer)(updateClient),
      Method.DELETE / "partner" / "api" / "clients" / string("id") -> endpointWith(StaffLevel.developer)(deleteClient),
      Method.POST / "partner" / "api" / "clients" / string("id") / "withdraw" -> endpointWith(StaffLevel.developer)(withdrawClient),
      Method.POST / "partner" / "api" / "clients" / string("id") / "rotate-secret" -> endpointWith(StaffLevel.developer)(rotateClientSecret),
      // resources
      Method.GET / "partner" / "api" / "resources" -> endpoint(StaffLevel.viewer)(listResources),
      Method.POST / "partner" / "api" / "resources" -> endpoint(StaffLevel.developer)(createResource),
      Method.PUT / "partner" / "api" / "resources" / string("id") -> endpointWith(StaffLevel.developer)(updateResource),
      Method.DELETE / "partner" / "api" / "resources" / string("id") -> endpointWith(StaffLevel.developer)(deleteResource),
      Method.POST / "partner" / "api" / "resources" / string("id") / "rotate-secret" -> endpointWith(StaffLevel.developer)(rotateResourceSecret),
      // edges
      Method.GET / "partner" / "api" / "edges" -> endpoint(StaffLevel.viewer)(listEdges),
      Method.POST / "partner" / "api" / "edges" -> endpoint(StaffLevel.developer)(createEdge),
      Method.DELETE / "partner" / "api" / "edges" / string("id") -> endpointWith(StaffLevel.developer)(deleteEdge),
      Method.POST / "partner" / "api" / "edges" / string("id") / "enrollment-tokens" -> endpointWith(StaffLevel.developer)(issueToken),
      Method.DELETE / "partner" / "api" / "edges" / string("id") / "enrollment-tokens" -> endpointWith(StaffLevel.developer)(revokeToken),
      // revocations: through central, never edge-local
      Method.POST / "partner" / "api" / "revocations" -> endpoint(StaffLevel.admin)(revoke),
      Method.GET / "partner" / "api" / "revocations" -> endpoint(StaffLevel.admin)(listRevocations),
      // audit
      Method.GET / "partner" / "api" / "audit" -> endpoint(StaffLevel.viewer)(audit),
      // team: local
      Method.GET / "partner" / "api" / "staff" -> endpoint(StaffLevel.admin)(listStaff),
      Method.POST / "partner" / "api" / "staff" -> endpoint(StaffLevel.admin)(inviteStaff),
      Method.PUT / "partner" / "api" / "staff" / string("id") -> endpointWith(StaffLevel.admin)(updateStaff),
      Method.DELETE / "partner" / "api" / "staff" / string("id") -> endpointWith(StaffLevel.admin)(removeStaff),
      // organisation
      Method.GET / "partner" / "api" / "settings" -> endpoint(StaffLevel.admin)(getSettings),
      Method.PUT / "partner" / "api" / "settings" -> endpoint(StaffLevel.admin)(putSettings),
      Method.GET / "partner" / "api" / "profile" -> endpoint(StaffLevel.admin)(getProfile),
      Method.PUT / "partner" / "api" / "profile" -> endpoint(StaffLevel.admin)(putProfile),
    )

  // ── who is asking ────────────────────────────────────────────────────────────────────────────

  private case class Session(console: EdgeService.ConsoleSession, member: StaffMember, me: PartnerMe):
    def sub: String = console.claims.subject
    def principal: String = console.accessToken.toString

  private type Run = (Session, Request) => IO[Fail | Throwable, Response]

  private def endpoint(level: StaffLevel)(run: Run): Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request](request => guarded(level, request)(run))

  private def endpointWith(level: StaffLevel)(run: (
      String,
      Session,
      Request,
  ) => IO[Fail | Throwable, Response]): Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)]((id, request) => guarded(level, request)((session, request) => run(id, session, request)))

  private def guarded(level: StaffLevel, request: Request)(run: Run): UIO[Response] =
    (for
      _ <- sameOrigin(request)
      console <- consoleOf(request)
      // the cookie of a refresh is set on whatever the answer is, a refusal of the person included: the refresh token it replaced is
      // spent, so a browser left with the old cookie would be sent to sign in again for a call that merely failed
      response <- (for
        session <- memberOf(console)
        _ <- ZIO.fail(Fail(Status.Forbidden, "insufficient_level", extra = List("required" -> Json.Str(level.toString))))
          .unless(session.member.level.atLeast(level))
          .tapError(_ => Observability.setError("insufficient_level"))
        // a partner central has suspended changes nothing: central refuses it, and so does the edge, which may not have asked yet
        _ <- ZIO.fail(Fail(Status.Forbidden, "partner_suspended"))
          .when(request.method != Method.GET && session.me.partner.status == "suspended")
          .tapError(_ => Observability.setError("partner_suspended"))
        response <- run(session, request)
      yield response).catchAll(failure(_)).map(response => console.rotatedCookie.fold(response)(response.addCookie))
    yield response)
      .catchAll(failure(_))
      .tapErrorCause(cause => ZIO.logErrorCause("partner api", cause))
      .catchAllCause(_ => ZIO.succeed(json(Status.InternalServerError, Json.Obj("error" -> Json.Str("internal_error")))))

  /** A state-changing request from another origin is not the console's: the cookie is `SameSite=Strict`, which already keeps it from
    * being sent, and this refuses what a browser that ignored that would send. A request with no `Origin` is not a browser's. */
  private def sameOrigin(request: Request): IO[Fail, Unit] =
    val mutating = request.method != Method.GET && request.method != Method.HEAD
    val origin = request.header(Header.Origin).map {
      case Header.Origin.Value(scheme, host, port) => s"$scheme://$host${port.fold("")(p => s":$p")}"
      case _ => "null"
    }
    val own = config.edgeUrl.scheme.fold("")(_.encode) + "://" + config.edgeUrl.host.getOrElse("") + config.edgeUrl.port.filterNot(p =>
      p == 443 || p == 80,
    ).fold("")(p => s":$p")
    ZIO.fail(Fail(Status.Forbidden, "origin_mismatch")).when(mutating && origin.exists(_ != own)).tapError(_ =>
      Observability.setError("origin_mismatch"),
    ).unit

  private def consoleOf(request: Request): IO[Fail | Throwable, EdgeService.ConsoleSession] =
    edgeService.consoleSession(request).catchAll:
      case noSession: EdgeService.NoSession => unauthenticated(noSession.clear)
      case error: Throwable => ZIO.fail(error)

  private def memberOf(console: EdgeService.ConsoleSession): IO[Fail | Throwable, Session] =
    for
      me <- state.me
      // a session of one of the partner's own applications (an end user of its clients) shares the cookie, and is not staff
      _ <- unauthenticated(None).unless(console.presetId.toString == me.console.presetId && console.claims.clientId.toString == me.console.clientId)
        .tapError(_ => Observability.setError("console_client_mismatch"))
      admission <- staff.admit(console.claims.subject, userInfo(console)).catchSome:
        // the operator no longer honours the token (revoked a moment ago, or signed out elsewhere): the session is over
        case UserInfoRefused => unauthenticated(None)
      member <- admission match
        case StaffService.Admission.Member(member) => ZIO.succeed(member)
        case StaffService.Admission.NotAMember(masked) =>
          ZIO.fail(
            Fail(
              Status.Forbidden,
              "not_a_member",
              extra = List("identifier" -> Json.Str(masked.getOrElse("")), "partner" -> Json.Str(me.partner.displayName)),
            ),
          )
    yield Session(console, member, me)

  private def unauthenticated[A](clear: Option[zio.http.Cookie.Response]): IO[Fail, A] =
    ZIO.fail(Fail(Status.Unauthorized, "unauthenticated", extra = List("loginPath" -> Json.Str(LoginPath)), clear = clear))

  private def userInfo(console: EdgeService.ConsoleSession): IO[Throwable, Json.Obj] =
    edgeService.consoleUserInfo(console).catchAll:
      case SSOClient.UserInfoUnauthorized => ZIO.fail(UserInfoRefused)
      case error: Throwable => ZIO.fail(error)

  // ── me ───────────────────────────────────────────────────────────────────────────────────────

  private def me(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      reachable <- state.centralReachable
      partner = session.me.partner
      operator = session.me.operator
    yield ok(
      Json.Obj(
        "partner" -> Json.Obj(
          "id" -> Json.Str(partner.id),
          "displayName" -> Json.Str(partner.displayName),
          "logoUri" -> optional(partner.logoUri),
          "status" -> Json.Str(partner.status),
          "limits" -> partner.limits,
          "reviewAt" -> optional(partner.reviewAt),
          "operator" -> Json.Obj(
            "name" -> Json.Str(operator.name),
            "issuer" -> Json.Str(operator.issuer.getOrElse("")),
            "mtlsUrl" -> Json.Str(operator.mtlsUrl.getOrElse("")),
            "centralMtlsUrl" -> Json.Str(operator.centralMtlsUrl.getOrElse("")),
          ),
        ),
        "staff" -> Json.Obj(
          "sub" -> Json.Str(session.member.subject),
          "display" -> Json.Str(session.member.display),
          "level" -> Json.Str(session.member.level.toString),
        ),
        "edge" -> Json.Obj(
          "id" -> Json.Str(session.me.edge.id),
          "publicUrl" -> Json.Str(session.me.edge.publicUrl.getOrElse("")),
          "central" -> Json.Str(if reachable then "ok" else "unavailable"),
        ),
        "console" -> Json.Obj("clientId" -> Json.Str(session.me.console.clientId), "presetId" -> Json.Str(session.me.console.presetId)),
      ),
    )

  // ── clients ──────────────────────────────────────────────────────────────────────────────────

  private def listClients(session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.GET, List("clients")).map: answer =>
      ok(Json.Obj("clients" -> Json.Arr(arrayOf(answer, "clients").map(client => clientDto(client, session.me))*)))

  private def clientOf(session: Session, id: String): IO[Fail | Throwable, Json.Obj] =
    forward(session, Method.GET, List("clients"), query = Map("id" -> id)).flatMap: answer =>
      ZIO.fromOption(answer.obj).orElseFail(Fail(Status.BadGateway, "central_unreadable"))

  private def createClient(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      payload <- ZIO.fromEither(clientForCentral(body)).mapError(reason => Fail(Status.BadRequest, "invalid_request", message = Some(reason)))
      id <- ZIO.fromOption(str(body, "id")).orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("id is missing")))
      answer <- forward(session, Method.POST, List("clients"), body = Some(payload)).map(Some(_)).catchSome:
        // central answers the creation with the client it reads back from a cache that is refreshed after the commit, so for a moment
        // it can answer 404 for the client it has just registered: the client is read until it is there
        case Fail(Status.NotFound, _, _, _, _, _) => ZIO.none
      created <- answer.flatMap(_.obj) match
        case Some(created) => ZIO.succeed(created)
        case None => clientOf(session, id).retry(Schedule.spaced(200.millis) && Schedule.recurs(15))
    yield json(Status.Accepted, clientDto(created, session.me))

  private def updateClient(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      _ <- ZIO.fail(Fail(Status.BadRequest, "invalid_request", message = Some("the id of the path and of the body differ")))
        .when(str(body, "id").exists(_ != id))
      current <- clientOf(session, id)
      update = clientUpdateForCentral(id, body, current)
      answer <- forward(session, Method.PUT, List("clients"), body = Some(update))
      // `202`: something waits for the operator; the answer names it. `204`: it applied, and the client is read back.
      response <-
        if answer.status == Status.Accepted then
          ZIO.fromOption(answer.obj).orElseFail(Fail(Status.BadGateway, "central_unreadable")).map(client =>
            json(Status.Accepted, clientDto(client, session.me)),
          )
        else clientOf(session, id).map(client => ok(clientDto(client, session.me)))
    yield response

  private def deleteClient(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.DELETE, List("clients"), query = Map("id" -> id)).as(Response.status(Status.NoContent))

  private def withdrawClient(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      before <- clientOf(session, id)
      _ <- forward(session, Method.POST, List("clients", "withdraw"), body = Some(Json.Obj("id" -> Json.Str(id))))
      after <- clientOf(session, id).map(Some(_)).catchSome { case Fail(Status.NotFound, _, _, _, _, _) => ZIO.none }
    yield after match
      case Some(client) => ok(clientDto(client, session.me))
      // a client that was never approved is gone: what it was is returned, rejected
      case None => ok(clientDto(withApprovalStatus(before, "rejected"), session.me))

  /** A partner client has no client secret: its credential is the group's certificate, which central issues and this edge renews, or
    * keys the partner holds. There is nothing for this to rotate. */
  private def rotateClientSecret(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    ZIO.fail(Fail(
      Status.Conflict,
      "not_supported",
      message = Some("a partner client authenticates with the group's certificate: it has no secret to rotate"),
    ))

  // ── resources ────────────────────────────────────────────────────────────────────────────────

  private def listResources(session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.GET, List("resources")).map: answer =>
      ok(Json.Obj("resources" -> Json.Arr(arrayOf(answer, "resources").map(resourceDto)*)))

  private def resourcesOf(session: Session): IO[Fail | Throwable, List[Json.Obj]] =
    forward(session, Method.GET, List("resources")).map(answer => arrayOf(answer, "resources").toList)

  private def createResource(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      payload = Json.Obj(
        "resourceId" -> field(body, "resourceId").getOrElse(Json.Null),
        "resource" -> field(body, "resource").getOrElse(Json.Null),
        "audience" -> field(body, "audience").getOrElse(Json.Arr()),
        "endpoints" -> Json.Arr(arrayField(body, "endpoints").map(endpointForCentral)*),
        "audienceRateLimits" -> Json.Arr(),
      )
      answer <- forward(session, Method.POST, List("resources"), body = Some(payload))
      secret = answer.obj.flatMap(str(_, "secret"))
      id = answer.obj.flatMap(str(_, "resourceId")).orElse(str(body, "resourceId"))
      resources <- resourcesOf(session)
      created <-
        ZIO.fromOption(resources.find(resource => str(resource, "resourceId") == id)).orElseFail(Fail(Status.BadGateway, "central_unreadable"))
    yield json(Status.Created, Json.Obj("resource" -> resourceDto(created), "secret" -> optional(secret)))

  private def updateResource(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      _ <- ZIO.fail(Fail(Status.BadRequest, "invalid_request", message = Some("the id of the path and of the body differ")))
        .when(str(body, "resourceId").exists(_ != id))
      resources <- resourcesOf(session)
      current <- ZIO.fromOption(resources.find(resource => str(resource, "resourceId").contains(id))).orElseFail(Fail(Status.NotFound, "not_found"))
      _ <- forward(session, Method.PUT, List("resources"), body = Some(resourceUpdateForCentral(id, body, current)))
      updated <- resourcesOf(session).map(_.find(resource => str(resource, "resourceId").contains(id)))
      resource <- ZIO.fromOption(updated).orElseFail(Fail(Status.BadGateway, "central_unreadable"))
    yield ok(resourceDto(resource))

  private def deleteResource(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.DELETE, List("resources"), query = Map("resourceId" -> id)).as(Response.status(Status.NoContent))

  private def rotateResourceSecret(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.POST, List("resources", "rotate-secret"), body = Some(Json.Obj("resourceId" -> Json.Str(id)))).map: answer =>
      ok(Json.Obj("secret" -> answer.obj.flatMap(str(_, "secret")).fold[Json](Json.Null)(Json.Str(_))))

  // ── edges ────────────────────────────────────────────────────────────────────────────────────

  private def listEdges(session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.GET, List("edges")).map: answer =>
      ok(Json.Obj("edges" -> Json.Arr(arrayOf(answer, "edges").map(edge => edgeDto(edge, session.me))*)))

  private def createEdge(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      payload = Json.Obj("id" -> field(body, "id").getOrElse(Json.Null), "publicUrl" -> field(body, "publicUrl").getOrElse(Json.Null))
      answer <- forward(session, Method.POST, List("edges"), body = Some(payload))
      created <- ZIO.fromOption(answer.obj).orElseFail(Fail(Status.BadGateway, "central_unreadable"))
    yield json(
      Status.Created,
      Json.Obj(
        "edge" -> edgeDto(obj(created, "edge"), session.me),
        "token" -> field(created, "token").getOrElse(Json.Null),
        "expiresAt" -> field(created, "expiresAt").getOrElse(Json.Null),
      ),
    )

  private def deleteEdge(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.DELETE, List("edges"), query = Map("id" -> id)).as(Response.status(Status.NoContent))

  /** A token for an edge that exists, which is all there is to start it with: central's answer is the token and its expiry. */
  private def issueToken(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.POST, List("edges", "enrollment-tokens"), body = Some(Json.Obj("id" -> Json.Str(id)))).map: answer =>
      val issued = answer.obj.getOrElse(Json.Obj())
      json(
        Status.Created,
        Json.Obj(
          "token" -> field(issued, "token").getOrElse(Json.Null),
          "expiresAt" -> field(issued, "expiresAt").getOrElse(Json.Null),
        ),
      )

  private def revokeToken(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(
      session,
      Method.DELETE,
      List("edges", "enrollment-tokens"),
      body = Some(Json.Obj("id" -> Json.Str(id))),
    ).as(Response.status(Status.NoContent))

  // ── revocations, audit ───────────────────────────────────────────────────────────────────────

  /** Ends sessions on this edge: every session of a user (`by = user`, the `sub`), one SSO session (`sid`) or the sessions of one refresh-token
    * family (`fam`). Local to the edge: its own revocation list and session rows, nothing is asked of central or auth, so it works while
    * central is down. It only reaches sessions this edge holds, which are the partner's own applications' (and the console's). `ended` is how
    * many live sessions of the edge it ended. `clients` and `edges` of the console's form are not used: there is one edge here, and the
    * store holds the sessions of this partner's clients only. */
  private def revoke(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      value <- ZIO.fromOption(str(
        body,
        "value",
      ).filter(_.nonEmpty)).orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("value is missing")))
      by <- ZIO.fromOption(str(body, "by")).orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("by must be user, sid or fam")))
      what <- by match
        case "user" => ZIO.succeed(EdgeService.SessionRevocation.User(value))
        case "sid" => ZIO.succeed(EdgeService.SessionRevocation.Session(SessionId(value)))
        case "fam" => ZIO.succeed(EdgeService.SessionRevocation.Family(RefreshTokenFamilyId(value)))
        case _ => ZIO.fail(Fail(Status.BadRequest, "invalid_request", message = Some("by must be user, sid or fam")))
      ended <- edgeService.revokeSessions(what)
      now <- Clock.instant
      _ <- staff.recordRevocation(session.sub, by, value, ended, now)
    yield ok(Json.Obj("ended" -> Json.Num(ended)))

  /** The revocations the partner's administrators made on this edge, newest first (the edge's own record of them). */
  private def listRevocations(session: Session, request: Request): IO[Fail | Throwable, Response] =
    staff.events(None, None, AuditPage).map: events =>
      val revocations = events.filter(_.action == "revoke").map: event =>
        val (by, ended) = event.detail.getOrElse(":0").split(":", 2).toList match
          case by :: ended :: Nil => by -> ended.toIntOption.getOrElse(0)
          case _ => "" -> 0
        Json.Obj(
          "id" -> Json.Str(event.id.toString),
          "at" -> Json.Str(event.at.toString),
          "by_staff" -> Json.Str(event.actor),
          "by" -> Json.Str(by),
          "value" -> Json.Str(event.target),
          "clients" -> Json.Arr(),
          "edges" -> Json.Arr(),
          "ended" -> Json.Num(ended),
        )
      ok(Json.Obj("revocations" -> Json.Arr(revocations*)))

  /** The partner's trail: central's events (what its staff did through its edges and what the operator decided) with the team's own,
    * which only the edge holds. The console asks by group (`kind` clients, resources, edges, team, revocations, security; `actor` team,
    * operator, edges), which central does not know: it matches one kind and one actor exactly. So the edge reads central's pages and
    * keeps the events of the group, and lays the team's events among them by time. Anything else is passed to central as it is. */
  private def audit(session: Session, request: Request): IO[Fail | Throwable, Response] =
    def param(name: String) = request.url.queryParams.getAll(name).headOption.filter(_.nonEmpty)
    val kind = param("kind")
    val actor = param("actor")
    val q = param("q")
    val cursor = param("cursor").map(AuditCursor.decode)
    // the team's and the revocations' events are the edge's own: central is not asked for those groups
    val teamOnly = kind.exists(Set(TeamKind, "revocations"))
    val localKind: TeamEvent => Boolean = kind match
      case Some(TeamKind) => _.action != "revoke"
      case Some("revocations") => _.action == "revoke"
      case None => _ => true
      case Some(_) => _ => false
    val localWanted = (kind.isEmpty || teamOnly) && actor.forall(a => a == "team" || !Set("operator", "edges").contains(a))
    val central = kind.filter(_ => !teamOnly).flatMap(AuditGroups.kinds)
    for
      displays <- staff.list.map(_.flatMap(entry => entry.subject.map(_ -> entry.display.getOrElse(entry.subject.getOrElse("")))).toMap)
      fetched <-
        if teamOnly then ZIO.succeed((Chunk.empty[Json.Obj], Option.empty[String], Option.empty[Instant]))
        else readCentral(session, cursor.flatMap(_.central), kind, actor, q, central)
      (events, next, oldest) = fetched
      before = cursor.flatMap(_.boundary)
      // the team's events between where the page of central's begins and where the one before it ended; the last page reaches back to the start
      lower = if next.isDefined then oldest else None
      local <-
        if !localWanted then ZIO.succeed(Nil)
        else
          staff.events(lower, before, AuditPage).map(_.filter(localKind).filter(event =>
            q.forall(text => event.target.contains(text) || event.action.contains(text)),
          ))
      localDtos = local.map(event => (event.at, localEventDto(event, displays)))
      centralDtos = events.toList.map(event => (Instant.parse(str(event, "at").getOrElse("1970-01-01T00:00:00Z")), auditEventDto(event, displays)))
      merged = (centralDtos ++ localDtos).sortBy(_._1)(using Ordering[Instant].reverse).map(_._2)
      nextCursor =
        if teamOnly then Option.when(local.size == AuditPage)(AuditCursor.encode(AuditCursor(None, local.lastOption.map(_.at))))
        else next.map(c => AuditCursor.encode(AuditCursor(Some(c), oldest)))
    yield ok(Json.Obj("events" -> Json.Arr(merged*), "next" -> nextCursor.fold[Json](Json.Null)(Json.Str(_))))

  /** Pages of central's audit until a page's worth of the group is found, or [[AuditPages]] pages were read. `next` is where to go on from,
    * `oldest` the time of the oldest event read (what the team's events are laid against). */
  private def readCentral(
      session: Session,
      from: Option[String],
      kind: Option[String],
      actor: Option[String],
      q: Option[String],
      kinds: Option[List[String]],
  ): IO[Fail | Throwable, (Chunk[Json.Obj], Option[String], Option[Instant])] =
    // a group is every kind of central's it names, an unknown `kind` is central's own exact one
    val exact = kind.filter(_ => kinds.isEmpty)
    def loop(
        cursor: Option[String],
        pages: Int,
        found: Chunk[Json.Obj],
        oldest: Option[Instant],
    ): IO[Fail | Throwable, (Chunk[Json.Obj], Option[String], Option[Instant])] =
      val query = List(
        cursor.map("cursor" -> _),
        exact.map("kind" -> _),
        q.map("q" -> _),
        actor.filter(a => !Set("team", "operator", "edges").contains(a)).map("actor" -> _),
      ).flatten.toMap
      forward(session, Method.GET, List("audit"), query = query).flatMap: answer =>
        val page = arrayOf(answer, "events")
        val next = answer.obj.flatMap(str(_, "next"))
        // the operator's reveal of the bundle secret at the partner's creation is the operator's own business, not an event of the partner's trail
        val kept = page.filter(event => !str(event, "kind").contains("admin.secret.revealed")).filter(event =>
          kinds.forall(ks => str(event, "kind").exists(k => ks.exists(k.contains))) && actor.forall(a => AuditGroups.actor(a, event)),
        )
        val times = page.flatMap(event => str(event, "at").flatMap(at => scala.util.Try(Instant.parse(at)).toOption))
        val lowest = (oldest.toList ++ times.lastOption.toList).minOption
        val all = found ++ kept
        if next.isEmpty || all.size >= AuditPage || pages + 1 >= AuditPages then ZIO.succeed((all, next, lowest))
        else loop(next, pages + 1, all, lowest)
    loop(from, 0, Chunk.empty, None)

  private def localEventDto(event: TeamEvent, displays: Map[String, String]): Json.Obj =
    val what = event.action match
      case "invite" => s"Invited ${event.target} as ${event.detail.getOrElse("")}".trim
      case "level" => s"Made ${displays.getOrElse(event.target, event.target)} ${event.detail.getOrElse("")}".trim
      case "remove" => s"Removed ${displays.getOrElse(event.target, event.target)} from the team"
      case "admit" => s"${event.target} joined the team as ${event.detail.getOrElse("")}".trim
      case "revoke" =>
        val (by, ended) = event.detail.getOrElse(":0").split(":", 2).toList match
          case by :: ended :: Nil => by -> ended
          case _ => "" -> "0"
        s"Ended $ended session(s) by $by ${event.target}"
      case other => other
    Json.Obj(
      "id" -> Json.Str(event.id.toString),
      "at" -> Json.Str(event.at.toString),
      "kind" -> Json.Str(s"team.${event.action}"),
      "actor" -> Json.Obj(
        "sub" -> Json.Str(event.actor),
        "display" -> Json.Str(if event.actor == StaffService.ByConfiguration then "Configuration" else displays.getOrElse(event.actor, event.actor)),
      ),
      "action" -> Json.Str(what),
      "target" -> Json.Str(event.target),
      "outcome" -> Json.Str("ok"),
    )

  // ── the team ─────────────────────────────────────────────────────────────────────────────────

  private def listStaff(session: Session, request: Request): IO[Fail | Throwable, Response] =
    staff.list.map(entries => ok(Json.Obj("staff" -> Json.Arr(entries.map(staffDto)*))))

  private def inviteStaff(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      identifier <-
        ZIO.fromOption(str(body, "identifier")).orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("identifier is missing")))
      level <- levelOf(body)
      requested = field(body, "expiresInDays").filter(_ != Json.Null)
      days <- requested match
        case None => ZIO.none
        case Some(Json.Num(value)) if value.intValue == value.doubleValue && value.intValue >= 1 && value.intValue <= 90 => ZIO.some(value.intValue)
        case Some(_) =>
          ZIO.fail(Fail(Status.BadRequest, "invalid_request", message = Some("expiresInDays must be a whole number of days from 1 to 90")))
      entry <- staff.invite(session.sub, identifier, level, days.map(d => Duration.fromSeconds(d.toLong * 86400))).mapError(staffFailure)
    yield json(Status.Created, staffDto(entry))

  private def updateStaff(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      level <- levelOf(body)
      _ <- staff.setLevel(session.sub, id, level).mapError(staffFailure)
      entries <- staff.list
      entry <- ZIO.fromOption(entries.find(_.id == id)).orElseFail(Fail(Status.NotFound, "not_found"))
    yield ok(staffDto(entry))

  private def removeStaff(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    staff.remove(session.sub, id).mapError(staffFailure).as(Response.status(Status.NoContent))

  private def levelOf(body: Json.Obj): IO[Fail, StaffLevel] =
    ZIO.fromOption(str(body, "level").flatMap(name => StaffLevel.values.find(_.toString == name)))
      .orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("level must be admin, developer or viewer")))

  private def staffFailure(error: StaffService.Error | Throwable): Fail | Throwable =
    error match
      case StaffService.Error.NotFound(what) => Fail(Status.NotFound, "not_found", message = Some(what))
      case StaffService.Error.Invalid(reason) => Fail(Status.BadRequest, "invalid_request", message = Some(reason))
      case StaffService.Error.Conflict(reason) => Fail(Status.Conflict, "conflict", message = Some(reason))
      case error: Throwable => error

  // ── organisation ─────────────────────────────────────────────────────────────────────────────

  private def getSettings(session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.GET, List("settings")).map(answer => ok(answer.obj.getOrElse(Json.Obj())))

  private def putSettings(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      // only what the console edits is sent on, key by key: nothing else the request carries reaches central
      only = (value: Option[Json], names: List[String]) =>
        value.flatMap(_.asObject).map(obj => Json.Obj(obj.fields.filter((name, _) => names.contains(name)))).getOrElse(Json.Obj())
      payload = Json.Obj(
        List(
          "contact" -> only(field(body, "contact"), List("email", "phone")),
          "defaults" -> only(field(body, "defaults"), List("backChannelLogoutUri", "accessTokenTtl", "refreshTokenTtl")),
        )
          .filter((name, _) => field(body, name).isDefined)*,
      )
      _ <- forward(session, Method.PUT, List("settings"), body = Some(payload))
    yield Response.status(Status.NoContent)

  /** What waits for the operator is not kept by central's contract for the partner to read back (the answer of the change itself is
    * the only place it is told): `null`, until central serves it. */
  private def getProfile(session: Session, request: Request): IO[Fail | Throwable, Response] =
    ZIO.succeed(ok(Json.Obj("approval" -> Json.Null)))

  private def putProfile(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      payload = Json.Obj("displayName" -> field(body, "displayName").getOrElse(Json.Null), "logoUri" -> field(body, "logoUri").getOrElse(Json.Null))
      answer <- forward(session, Method.PUT, List("profile"), body = Some(payload))
      approval = answer.obj.getOrElse(Json.Obj())
    yield json(
      Status.Accepted,
      Json.Obj(
        "approval" -> Json.Obj(
          "id" -> Json.Str(str(approval, "id").getOrElse("")),
          "status" -> Json.Str("waiting"),
          "requestedAt" -> field(approval, "requestedAt").getOrElse(Json.Null),
          "requested" -> payload,
        ),
      ),
    )

  // ── calls to central ─────────────────────────────────────────────────────────────────────────

  private def forward(
      session: Session,
      method: Method,
      path: List[String],
      query: Map[String, String] = Map.empty,
      body: Option[Json] = None,
  ): IO[Fail | Throwable, Answer] =
    central.call(method, path, query, body, Some(session.principal)).tapError {
      case Failure.Suspended => state.noteSuspended
      case _ => ZIO.unit
    }.mapError {
      case Failure.Unavailable => Fail(Status.ServiceUnavailable, "central_unavailable")
      case Failure.Suspended => Fail(Status.Forbidden, "partner_suspended")
      case Failure.PrincipalMismatch => Fail(Status.Forbidden, "partner_principal_mismatch")
      case error: Throwable => error
    }.flatMap: answer =>
      if answer.status.isSuccess then ZIO.succeed(answer)
      else
        val message = Some(answer.text)
        val (status, code) = answer.status match
          case Status.NotFound => (Status.NotFound, "not_found")
          case Status.Conflict => (Status.Conflict, "conflict")
          case Status.BadRequest => (Status.BadRequest, "invalid_request")
          case other => (Status.BadGateway, s"central_answered_${other.code}")
        Observability.setError(code, Some(s"central answered ${answer.status.code}")) *> ZIO.fail(Fail(status, code, message = message))

  private def failure(error: Fail | Throwable): IO[Throwable, Response] =
    error match
      case fail: Fail =>
        val body = Json.Obj(
          (List("error" -> (Json.Str(fail.code): Json)) ++
            Option.when(fail.fields.nonEmpty)("fields" -> Json.Arr(fail.fields.map(Json.Str(_))*)) ++
            Some("message" -> (Json.Str(fail.message.getOrElse(defaultMessage(fail.code))): Json)) ++ fail.extra)*,
        )
        // A 4xx or 5xx says why once, through the request's error context, unless the step that failed already did.
        val response = json(fail.status, body)
        ZIO.succeed(fail.clear.fold(response)(response.addCookie))
      case error: Throwable => ZIO.fail(error)

  private def jsonBody(request: Request): IO[Fail | Throwable, Json.Obj] =
    request.body.asString.mapError(identity).flatMap: text =>
      ZIO.fromEither(text.fromJson[Json.Obj]).mapError(_ =>
        Fail(Status.BadRequest, "invalid_request", message = Some("the body is not a JSON object")),
      )

object PartnerApi:
  private val AuditPage = 50
  private val AuditPages = 6
  private val TeamKind = "team"

  /** Where the console's groups of the audit trail are in central's: `kinds` are the substrings of its kinds, `actor` the test of the
    * actor of an event. A name that is not a group is central's own and is not mapped. */
  private[partner] object AuditGroups:
    private val groups: Map[String, List[String]] = Map(
      "clients" -> List(".partner-client.", ".partner-approval."),
      "resources" -> List(".partner-resource."),
      "edges" -> List(".partner-edge."),
      "security" -> List(".partner.", ".partner-approval."),
    )
    def kinds(kind: String): Option[List[String]] = groups.get(kind)
    def actor(name: String, event: Json.Obj): Boolean =
      val actorType = str(event, "actorType").getOrElse("")
      name match
        case "team" => actorType == "partner-staff"
        case "operator" => actorType != "partner-staff" && actorType != "edge"
        case "edges" => actorType == "edge"
        case _ => true

  /** Where the next page of the audit trail begins: central's own cursor, and the time the page before it began, which the team's events
    * of the pages in between are cut at. */
  private[partner] case class AuditCursor(central: Option[String], boundary: Option[Instant])
  private[partner] object AuditCursor:
    def encode(cursor: AuditCursor): String =
      java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
        s"${cursor.central.getOrElse("")}|${cursor.boundary.map(_.toString).getOrElse("")}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
      )
    def decode(raw: String): AuditCursor =
      scala.util.Try(String(
        java.util.Base64.getUrlDecoder.decode(raw),
        java.nio.charset.StandardCharsets.UTF_8,
      )).toOption.map(_.split("\\|", -1).toList) match
        case Some(central :: boundary :: Nil) =>
          AuditCursor(Some(central).filter(_.nonEmpty), scala.util.Try(Instant.parse(boundary)).toOption)
        case _ => AuditCursor(Some(raw), None)
  val LoginPath = "/partner/login"
  val LogoutPath = "/partner/logout"

  /** The token of the session is no longer good at the operator's `/userinfo`. */
  case object UserInfoRefused extends RuntimeException("the operator's /userinfo refused the session's access token")

  /** A refusal, with the body the console reads: `{error, fields?, message?, ...extra}`. */
  case class Fail(
      status: Status,
      code: String,
      fields: List[String] = Nil,
      message: Option[String] = None,
      extra: List[(String, Json)] = Nil,
      clear: Option[zio.http.Cookie.Response] = None,
  )

  /** What the console shows for a code it has no text of its own for (the codes beyond the five of #963). */
  private[partner] def defaultMessage(code: String): String =
    code match
      case "insufficient_level" => "Your level on the team does not allow this."
      case "not_a_member" => "You are not on this partner's team."
      case "unauthenticated" => "Your session ended. Sign in again."
      case "partner_suspended" => "The operator has suspended this organisation: nothing can be changed until it resumes it."
      case "partner_principal_mismatch" => "Central did not accept your session for this change. Sign in again."
      case "central_unavailable" => "Central is unavailable right now, so nothing was changed."
      case "origin_mismatch" => "The request did not come from the console."
      case "not_supported" => "Not supported for this client."
      case "conflict" => "This conflicts with what is already there."
      case "not_found" => "There is no such record."
      case _ => "The request was not accepted."

  private def json(status: Status, body: Json): Response =
    Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.application.json), Header.CacheControl.NoStore),
      body = Body.fromString(body.toJson),
    )

  private def ok(body: Json): Response = json(Status.Ok, body)

  private def optional(value: Option[String]): Json = value.fold[Json](Json.Null)(Json.Str(_))

  private def field(o: Json.Obj, name: String): Option[Json] = o.fields.collectFirst { case (`name`, value) => value }

  private def str(o: Json.Obj, name: String): Option[String] = field(o, name).flatMap(_.asString)

  private def obj(o: Json.Obj, name: String): Json.Obj = field(o, name).collect { case inner: Json.Obj => inner }.getOrElse(Json.Obj())

  private def arrayField(o: Json.Obj, name: String): Chunk[Json.Obj] =
    field(o, name).collect { case Json.Arr(elements) => elements.collect { case element: Json.Obj => element } }.getOrElse(Chunk.empty)

  private def arrayOf(answer: Answer, name: String): Chunk[Json.Obj] = answer.obj.fold(Chunk.empty[Json.Obj])(arrayField(_, name))

  private def without(o: Json.Obj, names: String*): Json.Obj = Json.Obj(o.fields.filterNot((name, _) => names.contains(name)))

  private def with_(o: Json.Obj, entries: (String, Json)*): Json.Obj =
    Json.Obj(without(o, entries.map(_._1)*).fields ++ Chunk.fromIterable(entries))

  // ── client shapes ────────────────────────────────────────────────────────────────────────────

  /** One string for the name on the console, the locale map on central: English if there is one, else any. */
  private[partner] def nameOf(value: Option[Json]): String =
    value match
      case Some(Json.Str(name)) => name
      case Some(names: Json.Obj) =>
        names.fields.collectFirst { case ("en", Json.Str(name)) => name }.orElse(names.fields.collectFirst { case (_, Json.Str(name)) =>
          name
        }).getOrElse("")
      case _ => ""

  /** A client as the console reads it: the name as one string, what is fixed for a partner's client written out, and the partner's
    * limits beside it for the fields' hints. `jwks` is not the console's to read. */
  private[partner] def clientDto(client: Json.Obj, me: PartnerMe): Json.Obj =
    with_(
      without(client, "jwks", "dpopBoundAccessTokens"),
      "clientName" -> Json.Str(nameOf(field(client, "clientName"))),
      "fixed" -> Json.Obj(
        "consent" -> Json.Bool(true),
        "flow" -> Json.Str("code+pkce+par"),
        "authMethod" -> Json.Str(str(client, "authMethod").getOrElse("tls_client_auth")),
        "group" -> Json.Str(me.partner.id),
      ),
      "limits" -> me.partner.limits,
    )

  private def withApprovalStatus(client: Json.Obj, status: String): Json.Obj =
    with_(client, "approval" -> with_(obj(client, "approval"), "status" -> Json.Str(status)))

  private val DefaultAuthMethod = "tls_client_auth"

  /** `POST /edge/partner/clients`: exactly the fields central takes (it refuses others), with the name as a locale map. */
  private[partner] def clientForCentral(body: Json.Obj): Either[String, Json.Obj] =
    for
      id <- str(body, "id").toRight("id is missing")
      kind <- str(body, "kind").filter(Set("web", "native")).toRight("kind must be web or native")
      name <- str(body, "clientName").filter(_.trim.nonEmpty).toRight("clientName is missing")
      redirectUris <- field(body, "redirectUris").toRight("redirectUris is missing")
      scope <- field(body, "scope").toRight("scope is missing")
      accessTokenTtl <- field(body, "accessTokenTtl").toRight("accessTokenTtl is missing")
    yield Json.Obj(
      "id" -> Json.Str(id),
      "kind" -> Json.Str(kind),
      "clientName" -> Json.Obj("en" -> Json.Str(name)),
      "logoUri" -> nullable(body, "logoUri"),
      "policyUri" -> nullable(body, "policyUri"),
      "tosUri" -> nullable(body, "tosUri"),
      "redirectUris" -> redirectUris,
      "scope" -> scope,
      "authMethod" -> Json.Str(str(body, "authMethod").getOrElse(DefaultAuthMethod)),
      "jwks" -> Json.Null,
      "accessTokenTtl" -> accessTokenTtl,
      "refreshTokenTtl" -> nullable(body, "refreshTokenTtl"),
      "backChannelLogoutUri" -> nullable(body, "backChannelLogoutUri"),
      "consentFlow" -> Json.Null,
    )

  private def nullable(o: Json.Obj, name: String): Json =
    field(o, name) match
      case Some(Json.Str(value)) if value.isBlank => Json.Null
      case Some(value) => value
      case None => Json.Null

  /** `PUT /edge/partner/clients`: only the fields the console's whole-client body changes against `current`. Central takes what is
    * named as a change, and answers a change of the name or the logo (what the consent screen shows) with an approval, so the
    * console sending the whole client must not make every edit one. */
  private[partner] def clientUpdateForCentral(id: String, body: Json.Obj, current: Json.Obj): Json.Obj =
    def changed(name: String): Option[Json] =
      field(body, name).filter(value => field(current, name).getOrElse(Json.Null) != value)
    def patch(name: String): Option[(String, Json)] =
      field(body, name).flatMap: value =>
        val now = value match
          case Json.Str(text) if text.isBlank => None
          case Json.Null => None
          case other => Some(other)
        val before = field(current, name).collect { case Json.Str(text) if text.nonEmpty => Json.Str(text) }
        Option.when(now != before)(name -> now.getOrElse(Json.Null))
    val newName = str(body, "clientName")
    val nameChange =
      newName.filter(name => name != nameOf(field(current, "clientName"))).map(name => "clientName" -> (Json.Obj("en" -> Json.Str(name)): Json))
    val sets = List("redirectUris", "scope").flatMap: name =>
      field(body, name).filter(value => !sameSet(field(current, name), value)).map(name -> _)
    val numbers = List("accessTokenTtl", "refreshTokenTtl").flatMap(name => changed(name).map(name -> _))
    Json.Obj(Chunk.fromIterable(List("id" -> (Json.Str(id): Json)) ++ nameChange ++ List(
      "logoUri",
      "policyUri",
      "tosUri",
      "backChannelLogoutUri",
    ).flatMap(patch) ++ sets ++ numbers))

  private def sameSet(a: Option[Json], b: Json): Boolean =
    def members(json: Json): Set[String] = json match
      case Json.Arr(elements) => elements.flatMap(_.asString).toSet
      case _ => Set.empty
    a.exists(members(_) == members(b))

  // ── resource shapes ──────────────────────────────────────────────────────────────────────────

  /** A partner resource as the console reads it: no `internal` (always true for a partner), no limits and no owner. */
  private[partner] def resourceDto(resource: Json.Obj): Json.Obj = without(resource, "internal", "partnerId", "audienceRateLimits")

  private val EndpointFields = List("id", "path", "method", "fetchUserInfo", "allow", "inject", "stepUpCondition", "stepUpAcr", "maxAge", "rateLimit")

  /** An endpoint as central takes it on create: its fields, an id made when the console's new one has none. */
  private[partner] def endpointForCentral(endpoint: Json.Obj): Json.Obj =
    val withId = if field(endpoint, "id").exists(_ != Json.Null) then endpoint else with_(endpoint, "id" -> Json.Str(UUID.randomUUID().toString))
    Json.Obj(
      Chunk.fromIterable(
        EndpointFields.map: name =>
          name -> field(withId, name).getOrElse(name match
            case "fetchUserInfo" => Json.Bool(false)
            case "inject" => Json.Arr()
            case _ => Json.Null),
      ),
    )

  /** `PUT /edge/partner/resources`: the console sends the resource whole, central takes a change. The audience becomes the clients
    * added and removed; an endpoint that is new, or is not what it was, is created (the one it replaces deleted). */
  private[partner] def resourceUpdateForCentral(id: String, body: Json.Obj, current: Json.Obj): Json.Obj =
    def names(json: Option[Json]): Set[String] = json.collect { case Json.Arr(elements) => elements.flatMap(_.asString).toSet }.getOrElse(Set.empty)
    val wanted = names(field(body, "audience"))
    val had = names(field(current, "audience"))
    val hadEndpoints = arrayField(current, "endpoints").map(endpoint => str(endpoint, "id").getOrElse("") -> endpointForCentral(endpoint)).toMap
    val wantedEndpoints = arrayField(body, "endpoints").map(endpointForCentral)
    val created = wantedEndpoints.filter(endpoint => !hadEndpoints.get(str(endpoint, "id").getOrElse("")).contains(endpoint))
    val wantedIds = wantedEndpoints.flatMap(str(_, "id")).toSet
    val removed = hadEndpoints.keySet.filter(existing => !wantedIds.contains(existing) || created.exists(str(_, "id").contains(existing)))
    Json.Obj(
      "resourceId" -> Json.Str(id),
      "resource" -> (field(body, "resource").filter(_ != field(current, "resource").getOrElse(Json.Null)).getOrElse(Json.Null)),
      "audience" -> Json.Obj(
        "add" -> Json.Arr((wanted -- had).toList.sorted.map(Json.Str(_))*),
        "remove" -> Json.Arr((had -- wanted).toList.sorted.map(Json.Str(_))*),
      ),
      "deleteEndpoints" -> Json.Arr(removed.toList.sorted.map(Json.Str(_))*),
      "createEndpoints" -> Json.Arr(created*),
      "audienceRateLimits" -> Json.Null,
    )

  // ── edges, audit, staff shapes ───────────────────────────────────────────────────────────────

  /** `EdgeResponse` as the console reads it: enrolled once central issued it a certificate; the group's policies read-only. */
  private[partner] def edgeDto(edge: Json.Obj, me: PartnerMe): Json.Obj =
    val enrolled = field(edge, "certificateNotAfter").exists(_ != Json.Null)
    val status = if me.partner.status == "suspended" then "suspended" else if enrolled then "enrolled" else "waiting"
    Json.Obj(
      Chunk.fromIterable(
        List[(String, Json)](
          "id" -> Json.Str(str(edge, "id").getOrElse("")),
          "publicUrl" -> Json.Str(str(edge, "publicUrl").getOrElse("")),
          "status" -> Json.Str(status),
          "certificateNotAfter" -> field(edge, "certificateNotAfter").getOrElse(Json.Null),
          "lastRenewedAt" -> field(edge, "lastRenewedAt").getOrElse(Json.Null),
          // central does not count the replicas behind a group's edge
          "replicas" -> Json.Num(0),
          "policies" -> Json.Obj(
            "overload" -> field(edge, "overloadPolicy").filter(_ != Json.Null).getOrElse(Json.Obj()),
            "accessLog" -> field(edge, "accessLogPolicy").filter(_ != Json.Null).getOrElse(Json.Obj()),
            "rateLimits" -> field(edge, "rateLimitPolicies").filter(_ != Json.Null).getOrElse(Json.Obj()),
          ),
        ),
      ),
    )

  private val Nouns = Map(
    "partner-client" -> "client",
    "partner-resource" -> "resource",
    "partner-edge" -> "edge",
    "partner-revocation" -> "revocation",
    "partner-approval" -> "approval",
    "partner" -> "organisation",
  )
  private val Verbs = Map(
    "create" -> "Created",
    "update" -> "Changed",
    "delete" -> "Deleted",
    "withdraw" -> "Withdrew",
    "approve" -> "Approved",
    "reject" -> "Rejected",
    "suspend" -> "Suspended",
    "resume" -> "Resumed",
    "rotate-secret" -> "Rotated the secret of",
    "enrollment-tokens" -> "Changed the enrollment token of",
    "jwks" -> "Replaced the keys of",
  )

  /** The words for `admin.<resource>.<operation>`; the kind itself where this does not know it. */
  private[partner] def actionOf(kind: String): String =
    kind.split('.').toList match
      case "admin" :: "secret" :: "revealed" :: Nil => "Revealed a secret"
      case "admin" :: resource :: operation :: Nil if Nouns.contains(resource) && Verbs.contains(operation.stripPrefix("action:")) =>
        if resource == "partner-revocation" && operation == "create" then "Ended grants of clients"
        else s"${Verbs(operation.stripPrefix("action:"))} ${Nouns(resource)}"
      case _ => kind

  /** An event of central's audit log as the console's timeline reads it. `displays` names the members by `sub`. */
  private[partner] def auditEventDto(event: Json.Obj, displays: Map[String, String]): Json.Obj =
    val actorType = str(event, "actorType").getOrElse("")
    val sub = str(event, "actorId").getOrElse("")
    val outcome = str(event, "outcome") match
      case Some("success") => "ok"
      case Some("denied") => "refused"
      case _ => "failed"
    val target = List(str(event, "targetType"), str(event, "targetId")).flatten.mkString(":")
    val actor =
      if actorType == "partner-staff" then Json.Obj("sub" -> Json.Str(sub), "display" -> Json.Str(displays.getOrElse(sub, sub)))
      else if actorType == "edge" then Json.Obj("sub" -> Json.Str(if sub.nonEmpty then sub else "edge"), "display" -> Json.Str(s"Edge ${sub}".trim))
      else Json.Obj("sub" -> Json.Str(if sub.nonEmpty then sub else "operator"), "display" -> Json.Str("Operator"), "byOperator" -> Json.Bool(true))
    Json.Obj(
      Chunk.fromIterable(
        List[(String, Json)](
          "id" -> Json.Str(str(event, "id").getOrElse("")),
          "at" -> Json.Str(str(event, "at").getOrElse("")),
          "kind" -> Json.Str(str(event, "kind").getOrElse("")),
          "actor" -> actor,
          "action" -> Json.Str(actionOf(str(event, "kind").getOrElse(""))),
          "target" -> Json.Str(target),
          "outcome" -> Json.Str(outcome),
        ) ++ str(obj(event, "details"), "edgeId").map(edge => "from" -> (Json.Str(edge): Json)),
      ),
    )

  private[partner] def staffDto(entry: StaffService.Entry): Json =
    Json.Obj(
      Chunk.fromIterable(
        List[(String, Json)](
          "id" -> Json.Str(entry.id),
          "level" -> Json.Str(entry.level.toString),
          "state" -> Json.Str(if entry.member then "member" else "invited"),
          "addedBy" -> Json.Str(entry.addedBy),
          "addedAt" -> Json.Str(entry.addedAt.toString),
        ) ++ entry.subject.map(sub => "subject" -> (Json.Str(sub): Json)) ++
          entry.masked.map(masked =>
            "identifier" -> (Json.Obj("kind" -> Json.Str(entry.kind.fold("")(_.toString)), "masked" -> Json.Str(masked)): Json),
          ) ++
          entry.display.map(display => "display" -> (Json.Str(display): Json)) ++
          entry.expiresAt.map(at => "expiresAt" -> (Json.Str(at.toString): Json)),
      ),
    )
