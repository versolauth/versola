package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.JWT
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudStatusControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val rules = URL.empty / "antifraud" / "rules"
  private val status = URL.empty / "antifraud" / "status"

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(
      lists: AntifraudLists,
      tuning: AntifraudTuningStore,
      request: Request,
      decisionEnabled: Boolean = true,
      key: SecretKey = config.central.secretKey,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      settings = AntifraudTestSettings.events.copy(decision = AntifraudTestSettings.decision.copy(enabled = decisionEnabled))
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudStatusController.routes.provideEnvironment(
            ZEnvironment(lists) ++ ZEnvironment(tuning) ++ ZEnvironment(settings) ++ ZEnvironment(config) ++ tracing,
          ),
        ),
      )
      signed <- token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)))
      response <- client.batched(signed)
    yield response

  def spec = suite("AntifraudStatusController")(
    test("neither endpoint answers a request without the internal token, or one signed by another key") {
      val lists = stub[AntifraudLists]
      val tuning = stub[AntifraudTuningStore]
      for
        anonymousRules <- call(lists, tuning, Request.get(rules), key = wrongKey)
        anonymousStatus <- call(lists, tuning, Request.get(status), key = wrongKey)
      yield assertTrue(
        anonymousRules.status == Status.Unauthorized,
        anonymousStatus.status == Status.Unauthorized,
        lists.status.calls.isEmpty,
        tuning.status.calls.isEmpty,
      )
    },
    test("GET rules lists the names a rule can be fired under, once each") {
      val lists = stub[AntifraudLists]
      val tuning = stub[AntifraudTuningStore]
      for
        response <- call(lists, tuning, Request.get(rules))
        body <- response.body.asString
        names = body.fromJson[Json.Obj].toOption.flatMap(_.get("rules")).collect { case Json.Arr(items) => items.collect { case Json.Str(s) => s } }.getOrElse(Chunk.empty)
      yield assertTrue(
        response.status == Status.Ok,
        names.toList == AntifraudRuleCatalogue.names,
        names.distinct.size == names.size,
        names.contains("velocity_phone_1m"),
        names.contains("velocity_prefix_24h"),
        names.contains("velocity_known_user"),
        names.contains("deny_list:ip"),
        names.contains("shadow_list:email_domain"),
      )
    },
    test("the catalogue has every name the velocity rules are fired under") {
      val limit = AntifraudTestSettings.limit.copy(perMinute = 1, perTenMinutes = 1, perHour = 1, perDay = 1)
      val tuning = AntifraudTestSettings.tuning.copy(
        thresholds = AntifraudTestSettings.thresholds.copy(phone = limit, ip = limit, device = limit, asn = limit, prefix = limit),
      )
      val ctx = AntifraudContext(Some(TenantId("t")), ClientId("c"), AntifraudAction.otp_request, None, None, None, None, None, None, None, Map.empty)
      for
        built <- AntifraudTestSettings.velocityRules(AntifraudTestSettings.velocity.copy(enabled = true), tuning).rulesFor(ctx)
        names = built.map(_.name)
      yield assertTrue(names.size >= 21, names.forall(AntifraudRuleCatalogue.names.contains))
    },
    test("GET status says whether the decision and the lists are on, the tuning version and age, and the lists' snapshot") {
      val lists = stub[AntifraudLists]
      val tuning = stub[AntifraudTuningStore]
      for
        now <- Clock.instant
        _ <- lists.status.succeedsWith(ListsStatus(enabled = true, loadedAt = Some(now.minusSeconds(12)), size = 40, skipped = 2, lastError = None))
        _ <- tuning.status.succeedsWith(TuningStatus(available = true, version = Some(7), checkedAt = Some(now.minusSeconds(3)), lastError = None))
        response <- call(lists, tuning, Request.get(status))
        body <- response.body.asString
        parsed = body.fromJson[AntifraudStatusController.StatusResponse]
      yield assertTrue(
        response.status == Status.Ok,
        parsed.exists(_.decision.enabled),
        parsed.exists(_.lists.enabled),
        parsed.exists(l => l.lists.size == 40 && l.lists.skipped == 2 && l.lists.lastRefreshError.isEmpty),
        parsed.exists(_.lists.snapshotAgeSeconds.exists(age => age >= 12 && age < 20)),
        parsed.exists(t => t.tuning.available && t.tuning.version.contains(7L) && t.tuning.ageSeconds.exists(age => age >= 3 && age < 10)),
      )
    },
    test("GET status with everything off, nothing loaded and a refresh that failed gives no ages and the message") {
      val lists = stub[AntifraudLists]
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- lists.status.succeedsWith(ListsStatus(enabled = false, loadedAt = None, size = 0, skipped = 0, lastError = Some("db down")))
        _ <- tuning.status.succeedsWith(TuningStatus(available = false, version = None, checkedAt = None, lastError = Some("db down")))
        response <- call(lists, tuning, Request.get(status), decisionEnabled = false)
        body <- response.body.asString
        parsed = body.fromJson[AntifraudStatusController.StatusResponse]
      yield assertTrue(
        response.status == Status.Ok,
        parsed.exists(!_.decision.enabled),
        parsed.exists(_.lists == AntifraudStatusController.ListsStatusResponse(false, None, 0, 0, Some("db down"))),
        parsed.exists(_.tuning == AntifraudStatusController.TuningStatusResponse(false, None, None, Some("db down"))),
        !body.contains("AgeSeconds\":null"),
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
