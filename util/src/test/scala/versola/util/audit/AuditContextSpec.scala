package versola.util.audit

import io.opentelemetry.api
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

/** The audit context is the request's own: filled in from wherever the request knows something, reset by the middleware so that
  * nothing of one request is in the next. */
object AuditContextSpec extends ZIOSpecDefault:

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val Admin = ActorType("admin")
  private val Service = ActorType("service")

  private val userId = UUID.fromString("5f1c8f0e-6a3e-4a52-9d3e-2b1c0f6f3a01")

  /** What each request's handler saw of the context when it started, in order. */
  private def routes(seen: Ref[List[AuditContext.State]]) =
    Observability.middleware(
      Routes(
        Method.GET / "note" -> Handler.fromFunctionZIO[Request]: _ =>
          for
            before <- AuditContext.current
            _ <- seen.update(_ :+ before)
            _ <- AuditContext.setTenant("tenant-a") *> AuditContext.setTarget("client", "client-a") *> AuditContext.setChanged(Set("name"))
            _ <- AuditContext.setActor(Admin, Some("admin-1")) *> AuditContext.setUser(userId)
          yield Response.text("ok"),
        Method.GET / "look" -> Handler.fromFunctionZIO[Request]: _ =>
          AuditContext.current.flatMap(state => seen.update(_ :+ state)).as(Response.text("ok")),
      ),
    )

  val spec = suite("AuditContext")(
    test("what a request sets is not in the next request's context") {
      for
        env <- tracingLayer.build
        seen <- Ref.make(List.empty[AuditContext.State])
        _ <- TestClient.addRoutes(routes(seen).provideEnvironment(env))
        client <- ZIO.service[Client]
        _ <- client.batched(Request.get(URL.empty / "note"))
        _ <- client.batched(Request.get(URL.empty / "look"))
        states <- seen.get
      yield assertTrue(states == List(AuditContext.State.empty, AuditContext.State.empty))
    },
    test("what a request sets is not left in the context of the fiber that served it") {
      for
        env <- tracingLayer.build
        seen <- Ref.make(List.empty[AuditContext.State])
        _ <- TestClient.addRoutes(routes(seen).provideEnvironment(env))
        client <- ZIO.service[Client]
        _ <- client.batched(Request.get(URL.empty / "note"))
        after <- AuditContext.current
      yield assertTrue(after == AuditContext.State.empty)
    },
    test("the request's User-Agent is noted when it starts, and a request with none has none") {
      for
        env <- tracingLayer.build
        seen <- Ref.make(List.empty[AuditContext.State])
        _ <- TestClient.addRoutes(routes(seen).provideEnvironment(env))
        client <- ZIO.service[Client]
        _ <- client.batched(Request.get(URL.empty / "look").addHeader(Header.Custom("User-Agent", "Mozilla/5.0")))
        _ <- client.batched(Request.get(URL.empty / "look"))
        states <- seen.get
      yield assertTrue(states.map(_.userAgent) == List(Some("Mozilla/5.0"), None))
    },
    test("the setters fill the state, and the target, the changed names and the actor are what was set last") {
      for
        _ <- AuditContext.setTenant("tenant-a")
        _ <- AuditContext.setTarget("client", "client-a") *> AuditContext.setTarget("user", "u-1")
        _ <- AuditContext.setChanged(Set("a")) *> AuditContext.setChanged(Set("b", "c"))
        _ <- AuditContext.setActor(Service, Some("central")) *> AuditContext.setUser(userId) *> AuditContext.setUserAgent("UA")
        state <- AuditContext.current
      yield assertTrue(
        state == AuditContext.State(
          tenantId = Some("tenant-a"),
          actor = Some((Service, Some("central"))),
          userId = Some(userId),
          target = Some(("user", "u-1")),
          changed = Set("b", "c"),
          userAgent = Some("UA"),
        ),
      )
    } @@ TestAspect.after(AuditContext.ref.set(AuditContext.State.empty)),
  ).provideSomeLayer[Scope](TestClient.layer) @@ TestAspect.sequential
