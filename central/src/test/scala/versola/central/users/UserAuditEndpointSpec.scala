package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.audit.{AuditCursor, AuditReportError, AuditReportService, ReportWindow, UserAuditReport}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.Base64Url
import versola.util.http.Observability
import versola.util.{EnvName, PageLimit}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import java.util.UUID

object UserAuditEndpointSpec extends ZIOSpecDefault, ZIOStubs:
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
    truncated = false,
  )

  private def run(request: Request, service: AuditReportService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      users = stub[UserService]
      edge = stub[EdgeService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          UserController.routes.provideEnvironment(
            ZEnvironment[AuditReportService](service) ++ ZEnvironment[ResourceService](resources) ++ ZEnvironment[UserService](users) ++
              ZEnvironment[EdgeService](edge) ++ ZEnvironment[CentralConfig](TestCentralConfig.config) ++ ZEnvironment[EnvName](
                EnvName.Test("test"),
              ) ++
              tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private val base = URL.empty / "users" / "audit"
  private val cursor = AuditCursor(at = Instant.parse("2026-11-02T00:00:00Z"), id = userId, centralOnly = false)
  private val cursorParam = AuditCursor.encode(cursor)

  def spec = suite("UserController: the audit report")(
    test("the report is served with the user, the window, the cursor and the page size the request named") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.succeedsWith(report)
        response <- run(
          Request.get(
            base.addQueryParam("id", userId.toString).addQueryParam("from", "2026-11-01T00:00:00Z").addQueryParam("to", "2026-11-03T00:00:00Z")
              .addQueryParam("cursor", cursorParam).addQueryParam("limit", "25"),
          ),
          service,
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        service.userReport.calls == List(
          (userId, Some(Instant.parse("2026-11-01T00:00:00Z")), Some(Instant.parse("2026-11-03T00:00:00Z")), Some(cursor), Some(PageLimit(25))),
        ),
        body.contains("\"partial\":[\"auth\"]"),
        body.contains("\"nextCursor\":\"next\""),
      )
    },
    test("a request without the central credential is refused and asks nothing of the service") {
      val service = stub[AuditReportService]
      for response <- run(Request.get(base.addQueryParam("id", userId.toString)), service, authorized = false)
      yield assertTrue(response.status == Status.Unauthorized, service.userReport.calls.isEmpty)
    },
    test("a malformed userId, from, to, limit or cursor is a bad request with the error code set, and reaches nothing") {
      val service = stub[AuditReportService]
      val good = base.addQueryParam("id", userId.toString)
      val garbage = Base64Url.encode("{}".getBytes("UTF-8")).toString
      val notJson = Base64Url.encode("not json".getBytes("UTF-8")).toString
      def get(url: URL) = run(Request.get(url), service)
      for
        missing <- get(base)
        user <- get(base.addQueryParam("id", "someone"))
        from <- get(good.addQueryParam("from", "yesterday"))
        to <- get(good.addQueryParam("to", "2026-13-45"))
        zero <- get(good.addQueryParam("limit", "0"))
        huge <- get(good.addQueryParam("limit", "501"))
        word <- get(good.addQueryParam("limit", "many"))
        bytes <- get(good.addQueryParam("cursor", "%%%"))
        emptyObject <- get(good.addQueryParam("cursor", garbage))
        text <- get(good.addQueryParam("cursor", notJson))
        codes <- ZIO.foreach(List(missing, user, from, to, zero, huge, word, bytes, emptyObject, text))(response =>
          response.body.asString.map(response.status -> _),
        )
      yield assertTrue(
        codes.forall(_._1 == Status.BadRequest),
        codes.forall(_._2.nonEmpty),
        service.userReport.calls.isEmpty,
      )
    },
    test("what the service refuses is a bad request with its reason") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.failsWith(AuditReportError.Invalid("to is before from"))
        response <- run(
          Request.get(base.addQueryParam(
            "id",
            userId.toString,
          ).addQueryParam("from", "2026-11-03T00:00:00Z").addQueryParam("to", "2026-11-01T00:00:00Z")),
          service,
        )
        body <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body == "to is before from")
    },
    test("a report that finds no place free is 503, with its error code") {
      val service = stub[AuditReportService]
      for
        _ <- service.userReport.failsWith(AuditReportError.Busy)
        response <- run(Request.get(base.addQueryParam("id", userId.toString)), service)
      yield assertTrue(response.status == Status.ServiceUnavailable)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.sequential @@ TestAspect.silentLogging
