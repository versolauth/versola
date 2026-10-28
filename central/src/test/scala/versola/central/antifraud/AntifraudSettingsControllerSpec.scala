package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.central.users.UserId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudSettingsControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val base = URL.empty / "antifraud" / "settings"
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
  private val attacker = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f2"))

  private val limit = VelocityLimitDto(perMinute = 0, perTenMinutes = 0, perHour = 3, perDay = 0, weight = 50)
  private val settings = AntifraudSettings(
    challengeAt = 0,
    reviewAt = 0,
    blockAt = 80,
    pow = PowDifficultyDto(14, 18, 15),
    thresholds = VelocityThresholdsDto(limit, limit, limit, limit, limit, 40),
    smsLimits = SmsLimitsDto(SmsLimitDto(10, 0, 0.1), Map("49" -> SmsLimitDto(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** What edge does to the request: `userId` as a query parameter of a `GET`, as a body field of the rest, replacing what the client sent. */
  private def asAdmin(request: Request, userId: UserId): Task[Request] =
    if request.method == Method.GET then
      ZIO.succeed(request.updateURL(url => url.setQueryParams(url.queryParams.removeQueryParam("userId").addQueryParam("userId", userId.toString))))
    else
      request.body.asString.map: raw =>
        val fields = raw.fromJson[Json.Obj].toOption.fold(Chunk.empty[(String, Json)])(_.fields).filterNot((name, _) => name == "userId")
        request.copy(body = Body.fromString(Json.Obj(fields :+ ("userId" -> Json.Str(userId.toString))).toJson))

  private def run(request: Request, service: AntifraudSettingsService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudSettingsController.routes.provideEnvironment(
            ZEnvironment[AntifraudSettingsService](service) ++ ZEnvironment[ResourceService](resources) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def runAs(request: Request, service: AntifraudSettingsService, userId: UserId = admin) =
    asAdmin(request, userId).flatMap(run(_, service))

  private def put(body: String) = Request.put(base, Body.fromString(body))
  private def delete(body: String) = Request.delete(base).withBody(Body.fromString(body))
  private val putBody = s"""{"settings":${settings.toJson}}"""

  def spec = suite("central AntifraudSettingsController")(
    test("without the central credentials every endpoint is 401 and the service is not called") {
      val service = stub[AntifraudSettingsService]
      for
        statuses <- ZIO.foreach(List(Request.get(base), put(putBody), delete("{}"))): r =>
          asAdmin(r, admin).flatMap(run(_, service, authorized = false)).map(_.status)
      yield assertTrue(
        statuses.forall(_ == Status.Unauthorized),
        service.get.calls.isEmpty,
        service.put.calls.isEmpty,
        service.reset.calls.isEmpty,
      )
    },
    test("a put is handed to the service with the userId edge added, whatever the client sent") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.succeedsWith(settings)
        response <- runAs(put(putBody.dropRight(1) + s""","userId":"$attacker"}"""), service)
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[AntifraudSettings] == Right(settings),
        service.put.calls == List(PutAntifraudSettingsRequest(settings, admin)),
      )
    },
    test("a get takes the token's user") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.get.succeedsWith(settings)
        response <- runAs(Request.get(base.addQueryParam("userId", attacker.toString)), service)
        body <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.fromJson[AntifraudSettings] == Right(settings), service.get.calls == List(admin))
    },
    test("a delete resets, with the userId edge added to the body") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.reset.succeedsWith(())
        deleted <- runAs(delete("{}"), service)
      yield assertTrue(deleted.status == Status.NoContent, service.reset.calls == List(DeleteAntifraudSettingsRequest(admin)))
    },
    test("a request without a userId reaches nothing") {
      val service = stub[AntifraudSettingsService]
      for
        put <- run(Request.put(base, Body.fromString(putBody)), service)
        get <- run(Request.get(base), service)
      yield assertTrue(put.status == Status.BadRequest, get.status == Status.BadRequest, service.put.calls.isEmpty, service.get.calls.isEmpty)
    },
    test("what auth refused is 400 with its reason") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.failsWith(AntifraudListError.Invalid("blockAt must be between 0 and 100000"))
        invalid <- runAs(put(putBody), service)
        text <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, text.contains("blockAt must be between"))
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
