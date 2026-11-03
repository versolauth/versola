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
    mode = AntifraudMode.enforce,
    challengeAt = 0,
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
  private val mergePatch = MediaType.parseCustomMediaType("application/merge-patch+json").get
  private def patch(body: String, mediaType: MediaType = mergePatch) =
    Request(method = Method.PATCH, url = base, body = Body.fromString(body)).addHeader(Header.ContentType(mediaType))
  private def delete(body: String) = Request.delete(base).withBody(Body.fromString(body))
  private val putBody = s"""{"settings":${settings.toJson}}"""

  def spec = suite("central AntifraudSettingsController")(
    test("without the central credentials every endpoint is 401 and the service is not called") {
      val service = stub[AntifraudSettingsService]
      for
        statuses <- ZIO.foreach(List(Request.get(base), put(putBody), patch("{}"), delete("{}"))): r =>
          asAdmin(r, admin).flatMap(run(_, service, authorized = false)).map(_.status)
      yield assertTrue(
        statuses.forall(_ == Status.Unauthorized),
        service.get.calls.isEmpty,
        service.put.calls.isEmpty,
        service.patch.calls.isEmpty,
        service.reset.calls.isEmpty,
      )
    },
    test("a put is handed to the service with the userId edge added, whatever the client sent, on the version in If-Match") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.succeedsWith(VersionedAntifraudSettings(settings, 4))
        response <- runAs(put(putBody.dropRight(1) + s""","userId":"$attacker"}""").addHeader(Header.Custom("If-Match", "\"3\"")), service)
        body <- response.body.asString
        json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[AntifraudSettings] == Right(settings),
        json.get("version").contains(Json.Num(4)),
        response.headers.get("ETag").contains("\"4\""),
        service.put.calls == List(PutAntifraudSettingsRequest(settings, admin, Some(3))),
      )
    },
    test("a put takes the version from the body when there is no If-Match; both given must agree, and one that is no version is 400") {
      val service = stub[AntifraudSettingsService]
      val withVersion = putBody.dropRight(1) + ""","version":7}"""
      for
        _ <- service.put.succeedsWith(VersionedAntifraudSettings(settings, 8))
        fromBody <- runAs(put(withVersion), service)
        agree <- runAs(put(withVersion).addHeader(Header.Custom("If-Match", "7")), service)
        differ <- runAs(put(withVersion).addHeader(Header.Custom("If-Match", "\"6\"")), service)
        garbage <- runAs(put(putBody).addHeader(Header.Custom("If-Match", "W/\"3\"")), service)
        any <- runAs(put(putBody).addHeader(Header.Custom("If-Match", "*")), service)
      yield assertTrue(
        fromBody.status == Status.Ok,
        agree.status == Status.Ok,
        differ.status == Status.BadRequest,
        garbage.status == Status.BadRequest,
        any.status == Status.BadRequest,
        service.put.calls.map(_.version) == List(Some(7L), Some(7L)),
      )
    },
    test("a put that names no version replaces the settings unconditionally: the service is told there is none to check") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.succeedsWith(VersionedAntifraudSettings(settings, 5))
        response <- runAs(put(putBody), service)
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get("ETag").contains("\"5\""),
        service.put.calls == List(PutAntifraudSettingsRequest(settings, admin, None)),
      )
    },
    test("a put on a version the settings have left is 412, with the version they are at as the ETag") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.failsWith(AntifraudListError.StaleVersion("the settings are at version 5", Some(5)))
        stale <- runAs(put(putBody).addHeader(Header.Custom("If-Match", "\"3\"")), service)
        _ <- service.put.failsWith(AntifraudListError.StaleVersion("changed", None))
        unknown <- runAs(put(putBody).addHeader(Header.Custom("If-Match", "\"3\"")), service)
      yield assertTrue(
        stale.status == Status.PreconditionFailed,
        stale.headers.get("ETag").contains("\"5\""),
        unknown.status == Status.PreconditionFailed,
        unknown.headers.get("ETag").isEmpty,
      )
    },
    test("a get needs no caller to name, and answers the settings with their version as the ETag") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.get.succeedsWith(VersionedAntifraudSettings(settings, 3))
        response <- run(Request.get(base), service)
        body <- response.body.asString
        json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[AntifraudSettings] == Right(settings),
        json.get("version").contains(Json.Num(3)),
        response.headers.get("ETag").contains("\"3\""),
        service.get.calls.size == 1,
      )
    },
    test("a delete resets, with the userId edge added to the body, and need not name a version; one it names is passed on") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.reset.succeedsWith(())
        deleted <- runAs(delete("{}"), service)
        named <- runAs(delete("{}").addHeader(Header.Custom("If-Match", "\"4\"")), service)
        _ <- service.reset.failsWith(AntifraudListError.StaleVersion("changed", Some(9)))
        stale <- runAs(delete("{}").addHeader(Header.Custom("If-Match", "\"4\"")), service)
      yield assertTrue(
        deleted.status == Status.NoContent,
        named.status == Status.NoContent,
        stale.status == Status.PreconditionFailed,
        service.reset.calls == List(
          (DeleteAntifraudSettingsRequest(admin), None),
          (DeleteAntifraudSettingsRequest(admin), Some(4L)),
          (DeleteAntifraudSettingsRequest(admin), Some(4L)),
        ),
      )
    },
    test("a patch is handed to the service without the userId edge added and the version, which are taken out of it") {
      val service = stub[AntifraudSettingsService]
      val body = """{"blockAt":90,"smsLimits":{"byPrefix":{"49":null}},"userId":"%s","version":3}""".format(attacker)
      for
        _ <- service.patch.succeedsWith(VersionedAntifraudSettings(settings.copy(blockAt = 90), 4))
        response <- runAs(patch(body), service)
        text <- response.body.asString
        json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        json.get("version").contains(Json.Num(4)),
        json.get("blockAt").contains(Json.Num(90)),
        response.headers.get("ETag").contains("\"4\""),
        service.patch.calls == List(
          PatchAntifraudSettingsRequest(
            patch = Json.Obj("blockAt" -> Json.Num(90), "smsLimits" -> Json.Obj("byPrefix" -> Json.Obj("49" -> Json.Null))),
            userId = admin,
            version = Some(3),
          ),
        ),
      )
    },
    test("a patch takes its version from If-Match or the body, needs none, and one that differs is 400") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.patch.succeedsWith(VersionedAntifraudSettings(settings, 4))
        none <- runAs(patch("""{"blockAt":90}"""), service)
        header <- runAs(patch("""{"blockAt":90}""").addHeader(Header.Custom("If-Match", "\"7\"")), service)
        body <- runAs(patch("""{"blockAt":90,"version":8}"""), service)
        differ <- runAs(patch("""{"blockAt":90,"version":8}""").addHeader(Header.Custom("If-Match", "\"7\"")), service)
        garbage <- runAs(patch("""{"blockAt":90}""").addHeader(Header.Custom("If-Match", "*")), service)
        badVersion <- runAs(patch("""{"blockAt":90,"version":"x"}"""), service)
      yield assertTrue(
        none.status == Status.Ok,
        header.status == Status.Ok,
        body.status == Status.Ok,
        differ.status == Status.BadRequest,
        garbage.status == Status.BadRequest,
        badVersion.status == Status.BadRequest,
        service.patch.calls.map(_.version) == List(None, Some(7L), Some(8L)),
      )
    },
    test("a patch on a version the settings have left is 412 with the current version, and one auth refuses is 400 with its reason") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.patch.failsWith(AntifraudListError.StaleVersion("the settings are at version 5", Some(5)))
        stale <- runAs(patch("""{"blockAt":90}""").addHeader(Header.Custom("If-Match", "\"3\"")), service)
        _ <- service.patch.failsWith(AntifraudListError.Invalid("blockAt must be between 0 and 100000"))
        invalid <- runAs(patch("""{"blockAt":-1}"""), service)
        reason <- invalid.body.asString
      yield assertTrue(
        stale.status == Status.PreconditionFailed,
        stale.headers.get("ETag").contains("\"5\""),
        invalid.status == Status.BadRequest,
        reason.contains("blockAt must be between"),
      )
    },
    test(
      "a patch is JSON: other media types are 415, application/json is taken, a body that is no object or has no userId is 400, nothing reaches the service",
    ) {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.patch.succeedsWith(VersionedAntifraudSettings(settings, 4))
        plain <- runAs(patch("""{"blockAt":90}""", MediaType.text.plain), service)
        form <- runAs(patch("""{"blockAt":90}""", MediaType.application.`x-www-form-urlencoded`), service)
        json <- runAs(patch("""{"blockAt":90}""", MediaType.application.json), service)
        array <- run(patch("""[{"blockAt":90,"userId":"%s"}]""".format(admin)), service)
        scalar <- run(patch("""90"""), service)
        noUser <- run(patch("""{"blockAt":90}"""), service)
        unauthorized <- asAdmin(patch("""{"blockAt":90}"""), admin).flatMap(run(_, service, authorized = false))
      yield assertTrue(
        plain.status == Status.UnsupportedMediaType,
        form.status == Status.UnsupportedMediaType,
        json.status == Status.Ok,
        array.status == Status.BadRequest,
        scalar.status == Status.BadRequest,
        noUser.status == Status.BadRequest,
        unauthorized.status == Status.Unauthorized,
        service.patch.calls.size == 1,
      )
    },
    test("a put without a userId reaches nothing") {
      val service = stub[AntifraudSettingsService]
      for
        put <- run(Request.put(base, Body.fromString(putBody)).addHeader(Header.Custom("If-Match", "\"3\"")), service)
      yield assertTrue(put.status == Status.BadRequest, service.put.calls.isEmpty)
    },
    test("what auth refused is 400 with its reason") {
      val service = stub[AntifraudSettingsService]
      for
        _ <- service.put.failsWith(AntifraudListError.Invalid("blockAt must be between 0 and 100000"))
        invalid <- runAs(put(putBody).addHeader(Header.Custom("If-Match", "\"3\"")), service)
        text <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, text.contains("blockAt must be between"))
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
