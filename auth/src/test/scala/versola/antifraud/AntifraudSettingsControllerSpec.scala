package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.model.TenantId
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
  private val tenant = TenantId("tenant-a")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val base = URL.empty / "antifraud" / "settings"
  private val effective = Tuning.off
  private val patch = TuningPatch(
    challengeAt = None,
    reviewAt = None,
    blockAt = Some(80),
    fakeSendDelayMillis = None,
    pow = None,
    thresholds = None,
    smsLimits = None,
    ruleWeights = None,
    shadowRules = Some(Set("velocity_ip_1m")),
  )

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

  private def body(settings: String, tenantId: Option[String] = None) =
    Body.fromString(s"""{${tenantId.fold("")(t => s""""tenantId":"$t",""")}"settings":$settings,"userId":"$userId"}""")

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
    test("GET gives the stored row and what applies") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(Some(patch))
        _ = tuning.effective.returns(_ => effective)
        response <- call(tuning, Request.get(withUser("tenantId" -> "tenant-a")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[AntifraudSettingsController.SettingsView] == Right(AntifraudSettingsController.SettingsView(Some(tenant), Some(patch), effective)),
        tuning.stored.calls == List(Some(tenant)),
      )
    },
    test("PUT stores the patch for the scope as the user central names") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.succeedsWith(())
        _ = tuning.effective.returns(_ => effective)
        response <- call(tuning, Request.put(base, body(patch.toJson, Some("tenant-a"))))
        global <- call(tuning, Request.put(base, body(patch.toJson)))
      yield assertTrue(
        response.status == Status.Ok,
        global.status == Status.Ok,
        tuning.put.calls == List((Some(tenant), patch, userId), (None, patch, userId)),
      )
    },
    test("a patch the service refuses is 400 with its reason") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(InvalidTuning("blockAt must be between 0 and 100000"))
        response <- call(tuning, Request.put(base, body(patch.toJson)))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("DELETE removes the row, and is 404 where there is none") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.delete.succeedsWith(true)
        removed <- call(tuning, Request.delete(withUser("tenantId" -> "tenant-a")))
        _ <- tuning.delete.succeedsWith(false)
        missing <- call(tuning, Request.delete(withUser()))
      yield assertTrue(removed.status == Status.NoContent, missing.status == Status.NotFound, tuning.delete.calls.head == (Some(tenant), userId))
    },
    test("audit takes tenantId or global, not both") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.audit.succeedsWith(Nil)
        both <- call(tuning, Request.get(withUser("tenantId" -> "tenant-a", "global" -> "true").path(base.path / "audit")))
        global <- call(tuning, Request.get(withUser("global" -> "true").path(base.path / "audit")))
      yield assertTrue(both.status == Status.BadRequest, global.status == Status.Ok, tuning.audit.calls.map(_._1) == List(Some(None)))
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
