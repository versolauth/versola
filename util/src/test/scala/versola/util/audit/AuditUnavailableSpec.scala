package versola.util.audit

import io.opentelemetry.api
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

/** An event the audit log could not take in time is a request that does not happen: every endpoint carries it out as a failure and
  * `Observability.handleErrors` answers it, so that none maps it by hand. */
object AuditUnavailableSpec extends ZIOSpecDefault:

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val routes =
    Observability.middleware(
      Observability.handleErrors(
        Routes(
          Method.GET / "gated" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(AuditUnavailableException)),
          Method.GET / "broken" -> Handler.fromFunctionZIO[Request](_ => ZIO.fail(RuntimeException("boom"))),
        ),
      ),
    )

  val spec = suite("AuditUnavailableException")(
    test("is answered with 503 and a retry hint, and any other failure is still a 500") {
      for
        env <- tracingLayer.build
        _ <- TestClient.addRoutes(routes.provideEnvironment(env))
        client <- ZIO.service[Client]
        gated <- client.batched(Request.get(URL.empty / "gated"))
        broken <- client.batched(Request.get(URL.empty / "broken"))
      yield assertTrue(
        gated.status == Status.ServiceUnavailable,
        gated.rawHeader("Retry-After") == Some("1"),
        broken.status == Status.InternalServerError,
      )
    },
  ).provideSomeLayer[Scope](TestClient.layer) @@ TestAspect.silentLogging
