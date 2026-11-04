package versola.central.audit

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.central.users.UserId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import java.util.UUID

object AuditReportControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val report = UserAuditReport(
    userId = userId,
    window = ReportWindow(Instant.parse("2026-10-28T00:00:00Z"), Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-10-28T00:00:00Z")),
    summary = None,
    events = Nil,
    nextCursor = Some("next"),
    partial = List("auth"),
  )

  private def run(request: Request, service: AuditReportService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AuditReportController.routes.provideEnvironment(
            ZEnvironment[AuditReportService](service) ++ ZEnvironment[ResourceService](resources) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private val base = URL.empty / "audit" / "reports" / "user"

  def spec = suite("AuditReportController")(
    test("the report is served with the user, the window, the cursor and the page size the request named") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.succeedsWith(report)
        response <- run(
          Request.get(
            base.addQueryParam("userId", userId.toString).addQueryParam("from", "2026-11-01T00:00:00Z").addQueryParam("to", "2026-11-03T00:00:00Z")
              .addQueryParam("cursor", "abc").addQueryParam("limit", "25"),
          ),
          service,
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        service.userReport.calls == List(
          (userId, Some(Instant.parse("2026-11-01T00:00:00Z")), Some(Instant.parse("2026-11-03T00:00:00Z")), Some("abc"), Some(25)),
        ),
        body.contains("\"partial\":[\"auth\"]"),
        body.contains("\"nextCursor\":\"next\""),
      )
    },
    test("a request without the central credential is refused and asks nothing of the service") {
      val service = stub[AuditReportService]
      for response <- run(Request.get(base.addQueryParam("userId", userId.toString)), service, authorized = false)
      yield assertTrue(response.status == Status.Unauthorized, service.userReport.calls.isEmpty)
    },
    test("a user that is not a UUID, and a window that is not an instant, are a bad request") {
      val service = stub[AuditReportService]
      for
        user <- run(Request.get(base.addQueryParam("userId", "someone")), service)
        window <- run(Request.get(base.addQueryParam("userId", userId.toString).addQueryParam("from", "yesterday")), service)
        missing <- run(Request.get(base), service)
      yield assertTrue(
        user.status == Status.BadRequest,
        window.status == Status.BadRequest,
        missing.status == Status.BadRequest,
        service.userReport.calls.isEmpty,
      )
    },
    test("what the service refuses is a bad request with its reason") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.failsWith(AuditReportError.Invalid("limit is 1 to 500"))
        response <- run(Request.get(base.addQueryParam("userId", userId.toString).addQueryParam("limit", "0")), service)
        body <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body == "limit is 1 to 500")
    },
    test("a report that finds no place free is 503, with its error code") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.failsWith(AuditReportError.Busy)
        response <- run(Request.get(base.addQueryParam("userId", userId.toString)), service)
      yield assertTrue(response.status == Status.ServiceUnavailable)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.sequential @@ TestAspect.silentLogging
