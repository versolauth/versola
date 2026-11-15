package versola.central.audit

import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.audit.*
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.logging.logContext
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.util.UUID

object AuditHookSpec extends ZIOSpecDefault:
  private val admin = "00000000-0000-0000-0000-0000000000f1"
  private val targetUser = "00000000-0000-0000-0000-000000000001"

  /** What the hook gave the log, and the context of the request at that moment (the tenant and the user agent come from there). */
  private case class Recorded(
      draft: AuditDraft,
      context: AuditContext.State,
      clientId: Option[String],
      sessionId: Option[String],
      ip: Option[String],
  ):
    def kind: String = draft.kind.value
    def details: Map[String, Json] = draft.details.toJson.fields.toMap

  private class Capture(seen: Ref[List[Recorded]]) extends AuditEvents:
    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = recordBestEffort(draft)
    override def recordBestEffort(draft: AuditDraft): UIO[Unit] =
      for
        state <- AuditContext.current
        auth <- logContext.get.map(_.get(Observability.auth))
        _ <- seen.update(_ :+ Recorded(draft, state, auth.flatMap(_.clientId), auth.flatMap(_.sessionId), auth.flatMap(_.ip)))
      yield ()

  /** The tenants whose switch is off. */
  private def switchedOff(tenants: String*): AuditTenants = new AuditTenants:
    override def tenantOf(event: AuditEvent): UIO[Option[String]] = ZIO.none

    override def recording(tenantId: String): UIO[Boolean] = ZIO.succeed(!tenants.contains(tenantId))

  private def asAdmin(request: Request, tenant: String = "default", client: String = "central-admin"): Request =
    request.updateURL(
      _.addQueryParam(AuditHook.ActorIdParam, admin)
        .addQueryParam(AuditHook.ActorTenantParam, tenant)
        .addQueryParam(AuditHook.ActorClientParam, client),
    )

  private def serve(
      routes: Routes[Any, Response],
      request: Request,
      tenants: AuditTenants = AuditTenants.none,
  ): ZIO[Client & TestClient, Nothing, (Response, List[Recorded])] =
    // `Observability.middleware` gives each request a log context of its own; this is that, so that an error code one request set
    // is not the next one's
    logContext.locallyWith(identity)(for
      seen <- Ref.make(List.empty[Recorded])
      client <- ZIO.service[Client]
      _ <- TestClient.addRoutes((routes @@ TestAuditHook.middleware).provideEnvironment(ZEnvironment[AuditEvents, AuditTenants](
        Capture(seen),
        tenants,
      )))
      response <- client.batched(request).orDie
      recorded <- seen.get
    yield (response, recorded))

  /** A handler that knows what it acts on, as a controller's does once it has decoded its request. */
  private def named(id: String, tenant: Option[String] = None, body: String = "") =
    handler((_: Request) =>
      Audit.target(ClientId(id)) *> ZIO.foreachDiscard(tenant)(t => Audit.tenant(TenantId(t))) *> ZIO.succeed(Response.text(body)),
    )

  private def ok(body: String = "") = handler((_: Request) => ZIO.succeed(Response.text(body)))
  private val createClient = Method.POST / "configuration" / "clients" / "web"
  private val updateClient = Method.PUT / "configuration" / "clients" / "web"
  private val rotateSecret = Method.POST / "configuration" / "clients" / "rotate-secret"
  private val deleteClient = Method.DELETE / "configuration" / "clients"
  private val findUsers = Method.GET / "users"
  private val listClients = Method.GET / "configuration" / "clients"

  private def post(path: String, body: String) = Request.post(URL(Path.decode(path)), Body.fromString(body))

  private val bare = post("/configuration/clients/web", """{"id":"c"}""")
  private val syncClients = Method.GET / "configuration" / "clients" / "sync"
  private def refusedCount(reason: String) =
    Metric.counter("audit_actor_refused_total").tagged(Set(MetricLabel("reason", reason))).value.map(_.count)
  private def ran = Ref.make(0).map(counter => (counter, handler((_: Request) => counter.update(_ + 1).as(Response.text("")))))

  private def requireActorSuite =
    suite("a call that names no valid actor")(
      test("a change without an actor is answered 401 and the handler does not run; it is recorded as a denial and counted") {
        for
          before <- refusedCount("absent")
          (counter, run) <- ran
          (response, recorded) <- serve(Routes(createClient -> run), bare)
          runs <- counter.get
          after <- refusedCount("absent")
        yield assertTrue(
          response.status == Status.Unauthorized,
          runs == 0,
          recorded.map(_.kind) == List("admin.client.create"),
          recorded.head.draft.outcome == AuditOutcome.denied,
          recorded.head.draft.reason.contains("actor_required"),
          recorded.head.draft.actor.contains((CentralAudit.Actors.Service, None)),
          after == before + 1,
        )
      },
      test("a read that is an event, and a read nobody records, are refused the same way, and nothing is recorded of the first") {
        val viewed = Request.get(URL(Path.decode("/users")).addQueryParam("id", targetUser))
        for
          (counter, run) <- ran
          (user, userRecorded) <- serve(Routes(findUsers -> run), viewed)
          (listed, listedRecorded) <- serve(Routes(listClients -> run), Request.get(URL(Path.decode("/configuration/clients"))))
          runs <- counter.get
        yield assertTrue(
          user.status == Status.Unauthorized,
          listed.status == Status.Unauthorized,
          runs == 0,
          userRecorded.isEmpty,
          listedRecorded.isEmpty,
        )
      },
      test("an actor that is no user and no client is refused as invalid") {
        for
          before <- refusedCount("invalid")
          (counter, run) <- ran
          (response, _) <- serve(Routes(createClient -> run), bare.updateURL(_.addQueryParam(AuditHook.ActorIdParam, "someone")))
          runs <- counter.get
          after <- refusedCount("invalid")
        yield assertTrue(response.status == Status.Unauthorized, runs == 0, after == before + 1)
      },
      test("a call with a valid actor, an administrator or a client, is served and recorded as before") {
        for
          (counter, run) <- ran
          (byAdmin, adminRecorded) <- serve(Routes(createClient -> run), asAdmin(bare))
          (byClient, clientRecorded) <- serve(Routes(createClient -> run), asAdmin(bare, client = admin))
          (listed, _) <- serve(Routes(listClients -> run), asAdmin(Request.get(URL(Path.decode("/configuration/clients")))))
          runs <- counter.get
        yield assertTrue(
          byAdmin.status == Status.Ok,
          byClient.status == Status.Ok,
          listed.status == Status.Ok,
          runs == 3,
          adminRecorded.head.draft.outcome == AuditOutcome.success,
          adminRecorded.head.draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
          clientRecorded.head.draft.actor.contains((CentralAudit.Actors.Client, Some(admin))),
        )
      },
      test("a route declared internal is served without an actor: it has no administrator") {
        for
          (counter, run) <- ran
          (response, recorded) <- serve(Routes(syncClients -> run), Request.get(URL(Path.decode("/configuration/clients/sync"))))
          runs <- counter.get
        yield assertTrue(response.status == Status.Ok, runs == 1, recorded.isEmpty)
      },
    )

  def spec = suite("AuditHook")(
    test("a change is recorded as admin.<resource>.<operation> with its actor, tenant, target and the route it came by") {
      for
        (response, recorded) <- serve(
          Routes(createClient -> named("web-app", tenant = Some("acme"))),
          asAdmin(post("/configuration/clients/web", "{}")),
        )
      yield
        val event = recorded.head
        assertTrue(
          response.status == Status.Ok,
          recorded.map(_.kind) == List("admin.client.create", "admin.secret.revealed"),
          event.draft.outcome == AuditOutcome.success,
          event.draft.reason.isEmpty,
          event.draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
          // the type of the target is the route's, its id the handler's
          event.draft.target.contains(("client", "web-app")),
          event.draft.userId.isEmpty,
          event.context.tenantId.contains("default"),
          event.details("path") == Json.Str("/configuration/clients/web"),
          event.details("status") == Json.Num(200),
          event.details("tenantId") == Json.Str("acme"),
          !event.details.contains("query"),
          // the second event is the same call's: who looked at the secret it handed back
          recorded(1).draft.target.contains(("client", "web-app")),
          recorded(1).draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
        )
    },
    test("the hook does not read the request body: the handler gets it as it came") {
      val echo = handler((request: Request) => request.body.asString.map(Response.text(_)).orDie)
      for
        (response, recorded) <- serve(Routes(createClient -> echo), asAdmin(post("/configuration/clients/web", """{"id":"web-app"}""")))
        body <- response.body.asString
      yield assertTrue(body == """{"id":"web-app"}""", recorded.head.draft.target.isEmpty)
    },
    test("a change that did not happen is recorded with its outcome: 4xx and 5xx a failure with the request's error code, 401 and 403 denied") {
      def answering(status: Status, code: Option[String]) =
        handler((_: Request) => code.fold(ZIO.unit)(Observability.setError(_)).as(Response.status(status)))
      def outcome(status: Status, code: Option[String]) =
        serve(
          Routes(updateClient -> answering(status, code)),
          asAdmin(Request.put(URL(Path.decode("/configuration/clients/web")), Body.fromString("""{"clientId":"c1"}"""))),
        )
          .map((_, recorded) => recorded.map(e => (e.kind, e.draft.outcome, e.draft.reason)))
      for
        notFound <- outcome(Status.NotFound, Some("client_not_found"))
        conflict <- outcome(Status.Conflict, None)
        broken <- outcome(Status.InternalServerError, Some("write_conflict"))
        forbidden <- outcome(Status.Forbidden, None)
        unauthorized <- outcome(Status.Unauthorized, None)
      yield assertTrue(
        notFound == List(("admin.client.update", AuditOutcome.failure, Some("client_not_found"))),
        // no error code was set: the status stands in for it, a closed set
        conflict == List(("admin.client.update", AuditOutcome.failure, Some("http_409"))),
        broken == List(("admin.client.update", AuditOutcome.failure, Some("write_conflict"))),
        forbidden == List(("admin.client.update", AuditOutcome.denied, Some("http_403"))),
        unauthorized == List(("admin.client.update", AuditOutcome.denied, Some("http_401"))),
      )
    },
    test("a response that is an error value of the handler, and one that is a defect, are recorded and still answered") {
      val refused = handler((_: Request) => Audit.target(ClientId("c1")) *> ZIO.succeed(Response.forbidden))
      val failing: Handler[Any, Response, Request, Response] = handler((_: Request) => Audit.target(ClientId("c1")) *> ZIO.fail(Response.forbidden))
      val request = asAdmin(Request.delete(URL(Path.decode("/configuration/clients")).addQueryParam("clientId", "c1")))
      for
        (response, recorded) <- serve(Routes(deleteClient -> refused), request)
        (failed, failedRecorded) <- serve(Routes(deleteClient -> failing), request)
      yield assertTrue(
        response.status == Status.Forbidden,
        recorded.map(e => (e.kind, e.draft.outcome)) == List(("admin.client.delete", AuditOutcome.denied)),
        recorded.head.draft.target.contains(("client", "c1")),
        failed.status == Status.Forbidden,
        failedRecorded.map(e => (e.kind, e.draft.outcome)) == List(("admin.client.delete", AuditOutcome.denied)),
      )
    },
    test("an action that reveals a secret records admin.secret.revealed only when it succeeded") {
      val request = asAdmin(Request.post(URL(Path.decode("/configuration/clients/rotate-secret")).addQueryParam("clientId", "c1"), Body.empty))
      for
        (_, revealed) <- serve(Routes(rotateSecret -> named("c1", body = "secret")), request)
        (_, refused) <- serve(
          Routes(rotateSecret -> handler((_: Request) => Audit.target(ClientId("c1")) *> ZIO.succeed(Response.status(Status.Conflict)))),
          request,
        )
      yield assertTrue(
        revealed.map(_.kind) == List("admin.client-secret.action:rotate-secret", "admin.secret.revealed"),
        revealed.forall(_.draft.target.contains(("client", "c1"))),
        refused.map(_.kind) == List("admin.client-secret.action:rotate-secret"),
        refused.head.draft.outcome == AuditOutcome.failure,
        refused.forall(_.draft.target.contains(("client", "c1"))),
      )
    },
    test("what a handler tells the log: the target, the user it is about and the names of the fields changed, never their values") {
      val handled = handler { (_: Request) =>
        Audit.target(UserId(UUID.fromString(targetUser))) *> Audit.changedFields("redirectUris" -> true, "clientName" -> true, "theme" -> false) *>
          ZIO.succeed(Response.ok)
      }
      val roles = Method.PATCH / "users" / "roles"
      val deleteUser = Method.DELETE / "users"
      for
        (_, recorded) <- serve(Routes(roles -> handled), asAdmin(Request.patch("/users/roles", Body.fromString(s"""{"add":["secret-role"]}"""))))
        (_, deleted) <- serve(Routes(deleteUser -> handled), asAdmin(Request.delete(URL(Path.decode("/users")).addQueryParam("id", targetUser))))
      yield assertTrue(
        // the type is the route's (`user`), the id the handler's, and the user is the row's user
        recorded.head.draft.target.contains(("user", targetUser)),
        recorded.head.draft.userId.contains(UUID.fromString(targetUser)),
        recorded.head.details("changed") == Json.Arr(Json.Str("clientName"), Json.Str("redirectUris")),
        !recorded.head.draft.details.toJson.toJson.contains("secret-role"),
        deleted.head.kind == "admin.user.delete",
        deleted.head.draft.userId.contains(UUID.fromString(targetUser)),
      )
    },
    test("a route whose handler fails before it knows what it acts on is recorded without a target") {
      val early = handler((_: Request) => ZIO.succeed(Response.status(Status.BadRequest)))
      for (_, recorded) <-
          serve(Routes(updateClient -> early), asAdmin(Request.put(URL(Path.decode("/configuration/clients/web")), Body.fromString("not json"))))
      yield assertTrue(
        recorded.map(_.kind) == List("admin.client.update"),
        recorded.head.draft.target.isEmpty,
        recorded.head.draft.outcome == AuditOutcome.failure,
      )
    },
    test("a look at a user is recorded with the user as target and as the row's user, only when it succeeded") {
      val found = Request.get(URL(Path.decode("/users")).addQueryParam("id", targetUser))
      def knowing(status: Status) =
        handler((_: Request) => Audit.target(UserId(UUID.fromString(targetUser))) *> ZIO.succeed(Response.status(status)))
      def get(status: Status) = serve(Routes(findUsers -> knowing(status)), asAdmin(found)).map(_._2)
      for
        viewed <- get(Status.Ok)
        missing <- get(Status.NotFound)
      yield assertTrue(
        viewed.map(_.kind) == List("admin.user.viewed"),
        viewed.head.draft.target.contains(("user", targetUser)),
        viewed.head.draft.userId.contains(UUID.fromString(targetUser)),
        viewed.head.draft.outcome == AuditOutcome.success,
        missing.isEmpty,
      )
    },
    suite("the tenant's audit switch")(
      test("a change in a tenant that has switched its log off is not recorded, and the handler still runs") {
        for
          (counter, run) <- ran
          (response, recorded) <- serve(Routes(createClient -> run), asAdmin(bare, tenant = "acme"), switchedOff("acme"))
          runs <- counter.get
        yield assertTrue(response.status == Status.Ok, runs == 1, recorded.isEmpty)
      },
      test("a change in a tenant that keeps its log is recorded, whichever tenants have switched off") {
        for
          (_, recorded) <- serve(Routes(createClient -> ok()), asAdmin(bare, tenant = "acme"), switchedOff("other"))
        yield assertTrue(recorded.map(_.kind).contains("admin.client.create"))
      },
      test("the tenant a change is made to decides, not the tenant of the administrator who made it") {
        val onAcme = named("c", tenant = Some("acme"))
        for
          (_, offTarget) <- serve(Routes(createClient -> onAcme), asAdmin(bare, tenant = "default"), switchedOff("acme"))
          (_, offActor) <- serve(Routes(createClient -> onAcme), asAdmin(bare, tenant = "default"), switchedOff("default"))
        yield assertTrue(offTarget.isEmpty, offActor.map(_.kind).contains("admin.client.create"))
      },
      test("a read that is an event of its own, and a secret handed back, follow the switch too") {
        val found = Request.get(URL(Path.decode("/users")).addQueryParam("id", targetUser))
        for
          (_, viewed) <- serve(Routes(findUsers -> ok()), asAdmin(found, tenant = "acme"), switchedOff("acme"))
          (_, revealed) <- serve(
            Routes(rotateSecret -> ok()),
            asAdmin(post("/configuration/clients/rotate-secret", """{"id":"c"}"""), tenant = "acme"),
            switchedOff("acme"),
          )
        yield assertTrue(viewed.isEmpty, revealed.isEmpty)
      },
      test("a call that names no actor belongs to no tenant, so it is recorded whichever tenants have switched off") {
        for (response, recorded) <- serve(Routes(createClient -> ok()), bare, switchedOff("acme"))
        yield assertTrue(response.status == Status.Unauthorized, recorded.map(_.kind) == List("admin.client.create"))
      },
    ),
    test("a change auth makes is recorded by central with its outcome as well, success included") {
      val sessions = Method.DELETE / "users" / "sessions"
      val password = Method.POST / "users" / "password" / "set"
      val reset = Method.POST / "users" / "password" / "reset"
      val request = asAdmin(Request.delete(URL(Path.decode("/users/sessions")).addQueryParam("id", targetUser)))
      def failing(status: Status) = handler((_: Request) => ZIO.succeed(Response.status(status)))
      for
        (_, succeeded) <- serve(Routes(sessions -> ok()), request)
        (_, refused) <- serve(Routes(sessions -> failing(Status.Forbidden)), request)
        (_, unavailable) <- serve(Routes(sessions -> failing(Status.BadGateway)), request)
        (_, passwordSet) <- serve(Routes(password -> ok()), asAdmin(post("/users/password/set", s"""{"userId":"$targetUser","password":"p"}""")))
        (_, revealed) <- serve(Routes(reset -> ok()), asAdmin(post("/users/password/reset", s"""{"userId":"$targetUser","expiresInSeconds":60}""")))
      yield assertTrue(
        succeeded.map(r => (r.kind, r.draft.outcome)) == List(("admin.user-session.delete", AuditOutcome.success)),
        passwordSet.map(r => (r.kind, r.draft.outcome)) == List(("admin.user-password.action:set", AuditOutcome.success)),
        refused.map(r => (r.kind, r.draft.outcome)) == List(("admin.user-session.delete", AuditOutcome.denied)),
        unavailable.map(r => (r.kind, r.draft.outcome)) == List(("admin.user-session.delete", AuditOutcome.failure)),
        // a reset that hands the temporary password back is also a secret revealed
        revealed.map(_.kind) == List("admin.user-password.action:reset", "admin.secret.revealed"),
      )
    },
    test(
      "a call without the actor parameters is refused, recorded as a denial of a service's with no id and counted; one whose actor is its own client is the client's",
    ) {
      val bare = post("/configuration/clients/web", """{"id":"c"}""")
      def count(reason: String) =
        Metric.counter("audit_actor_missing_total").tagged(Set(MetricLabel("reason", reason))).value.map(_.count)
      for
        absentBefore <- count("absent")
        invalidBefore <- count("invalid")
        (bareResponse, bareRecorded) <- serve(Routes(createClient -> ok()), bare)
        absentAfter <- count("absent")
        (notAUserResponse, notAUser) <- serve(Routes(createClient -> ok()), bare.updateURL(_.addQueryParam(AuditHook.ActorIdParam, "someone")))
        invalidAfter <- count("invalid")
        (_, clientRecorded) <- serve(Routes(createClient -> ok()), asAdmin(bare, client = admin))
        clientAfter <- count("invalid")
        (_, userAgent) <- serve(Routes(createClient -> ok()), asAdmin(bare).addHeader(Header.Custom("User-Agent", "Console/1")))
      yield assertTrue(
        bareResponse.status == Status.Unauthorized,
        notAUserResponse.status == Status.Unauthorized,
        bareRecorded.head.draft.outcome == AuditOutcome.denied,
        bareRecorded.head.draft.actor.contains((CentralAudit.Actors.Service, None)),
        bareRecorded.head.context.tenantId.isEmpty,
        absentAfter == absentBefore + 1,
        // an actor id that is no user id and no client is treated as absent, and counted as invalid
        notAUser.head.draft.actor.contains((CentralAudit.Actors.Service, None)),
        invalidAfter == invalidBefore + 1,
        clientRecorded.head.draft.actor.contains((CentralAudit.Actors.Client, Some(admin))),
        clientAfter == invalidAfter,
        userAgent.head.context.userAgent.contains("Console/1"),
      )
    },
    requireActorSuite,
    test("the client and the session of the token are the client and the session of the event's context") {
      val session = "sess-1"
      for
        (_, recorded) <- serve(
          Routes(createClient -> ok()),
          asAdmin(post("/configuration/clients/web", """{"id":"c"}""")).updateURL(_.addQueryParam(AuditHook.ActorSessionParam, session)),
        )
      yield assertTrue(
        recorded.head.draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
        recorded.head.clientId.contains("central-admin"),
        recorded.head.sessionId.contains(session),
      )
    },
    test("the address edge found for the caller is the ip of the event; one that is no address is not recorded and is counted") {
      def count(reason: String) =
        Metric.counter("audit_actor_missing_total").tagged(Set(MetricLabel("reason", reason))).value.map(_.count)
      def withIp(value: String) =
        asAdmin(post("/configuration/clients/web", """{"id":"c"}""")).updateURL(_.addQueryParam(AuditHook.ActorIpParam, value))
      for
        before <- count("invalid_ip")
        (_, v4) <- serve(Routes(createClient -> ok()), withIp("203.0.113.7"))
        (_, v6) <- serve(Routes(createClient -> ok()), withIp("2001:db8:1:2::1"))
        afterValid <- count("invalid_ip")
        (_, none) <- serve(Routes(createClient -> ok()), asAdmin(post("/configuration/clients/web", """{"id":"c"}""")))
        (_, text) <- serve(Routes(createClient -> ok()), withIp("not-an-address"))
        (_, almost) <- serve(Routes(createClient -> ok()), withIp("1.2.3"))
        (_, listed) <- serve(Routes(createClient -> ok()), withIp("203.0.113.7, 198.51.100.1"))
        afterInvalid <- count("invalid_ip")
      yield assertTrue(
        v4.forall(_.ip.contains("203.0.113.7")),
        v6.forall(_.ip.contains("2001:db8:1:2::1")),
        afterValid == before,
        // no address: the event has none, and nothing is counted for it
        none.forall(_.ip.isEmpty),
        // an address that is none is treated as absent, the actor is still the one edge named, and the call is counted
        List(text, almost, listed).forall(_.forall(_.ip.isEmpty)),
        List(text, almost, listed).forall(_.head.draft.actor.contains((CentralAudit.Actors.Admin, Some(admin)))),
        afterInvalid == before + 3,
      )
    },
    test("a request that is interrupted after its change committed is still recorded, as a failure that was interrupted") {
      for
        seen <- Ref.make(List.empty[Recorded])
        started <- Promise.make[Nothing, Unit]
        client <- ZIO.service[Client]
        // the change has happened; the handler then waits, as a response that is still being written would
        routes = Routes(createClient -> handler((_: Request) => started.succeed(()) *> ZIO.never))
        _ <- TestClient.addRoutes((routes @@ TestAuditHook.middleware).provideEnvironment(ZEnvironment[AuditEvents, AuditTenants](
          Capture(seen),
          AuditTenants.none,
        )))
        fiber <- client.batched(asAdmin(post("/configuration/clients/web", """{"id":"c"}"""))).fork
        _ <- started.await
        _ <- fiber.interrupt
        recorded <- seen.get.repeatUntil(_.nonEmpty).timeoutFail(RuntimeException("not recorded"))(5.seconds).orDie.withClock(Clock.ClockLive)
      yield assertTrue(
        recorded.map(_.kind) == List("admin.client.create"),
        recorded.head.draft.outcome == AuditOutcome.failure,
        recorded.head.draft.reason.contains("interrupted"),
      )
    },
    test("a parameter an attacker makes enormous does not make an enormous row") {
      val long = "x" * 10000
      for
        (_, recorded) <- serve(Routes(createClient -> named(long)), asAdmin(post("/configuration/clients/web", "{}"), tenant = long))
        // an actor id that is no user id is no actor: the call is refused, and the row does not hold what was written
        (refused, refusedRecorded) <- serve(
          Routes(createClient -> named(long)),
          post("/configuration/clients/web", "{}").updateURL(_.addQueryParam(AuditHook.ActorIdParam, long)),
        )
      yield assertTrue(
        recorded.head.context.tenantId.exists(_.length <= 256),
        recorded.head.draft.target.exists(_._2.length <= 256),
        refused.status == Status.Unauthorized,
        refusedRecorded.head.draft.actor.contains((CentralAudit.Actors.Service, None)),
      )
    },
    test("reads nobody records and internal routes record nothing, and are served as before") {
      val syncRoute = Method.GET / "configuration" / "clients" / "sync"
      for
        (listed, list) <- serve(Routes(listClients -> ok("list")), asAdmin(Request.get(URL(Path.decode("/configuration/clients")))))
        (synced, sync) <- serve(Routes(syncRoute -> ok("sync")), Request.get(URL(Path.decode("/configuration/clients/sync"))))
        listedBody <- listed.body.asString
      yield assertTrue(
        list.isEmpty,
        sync.isEmpty,
        listedBody == "list",
        synced.status == Status.Ok,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.sequential
