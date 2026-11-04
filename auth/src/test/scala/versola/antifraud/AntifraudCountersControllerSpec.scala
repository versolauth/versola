package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.util.JWT
import versola.util.audit.AuditEvents
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudCountersControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val audit = CapturingAuditEvents.shared()
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val url = URL.empty / "antifraud" / "counters" / "reset"

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(
      counters: AntifraudCounters,
      request: Request,
      key: SecretKey = config.central.secretKey,
      signed: Boolean = true,
      events: AuditEvents = audit,
  ) =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudCountersController.routes.provideEnvironment(
            ZEnvironment(counters) ++ ZEnvironment(config) ++ ZEnvironment[AuditEvents](events) ++ tracing,
          ),
        ),
      )
      sent <-
        if signed then
          token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))
        else ZIO.succeed(request.addHeader(Header.ContentType(MediaType.application.json)))
      response <- client.batched(sent)
    yield response

  private def body(json: String) = Body.fromString(json.dropRight(1) + s""","userId":"$userId"}""")

  def spec = suite("AntifraudCountersController")(
    test("a request without a token, or with one signed by another key, is 401 and resets nothing") {
      val counters = stub[AntifraudCounters]
      for
        anonymous <- call(counters, Request.post(url, body("""{"scope":"all"}""")), signed = false)
        forged <- call(counters, Request.post(url, body("""{"scope":"all"}""")), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, counters.reset.calls.isEmpty)
    },
    test("202 with the generation, and the request as the admin central names sent it") {
      val counters = stub[AntifraudCounters]
      for
        _ <- counters.reset.succeedsWith(7L)
        response <- call(counters, Request.post(url, body("""{"scope":"velocity","valueType":"phone","value":"+77011234567"}""")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Accepted,
        text == """{"generation":7}""",
        counters.reset.calls == List((CounterScope.velocity, Some(CounterValueType.phone), Some("+77011234567"), userId)),
      )
    },
    test("records antifraud.counters_reset with the administrator, the scope and the generation, and not the value that was reset") {
      val counters = stub[AntifraudCounters]
      val audit = CapturingAuditEvents.shared()
      for
        _ <- counters.reset.succeedsWith(7L)
        _ <- call(counters, Request.post(url, body("""{"scope":"velocity","valueType":"phone","value":"+77011234567"}""")), events = audit)
        drafts <- audit.recorded
      yield assertTrue(
        drafts.map(_.kind) == List(AuthAuditKinds.AntifraudCountersReset),
        drafts.head.actor == Some((AuthActors.admin, Some(userId))),
        drafts.head.details == AuthAuditDetails.AntifraudCountersReset("velocity", Some("phone"), 7L),
        !drafts.head.details.toJson.toString.contains("77011234567"),
      )
    },
    test("a reset the service refuses records nothing") {
      val counters = stub[AntifraudCounters]
      val audit = CapturingAuditEvents.shared()
      for
        _ <- counters.reset.failsWith(InvalidCounterReset("no"))
        _ <- call(counters, Request.post(url, body("""{"scope":"all","value":"1"}""")), events = audit)
        drafts <- audit.recorded
      yield assertTrue(drafts.isEmpty)
    },
    test("the whole scope needs no value") {
      val counters = stub[AntifraudCounters]
      for
        _ <- counters.reset.succeedsWith(1L)
        response <- call(counters, Request.post(url, body("""{"scope":"budget"}""")))
      yield assertTrue(response.status == Status.Accepted, counters.reset.calls == List((CounterScope.budget, None, None, userId)))
    },
    test("a request the service refuses is 400 with its reason") {
      val counters = stub[AntifraudCounters]
      for
        _ <- counters.reset.failsWith(InvalidCounterReset("give valueType and value together, or neither"))
        response <- call(counters, Request.post(url, body("""{"scope":"all","value":"1"}""")))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "give valueType and value together, or neither")
    },
    test("a scope or a value type that is not one is 400, and a request without userId is refused, before the service") {
      val counters = stub[AntifraudCounters]
      for
        scope <- call(counters, Request.post(url, body("""{"scope":"everything"}""")))
        kind <- call(counters, Request.post(url, body("""{"scope":"velocity","valueType":"asn","value":"1"}""")))
        noUser <- call(counters, Request.post(url, Body.fromString("""{"scope":"all"}""")))
      yield assertTrue(
        scope.status.isClientError,
        kind.status.isClientError,
        noUser.status.isClientError,
        counters.reset.calls.isEmpty,
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
