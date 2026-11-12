package versola.edge.partner

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
      session <- authenticate(request)
      // the cookie of a refresh is set on whatever the answer is: the refresh token it replaced is spent, so a browser left with the
      // old cookie would be sent to sign in again for a call that merely failed
      response <- (for
        _ <- ZIO.fail(Fail(Status.Forbidden, "insufficient_level", extra = List("required" -> Json.Str(level.toString))))
          .unless(session.member.level.atLeast(level))
          .tapError(_ => Observability.setError("insufficient_level"))
        response <- run(session, request)
      yield response).catchAll(failure(_)).map(response => session.console.rotatedCookie.fold(response)(response.addCookie))
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

  private def authenticate(request: Request): IO[Fail | Throwable, Session] =
    for
      console <- edgeService.consoleSession(request).catchAll:
        case noSession: EdgeService.NoSession => unauthenticated(noSession.clear)
        case error: Throwable => ZIO.fail(error)
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
            "issuer" -> optional(operator.issuer),
            "mtlsUrl" -> optional(operator.mtlsUrl),
            "centralMtlsUrl" -> optional(operator.centralMtlsUrl),
          ),
        ),
        "staff" -> Json.Obj(
          "sub" -> Json.Str(session.member.subject),
          "display" -> Json.Str(session.member.display),
          "level" -> Json.Str(session.member.level.toString),
        ),
        "edge" -> Json.Obj(
          "id" -> Json.Str(session.me.edge.id),
          "publicUrl" -> optional(session.me.edge.publicUrl),
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
        "signingKey" -> field(created, "signingKey").getOrElse(Json.Null),
        "token" -> field(created, "token").getOrElse(Json.Null),
        "bundle" -> field(created, "bundle").getOrElse(Json.Null),
        "expiresAt" -> field(created, "expiresAt").getOrElse(Json.Null),
      ),
    )

  private def deleteEdge(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.DELETE, List("edges"), query = Map("id" -> id)).as(Response.status(Status.NoContent))

  /** A token for an edge that exists. Central's answer is the token and its expiry; the key is shown once, at creation, and is not
    * here, so the bundle carries everything else the edge is deployed with. */
  private def issueToken(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.POST, List("edges", "enrollment-tokens"), body = Some(Json.Obj("id" -> Json.Str(id)))).map: answer =>
      val issued = answer.obj.getOrElse(Json.Obj())
      val token = str(issued, "token").getOrElse("")
      val operator = session.me.operator
      val bundle = Json.Obj(
        "edgeId" -> Json.Str(id),
        "group" -> Json.Str(session.me.partner.id),
        "central" -> Json.Obj("mtlsUrl" -> optional(operator.centralMtlsUrl)),
        "auth" -> Json.Obj("issuer" -> optional(operator.issuer), "mtlsUrl" -> optional(operator.mtlsUrl)),
        "token" -> Json.Str(token),
      )
      json(
        Status.Created,
        Json.Obj("token" -> Json.Str(token), "bundle" -> Json.Str(bundle.toJson), "expiresAt" -> field(issued, "expiresAt").getOrElse(Json.Null)),
      )

  private def revokeToken(id: String, session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(
      session,
      Method.DELETE,
      List("edges", "enrollment-tokens"),
      body = Some(Json.Obj("id" -> Json.Str(id))),
    ).as(Response.status(Status.NoContent))

  // ── revocations, audit ───────────────────────────────────────────────────────────────────────

  /** Ends the grants of the partner's own clients, by user or by client. A management call like any other: central asks auth, which
    * revokes the refresh-token families and announces them on the back channel, and the announcement reaches this edge's
    * `/logout/backchannel`, which is how the edge's own sessions end. Nothing is revoked here: with central unavailable it is `503`.
    *
    * `sid` and `fam` are what the console's form offers and central does not take yet (#962 §4: `sid` and `jti` later), so they are
    * refused rather than half done. */
  private def revoke(session: Session, request: Request): IO[Fail | Throwable, Response] =
    for
      body <- jsonBody(request)
      by <- ZIO.fromOption(str(body, "by").filter(Set("user", "client"))).orElseFail(
        Fail(
          Status.BadRequest,
          "invalid_request",
          message = Some("by must be user or client: sessions and refresh families are not revoked through central yet"),
        ),
      )
      value <- ZIO.fromOption(str(body, "value")).orElseFail(Fail(Status.BadRequest, "invalid_request", message = Some("value is missing")))
      named = field(body, "clients").collect { case Json.Arr(elements) => elements.flatMap(_.asString) }.getOrElse(Chunk.empty)
      // no client named is every client of the partner that is approved
      clients <-
        if named.nonEmpty then ZIO.succeed(named.toList)
        else
          forward(session, Method.GET, List("clients")).map(answer =>
            arrayOf(answer, "clients").toList.filter(client =>
              obj(client, "approval").fields.exists { case ("status", Json.Str("approved")) => true; case _ => false },
            ).flatMap(str(_, "id")),
          )
      _ <- ZIO.fail(Fail(
        Status.BadRequest,
        "invalid_request",
        message = Some("the partner has no approved client to revoke grants of"),
      )).when(clients.isEmpty)
      _ <- forward(
        session,
        Method.POST,
        List("revocations"),
        body = Some(Json.Obj(
          "by" -> Json.Str(by),
          "value" -> Json.Str(value),
          "clients" -> Json.Arr(clients.map(Json.Str(_))*),
          "revokeRefresh" -> Json.Bool(true),
        )),
      )
    // central does not count what auth ended
    yield json(Status.Accepted, Json.Obj("ended" -> Json.Num(0), "accepted" -> Json.Bool(true)))

  /** The revocations the partner's staff made, read from central's audit trail of them (central keeps no list of its own). */
  private def listRevocations(session: Session, request: Request): IO[Fail | Throwable, Response] =
    forward(session, Method.GET, List("audit"), query = Map("kind" -> "admin.partner-revocation.create")).map: answer =>
      val revocations = arrayOf(answer, "events").map: event =>
        Json.Obj(
          "id" -> Json.Str(str(event, "id").getOrElse("")),
          "at" -> Json.Str(str(event, "at").getOrElse("")),
          "by_staff" -> Json.Str(str(event, "actorId").getOrElse("")),
          "by" -> Json.Str(""),
          "value" -> Json.Str(str(event, "targetId").getOrElse("")),
          "clients" -> Json.Arr(),
          "edges" -> Json.Arr(),
          "ended" -> Json.Num(0),
        )
      ok(Json.Obj("revocations" -> Json.Arr(revocations*)))

  private def audit(session: Session, request: Request): IO[Fail | Throwable, Response] =
    val query = List("cursor", "actor", "kind", "q").flatMap(name => request.url.queryParams.getAll(name).headOption.map(name -> _)).toMap
    forward(session, Method.GET, List("audit"), query = query).map: answer =>
      val events = arrayOf(answer, "events").map(auditEventDto)
      ok(Json.Obj("events" -> Json.Arr(events*), "next" -> answer.obj.flatMap(field(_, "next")).getOrElse(Json.Null)))

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
      _ <- forward(session, Method.PUT, List("settings"), body = Some(body))
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
    central.call(method, path, query, body, Some(session.principal)).mapError {
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
            fail.message.map(message => "message" -> (Json.Str(message): Json)) ++ fail.extra)*,
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

  /** An event of central's audit log as the console's timeline reads it. */
  private[partner] def auditEventDto(event: Json.Obj): Json.Obj =
    val actorType = str(event, "actorType").getOrElse("")
    val sub = str(event, "actorId").getOrElse("")
    val outcome = str(event, "outcome") match
      case Some("success") => "ok"
      case Some("denied") => "refused"
      case _ => "failed"
    val target = List(str(event, "targetType"), str(event, "targetId")).flatten.mkString(":")
    val actor =
      if actorType == "partner-staff" then Json.Obj("sub" -> Json.Str(sub))
      else Json.Obj("sub" -> Json.Str(if sub.nonEmpty then sub else "operator"), "byOperator" -> Json.Bool(true))
    Json.Obj(
      Chunk.fromIterable(
        List[(String, Json)](
          "id" -> Json.Str(str(event, "id").getOrElse("")),
          "at" -> Json.Str(str(event, "at").getOrElse("")),
          "actor" -> actor,
          "action" -> Json.Str(str(event, "kind").getOrElse("")),
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
