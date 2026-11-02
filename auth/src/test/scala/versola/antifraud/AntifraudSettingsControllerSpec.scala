package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.util.JWT
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudSettingsControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val base = URL.empty / "antifraud" / "settings"
  private val settings = Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(tuning: AntifraudTuningStore, request: Request, key: SecretKey = config.central.secretKey): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(tuning) ++ ZEnvironment(config) ++ tracing),
        ),
      )
      signed <- token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))
      response <- client.batched(signed)
    yield response

  private def withUser(params: (String, String)*) = base.addQueryParams(QueryParams(("userId" -> userId), params*))

  private def body(settings: String) = Body.fromString(s"""{"settings":$settings,"userId":"$userId"}""")

  def spec = suite("AntifraudSettingsController")(
    test("a request without a token, or with one signed by another key, is 401 and reaches nothing") {
      val tuning = stub[AntifraudTuningStore]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(tuning) ++ ZEnvironment(config) ++ tracing),
          ),
        )
        anonymous <- client.batched(Request.get(base))
        forged <- call(tuning, Request.get(withUser()), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, tuning.stored.calls.isEmpty)
    },
    test("GET gives the stored settings with their version, in the body and as the ETag") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(StoredTuning(settings, 3))
        response <- call(tuning, Request.get(withUser()))
        text <- response.body.asString
        json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[Tuning] == Right(settings),
        json.get("version").contains(Json.Num(3)),
        response.headers.get("ETag").contains("\"3\""),
      )
    },
    test("with no row GET gives everything off at version 0") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(StoredTuning(Tuning.off, 0))
        response <- call(tuning, Request.get(withUser()))
        text <- response.body.asString
      yield assertTrue(text.fromJson[Tuning] == Right(Tuning.off), response.headers.get("ETag").contains("\"0\""))
    },
    test("PUT replaces the settings as the user central names, on the version in If-Match, and answers the new version") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.succeedsWith(4L)
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"3\"")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[Tuning] == Right(settings),
        text.fromJson[Json.Obj].exists(_.get("version").contains(Json.Num(4))),
        response.headers.get("ETag").contains("\"4\""),
        tuning.put.calls == List((settings, userId, Some(3L))),
      )
    },
    test("PUT takes the version from the body when there is no If-Match; both must agree, and a header that is not a version is 400") {
      val tuning = stub[AntifraudTuningStore]
      def withVersion(version: Long) = Body.fromString(s"""{"settings":${settings.toJson},"userId":"$userId","version":$version}""")
      for
        _ <- tuning.put.succeedsWith(8L)
        fromBody <- call(tuning, Request.put(base, withVersion(7)))
        agree <- call(tuning, Request.put(base, withVersion(7)).addHeader(Header.Custom("If-Match", "7")))
        differ <- call(tuning, Request.put(base, withVersion(7)).addHeader(Header.Custom("If-Match", "\"6\"")))
        garbage <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "*")))
      yield assertTrue(
        fromBody.status == Status.Ok,
        agree.status == Status.Ok,
        differ.status == Status.BadRequest,
        garbage.status == Status.BadRequest,
        tuning.put.calls.map(_._3) == List(Some(7L), Some(7L)),
      )
    },
    test("PUT that names no version is 428 and writes nothing") {
      val tuning = stub[AntifraudTuningStore]
      for
        response <- call(tuning, Request.put(base, body(settings.toJson)))
      yield assertTrue(response.status == Status.PreconditionRequired, tuning.put.calls.isEmpty)
    },
    test("PUT on a version the settings have left is 412 with the version they are at as the ETag") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(VersionMismatch(5))
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"3\"")))
      yield assertTrue(response.status == Status.PreconditionFailed, response.headers.get("ETag").contains("\"5\""))
    },
    test("settings the service refuses are 400 with its reason") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(InvalidTuning("blockAt must be between 0 and 100000"))
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"1\"")))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("DELETE puts the seeded values back, and does not have to name a version; one it names is checked") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.reset.succeedsWith(6L)
        plain <- call(tuning, Request.delete(withUser()))
        named <- call(tuning, Request.delete(withUser()).addHeader(Header.Custom("If-Match", "\"5\"")))
        _ <- tuning.reset.failsWith(VersionMismatch(9))
        stale <- call(tuning, Request.delete(withUser()).addHeader(Header.Custom("If-Match", "\"5\"")))
      yield assertTrue(
        plain.status == Status.NoContent,
        named.status == Status.NoContent,
        stale.status == Status.PreconditionFailed,
        stale.headers.get("ETag").contains("\"9\""),
        tuning.reset.calls == List((userId, None), (userId, Some(5L)), (userId, Some(5L))),
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
