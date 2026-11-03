package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object AntifraudStatusControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val rules = URL.empty / "antifraud" / "rules"
  private val status = URL.empty / "antifraud" / "status"

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def run(request: Request, service: AntifraudStatusService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudStatusController.routes.provideEnvironment(
            ZEnvironment[AntifraudStatusService](service) ++ ZEnvironment[ResourceService](resources) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private val statusResponse = AntifraudStatusResponse(
    decision = AntifraudDecisionStatus(enabled = true),
    lists = AntifraudListsStatus(enabled = true, snapshotAgeSeconds = Some(12), size = 40, skipped = 2, lastRefreshError = Some("db down")),
    tuning = AntifraudTuningStatus(available = true, version = Some(7), ageSeconds = Some(3), lastRefreshError = None),
  )

  def spec = suite("central AntifraudStatusController")(
    test("without the central credentials neither endpoint answers, and the service is not called") {
      val service = stub[AntifraudStatusService]
      for
        r <- run(Request.get(rules), service, authorized = false)
        s <- run(Request.get(status), service, authorized = false)
      yield assertTrue(r.status == Status.Unauthorized, s.status == Status.Unauthorized, service.rules.calls.isEmpty, service.status.calls.isEmpty)
    },
    test("the rule catalogue is auth's, passed on as it is, and a read names no caller") {
      val service = stub[AntifraudStatusService]
      val catalogue = AntifraudRulesResponse(List("velocity_phone_1m", "deny_list:ip"))
      for
        _ <- service.rules.succeedsWith(catalogue)
        response <- run(Request.get(rules), service)
        body <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.fromJson[AntifraudRulesResponse] == Right(catalogue))
    },
    test("the status is auth's, passed on as it is") {
      val service = stub[AntifraudStatusService]
      for
        _ <- service.status.succeedsWith(statusResponse)
        response <- run(Request.get(status), service)
        body <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.fromJson[AntifraudStatusResponse] == Right(statusResponse))
    },
    test("a failure of the call to auth is a server error, not an answer") {
      val service = stub[AntifraudStatusService]
      for
        _ <- service.status.failsWith(RuntimeException("auth is down"))
        response <- run(Request.get(status), service)
      yield assertTrue(response.status == Status.InternalServerError)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
