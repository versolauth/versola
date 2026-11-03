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

import java.util.UUID

object AntifraudCountersControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val reset = URL.empty / "antifraud" / "counters" / "reset"
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
  private val attacker = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f2"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** What edge does to the request: `userId` as a body field, replacing what the client sent. */
  private def asAdmin(request: Request, userId: UserId): Task[Request] =
    request.body.asString.map: raw =>
      val fields = raw.fromJson[Json.Obj].toOption.fold(Chunk.empty[(String, Json)])(_.fields).filterNot((name, _) => name == "userId")
      request.copy(body = Body.fromString(Json.Obj(fields :+ ("userId" -> Json.Str(userId.toString))).toJson))

  private def run(request: Request, service: AntifraudCountersService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudCountersController.routes.provideEnvironment(
            ZEnvironment[AntifraudCountersService](service) ++ ZEnvironment[ResourceService](resources) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def runAs(request: Request, service: AntifraudCountersService, userId: UserId = admin) =
    asAdmin(request, userId).flatMap(run(_, service))

  private def post(body: String) = Request.post(reset, Body.fromString(body))

  def spec = suite("central AntifraudCountersController")(
    test("without the central credentials the endpoint is 401 and the service is not called") {
      val service = stub[AntifraudCountersService]
      for
        response <- asAdmin(post("""{"scope":"all"}"""), admin).flatMap(run(_, service, authorized = false))
      yield assertTrue(response.status == Status.Unauthorized, service.reset.calls.isEmpty)
    },
    test("a reset of a value is handed to the service with the userId edge added, whatever the client sent, and answers 202 with the generation") {
      val service = stub[AntifraudCountersService]
      val body = s"""{"scope":"velocity","valueType":"phone","value":"+77011234567","userId":"$attacker"}"""
      for
        _ <- service.reset.succeedsWith(ResetCountersResponse(12))
        response <- runAs(post(body), service)
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Accepted,
        text.fromJson[ResetCountersResponse] == Right(ResetCountersResponse(12)),
        service.reset.calls == List(
          ResetCountersRequest(CounterScope.velocity, Some(CounterValueType.phone), Some("+77011234567"), admin),
        ),
      )
    },
    test("a reset of a whole scope names no value") {
      val service = stub[AntifraudCountersService]
      for
        _ <- service.reset.succeedsWith(ResetCountersResponse(1))
        budget <- runAs(post("""{"scope":"budget"}"""), service)
        all <- runAs(post("""{"scope":"all"}"""), service)
      yield assertTrue(
        budget.status == Status.Accepted,
        all.status == Status.Accepted,
        service.reset.calls.map(r => (r.scope, r.valueType, r.value)) ==
          List((CounterScope.budget, None, None), (CounterScope.all, None, None)),
      )
    },
    test("an unknown scope, a value type the counters have no key for, a body that is no request, or no userId is 400 and reaches nothing") {
      val service = stub[AntifraudCountersService]
      for
        unknownScope <- runAs(post("""{"scope":"everything"}"""), service)
        asn <- runAs(post("""{"scope":"velocity","valueType":"asn","value":"64500"}"""), service)
        deviceId <- runAs(post("""{"scope":"velocity","valueType":"device_id","value":"x"}"""), service)
        noScope <- runAs(post("{}"), service)
        notAnObject <- runAs(post("[]"), service)
        malformed <- runAs(post("{"), service)
        noUser <- run(post("""{"scope":"all"}"""), service)
      yield assertTrue(
        List(unknownScope, asn, deviceId, noScope, notAnObject, malformed, noUser).forall(_.status == Status.BadRequest),
        service.reset.calls.isEmpty,
      )
    },
    test("what auth refused is 400 with its reason") {
      val service = stub[AntifraudCountersService]
      for
        _ <- service.reset.failsWith(AntifraudListError.Invalid("a value goes with its valueType"))
        invalid <- runAs(post("""{"scope":"velocity","value":"1.2.3.4"}"""), service)
        text <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, text == "a value goes with its valueType")
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
