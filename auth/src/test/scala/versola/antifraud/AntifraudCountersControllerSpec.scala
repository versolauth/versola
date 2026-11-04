package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.audit.AuthAuditKinds
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
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val url = URL.empty / "antifraud" / "counters" / "reset"

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(counters: AntifraudCounters, request: Request, key: SecretKey = config.central.secretKey, signed: Boolean = true) =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudCountersController.routes.provideEnvironment(
            ZEnvironment(counters) ++ ZEnvironment(config) ++ ZEnvironment[AuditEvents](AuditEvents.noop) ++ tracing,
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
        counters.reset.calls.map((scope, valueType, value, actor, _) => (scope, valueType, value, actor)) ==
          List((CounterScope.velocity, Some(CounterValueType.phone), Some("+77011234567"), userId)),
        // The event is built by the controller and handed down, for the repository to insert with the reset.
        counters.reset.calls.map(_._5.kind) == List(AuthAuditKinds.AntifraudCountersReset),
      )
    },
    test("the whole scope needs no value") {
      val counters = stub[AntifraudCounters]
      for
        _ <- counters.reset.succeedsWith(1L)
        response <- call(counters, Request.post(url, body("""{"scope":"budget"}""")))
      yield assertTrue(
        response.status == Status.Accepted,
        counters.reset.calls.map((scope, valueType, value, actor, _) => (scope, valueType, value, actor)) == List((
          CounterScope.budget,
          None,
          None,
          userId,
        )),
      )
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
