package versola.central.audit

import versola.central.{CentralConfig, TestCentralConfig}
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
  private case class Recorded(draft: AuditDraft, context: AuditContext.State, clientId: Option[String], sessionId: Option[String]):
    def kind: String = draft.kind.value
    def details: Map[String, Json] = draft.details.toJson.fields.toMap

  private class Capture(seen: Ref[List[Recorded]]) extends AuditEvents:
    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = recordBestEffort(draft)
    override def recordBestEffort(draft: AuditDraft): UIO[Unit] =
      for
        state <- AuditContext.current
        auth <- logContext.get.map(_.get(Observability.auth))
        _ <- seen.update(_ :+ Recorded(draft, state, auth.flatMap(_.clientId), auth.flatMap(_.sessionId)))
      yield ()

  private def settings(adminUserViewed: Boolean = true) =
    TestCentralConfig.config.copy(audit = CentralConfig.AuditConfig(events = CentralConfig.AuditConfig.Events(adminUserViewed = adminUserViewed)))

  private def asAdmin(request: Request, tenant: String = "default", client: String = "central-admin"): Request =
    request.updateURL(
      _.addQueryParam(AuditHook.ActorIdParam, admin)
        .addQueryParam(AuditHook.ActorTenantParam, tenant)
        .addQueryParam(AuditHook.ActorClientParam, client),
    )

  private def serve(
      routes: Routes[Any, Response],
      request: Request,
      tiers: CentralConfig = settings(),
  ): ZIO[Client & TestClient, Nothing, (Response, List[Recorded])] =
    // `Observability.middleware` gives each request a log context of its own; this is that, so that an error code one request set
    // is not the next one's
    logContext.locallyWith(identity)(for
      seen <- Ref.make(List.empty[Recorded])
      client <- ZIO.service[Client]
      _ <- TestClient.addRoutes((routes @@ TestAuditHook.middleware).provideEnvironment(ZEnvironment[AuditEvents, CentralConfig](
        Capture(seen),
        tiers,
      )))
      response <- client.batched(request).orDie
      recorded <- seen.get
    yield (response, recorded))

  private def ok(body: String = "") = handler((_: Request) => ZIO.succeed(Response.text(body)))
  private val createClient = Method.POST / "configuration" / "clients" / "web"
  private val updateClient = Method.PUT / "configuration" / "clients" / "web"
  private val rotateSecret = Method.POST / "configuration" / "clients" / "rotate-secret"
  private val deleteClient = Method.DELETE / "configuration" / "clients"
  private val findUsers = Method.GET / "users"
  private val listClients = Method.GET / "configuration" / "clients"
  private val unknownRoute = Method.POST / "configuration" / "not-in-the-table"

  private def post(path: String, body: String) = Request.post(URL(Path.decode(path)), Body.fromString(body))

  def spec = suite("AuditHook")(
    test("a change is recorded as admin.<resource>.<operation> with its actor, tenant, target and the route it came by") {
      for
        (response, recorded) <- serve(
          Routes(createClient -> ok()),
          asAdmin(post("/configuration/clients/web", s"""{"id":"web-app","tenantId":"acme"}""")),
        )
      yield
        val event = recorded.head
        assertTrue(
          response.status == Status.Ok,
          recorded.map(_.kind) == List("admin.client.create", "admin.secret.revealed"),
          event.draft.outcome == AuditOutcome.success,
          event.draft.reason.isEmpty,
          event.draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
          event.draft.target.contains(("client", "web-app")),
          event.draft.userId.isEmpty,
          event.context.tenantId.contains("default"),
          event.details("path") == Json.Str("/configuration/clients/web"),
          event.details("status") == Json.Num(200),
          event.details("tenantId") == Json.Str("acme"),
          // the second event is the same call's: who looked at the secret it handed back
          recorded(1).draft.target.contains(("client", "web-app")),
          recorded(1).draft.actor.contains((CentralAudit.Actors.Admin, Some(admin))),
        )
    },
    test("the handler reads the body the hook read to find the target") {
      val echo = handler((request: Request) => request.body.asString.map(Response.text(_)).orDie)
      for
        (response, _) <- serve(Routes(createClient -> echo), asAdmin(post("/configuration/clients/web", """{"id":"web-app"}""")))
        body <- response.body.asString
      yield assertTrue(body == """{"id":"web-app"}""")
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
      val refused = handler((_: Request) => ZIO.succeed(Response.forbidden)).tapZIO(_ => ZIO.unit)
      val failing: Handler[Any, Response, Request, Response] = handler((_: Request) => ZIO.fail(Response.forbidden))
      val request = asAdmin(Request.delete(URL(Path.decode("/configuration/clients")).addQueryParam("clientId", "c1")))
      for
        (response, recorded) <- serve(Routes(deleteClient -> refused), request)
        (failed, failedRecorded) <- serve(Routes(deleteClient -> failing), request)
      yield assertTrue(
        response.status == Status.Forbidden,
        recorded.map(e => (e.kind, e.draft.outcome)) == List(("admin.client.delete", AuditOutcome.denied)),
        recorded.head.draft.target.contains(("client", "c1")),
        recorded.head.details("query") == Json.Obj("clientId" -> Json.Str("c1")),
        failed.status == Status.Forbidden,
        failedRecorded.map(e => (e.kind, e.draft.outcome)) == List(("admin.client.delete", AuditOutcome.denied)),
      )
    },
    test("an action that reveals a secret records admin.secret.revealed only when it succeeded") {
      val request = asAdmin(Request.post(URL(Path.decode("/configuration/clients/rotate-secret")).addQueryParam("clientId", "c1"), Body.empty))
      for
        (_, revealed) <- serve(Routes(rotateSecret -> ok("secret")), request)
        (_, refused) <- serve(Routes(rotateSecret -> handler((_: Request) => ZIO.succeed(Response.status(Status.Conflict)))), request)
      yield assertTrue(
        revealed.map(_.kind) == List("admin.client-secret.action:rotate-secret", "admin.secret.revealed"),
        revealed.forall(_.draft.target.contains(("client", "c1"))),
        refused.map(_.kind) == List("admin.client-secret.action:rotate-secret"),
        refused.head.draft.outcome == AuditOutcome.failure,
      )
    },
    test("what a service puts in the audit context wins: the target, the user and the names of the fields changed") {
      val handled = handler { (_: Request) =>
        AuditContext.setTarget("client", "from-the-service") *> AuditContext.setChanged(Set("redirectUris", "clientName")) *>
          AuditContext.setUser(UUID.fromString(targetUser)).as(Response.ok)
      }
      for
        (_, recorded) <- serve(
          Routes(updateClient -> handled),
          asAdmin(Request.put(URL(Path.decode("/configuration/clients/web")), Body.fromString("""{"clientId":"from-the-body"}"""))),
        )
      yield assertTrue(
        recorded.head.draft.target.contains(("client", "from-the-service")),
        recorded.head.draft.userId.contains(UUID.fromString(targetUser)),
        recorded.head.details("changed") == Json.Arr(Json.Str("clientName"), Json.Str("redirectUris")),
      )
    },
    test("an update records the names of the fields its body carried, never their values, and not the ones that name what is updated") {
      for
        (_, recorded) <- serve(
          Routes(updateClient -> ok()),
          asAdmin(Request.put(
            URL(Path.decode("/configuration/clients/web")),
            Body.fromString(
              """{"clientId":"c1","tenantId":"t","userId":"x","redirectUris":{"add":["https://secret.example.com"]},"clientName":{"en":"N"}}""",
            ),
          )),
        )
      yield assertTrue(
        recorded.head.details("changed") == Json.Arr(Json.Str("clientName"), Json.Str("redirectUris")),
        !recorded.head.draft.details.toJson.toJson.contains("secret.example.com"),
      )
    },
    test("a look at a user is recorded with the user as target and as the row's user, only when it succeeded and the deployment records it") {
      val found = Request.get(URL(Path.decode("/users")).addQueryParam("id", targetUser))
      def get(status: Status, tiers: CentralConfig) =
        serve(Routes(findUsers -> handler((_: Request) => ZIO.succeed(Response.status(status)))), asAdmin(found), tiers).map(_._2)
      for
        viewed <- get(Status.Ok, settings())
        missing <- get(Status.NotFound, settings())
        off <- get(Status.Ok, settings(adminUserViewed = false))
      yield assertTrue(
        viewed.map(_.kind) == List("admin.user.viewed"),
        viewed.head.draft.target.contains(("user", targetUser)),
        viewed.head.draft.userId.contains(UUID.fromString(targetUser)),
        viewed.head.draft.outcome == AuditOutcome.success,
        missing.isEmpty,
        off.isEmpty,
      )
    },
    test("a user action takes the user from the query or the body, and a target that is not a UUID is no user of ours") {
      val deleteUser = Method.DELETE / "users"
      val roles = Method.PATCH / "users" / "roles"
      def delete(id: String) = asAdmin(Request.delete(URL(Path.decode("/users")).addQueryParam("id", id)))
      for
        (_, byQuery) <- serve(Routes(deleteUser -> ok()), delete(targetUser))
        (_, byBody) <-
          serve(Routes(roles -> ok()), asAdmin(Request.patch("/users/roles", Body.fromString(s"""{"userId":"$targetUser","add":["r"]}"""))))
        (_, notAUuid) <- serve(Routes(deleteUser -> ok()), delete("someone"))
      yield assertTrue(
        byQuery.head.kind == "admin.user.delete",
        byQuery.head.draft.userId.contains(UUID.fromString(targetUser)),
        byBody.head.kind == "admin.user-roles.update",
        byBody.head.draft.userId.contains(UUID.fromString(targetUser)),
        notAUuid.head.draft.userId.isEmpty,
        notAUuid.head.draft.target.contains(("user", "someone")),
      )
    },
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
    test("a call without the actor parameters is recorded as a service's with no id and counted; one whose actor is its own client as the client's") {
      val bare = post("/configuration/clients/web", """{"id":"c"}""")
      def count(reason: String) =
        Metric.counter("audit_actor_missing_total").tagged(Set(MetricLabel("reason", reason))).value.map(_.count)
      for
        absentBefore <- count("absent")
        invalidBefore <- count("invalid")
        (_, bareRecorded) <- serve(Routes(createClient -> ok()), bare)
        absentAfter <- count("absent")
        (_, notAUser) <- serve(Routes(createClient -> ok()), bare.updateURL(_.addQueryParam(AuditHook.ActorIdParam, "someone")))
        invalidAfter <- count("invalid")
        (_, clientRecorded) <- serve(Routes(createClient -> ok()), asAdmin(bare, client = admin))
        clientAfter <- count("invalid")
        (_, userAgent) <- serve(Routes(createClient -> ok()), asAdmin(bare).addHeader(Header.Custom("User-Agent", "Console/1")))
      yield assertTrue(
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
    test("a request that is interrupted after its change committed is still recorded, as a failure that was interrupted") {
      for
        seen <- Ref.make(List.empty[Recorded])
        started <- Promise.make[Nothing, Unit]
        client <- ZIO.service[Client]
        // the change has happened; the handler then waits, as a response that is still being written would
        routes = Routes(createClient -> handler((_: Request) => started.succeed(()) *> ZIO.never))
        _ <- TestClient.addRoutes((routes @@ TestAuditHook.middleware).provideEnvironment(ZEnvironment[AuditEvents, CentralConfig](
          Capture(seen),
          settings(),
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
        (_, recorded) <- serve(
          Routes(createClient -> ok()),
          post("/configuration/clients/web", s"""{"id":"$long"}""")
            .updateURL(_.addQueryParam(AuditHook.ActorIdParam, long).addQueryParam(AuditHook.ActorTenantParam, long)),
        )
      yield assertTrue(
        // an actor id that is no user id is no actor
        recorded.head.draft.actor.contains((CentralAudit.Actors.Service, None)),
        recorded.head.context.tenantId.exists(_.length <= 256),
        recorded.head.draft.target.exists(_._2.length <= 256),
      )
    },
    test("reads nobody records, internal routes and routes the table does not know record nothing, and are served as before") {
      val syncRoute = Method.GET / "configuration" / "clients" / "sync"
      for
        (listed, list) <- serve(Routes(listClients -> ok("list")), asAdmin(Request.get(URL(Path.decode("/configuration/clients")))))
        (synced, sync) <- serve(Routes(syncRoute -> ok("sync")), Request.get(URL(Path.decode("/configuration/clients/sync"))))
        (unknown, other) <- serve(Routes(unknownRoute -> ok("hi")), asAdmin(post("/configuration/not-in-the-table", "{}")))
        listedBody <- listed.body.asString
        unknownBody <- unknown.body.asString
      yield assertTrue(
        list.isEmpty,
        sync.isEmpty,
        other.isEmpty,
        listedBody == "list",
        synced.status == Status.Ok,
        unknownBody == "hi",
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.sequential
