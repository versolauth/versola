package versola.antifraud

import versola.auth.TestEnvConfig
import versola.util.http.{NoopTracing, Observability}
import versola.util.{JWT, Patch}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudSettingsControllerSpec extends ZIOSpecDefault:

  private val config = TestEnvConfig.coreConfig
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val checkUrl = URL.empty / "antifraud" / "settings" / "check"
  private val settings = Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(request: Request, key: SecretKey = config.central.secretKey): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(config) ++ tracing)),
      )
      signed <- token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))
      response <- client.batched(signed)
    yield response

  private def blockAt(value: Int) = TuningPatch.empty.copy(blockAt = Some(Patch.Modified(value)))

  private def check(json: String) = Request.post(checkUrl, Body.fromString(json))

  private def tuningOf(response: Response): Task[Tuning] =
    response.body.asString.flatMap(text => ZIO.fromEither(text.fromJson[Tuning]).mapError(RuntimeException(_)))

  def spec = suite("AntifraudSettingsController")(
    test("a request without a token, or with one signed by another key, is 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(config) ++ tracing)),
        )
        anonymous <- client.batched(check("{}"))
        forged <- call(check("{}"), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized)
    },
    test("check of settings alone answers them as they are") {
      for
        response <- call(check(s"""{"settings":${settings.toJson}}"""))
        tuning <- tuningOf(response)
      yield assertTrue(response.status == Status.Ok, tuning == settings)
    },
    test("check of settings and a patch answers the settings the patch would make") {
      for
        response <- call(check(s"""{"settings":${settings.toJson},"patch":${blockAt(60).toJson}}"""))
        tuning <- tuningOf(response)
      yield assertTrue(response.status == Status.Ok, tuning == settings.copy(blockAt = 60))
    },
    test("check of a patch with no settings applies it to everything off") {
      for
        response <- call(check(s"""{"patch":${blockAt(60).toJson}}"""))
        tuning <- tuningOf(response)
      yield assertTrue(response.status == Status.Ok, tuning == Tuning.off.copy(blockAt = 60))
    },
    test("check of neither answers everything off") {
      for
        response <- call(check("{}"))
        tuning <- tuningOf(response)
      yield assertTrue(response.status == Status.Ok, tuning == Tuning.off)
    },
    test("check of settings that are not valid is 400 with the reason") {
      val invalid = settings.copy(blockAt = -5)
      for
        response <- call(check(s"""{"settings":${invalid.toJson}}"""))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("check of a patch whose result is not valid is 400 with the reason") {
      for
        response <- call(check(s"""{"settings":${settings.toJson},"patch":${blockAt(-5).toJson}}"""))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("check with a member the settings do not have, or a body that is not JSON, is 400") {
      for
        unknownInSettings <- call(check("""{"settings":{"blokAt":80}}"""))
        unknownInPatch <- call(check("""{"patch":{"blokAt":80}}"""))
        malformed <- call(check("{"))
      yield assertTrue(List(unknownInSettings, unknownInPatch, malformed).map(_.status) == List.fill(3)(Status.BadRequest))
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
