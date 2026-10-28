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
    test("GET gives the stored settings") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(Some(settings))
        response <- call(tuning, Request.get(withUser()))
        text <- response.body.asString
      yield assertTrue(response.status == Status.Ok, text.fromJson[Tuning] == Right(settings))
    },
    test("PUT replaces the settings as the user central names") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.succeedsWith(())
        response <- call(tuning, Request.put(base, body(settings.toJson)))
        text <- response.body.asString
      yield assertTrue(response.status == Status.Ok, text.fromJson[Tuning] == Right(settings), tuning.put.calls == List((settings, userId)))
    },
    test("settings the service refuses are 400 with its reason") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(InvalidTuning("blockAt must be between 0 and 100000"))
        response <- call(tuning, Request.put(base, body(settings.toJson)))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("DELETE puts the seeded values back") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.reset.succeedsWith(())
        response <- call(tuning, Request.delete(withUser()))
      yield assertTrue(response.status == Status.NoContent, tuning.reset.calls == List(userId))
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
