package versola.central.configuration.clients.est

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/** What auth and the operator are told: auth's token alone reaches the issuing endpoints (an edge's, and none, do not),
  * and each refusal is the status auth maps it to. */
object ClientEstControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val clientId = ClientId("bank-a")
  private val endsAt = Instant.parse("2027-10-22T00:00:00Z")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def token() = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("auth", "auth", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(SecretKeySpec(Array.fill(32)(7.toByte), "AES")),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val authToken = Header.Authorization.Bearer(token())

  private def call(estService: ClientEstService, request: Request): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      resources = stub[ResourceService]
      _ <- resources.verifySecret.succeedsWith(true)
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          ClientEstController.routes.provideEnvironment(
            ZEnvironment[ClientEstService](estService) ++ ZEnvironment[ResourceService](resources) ++
              ZEnvironment(TestCentralConfig.config) ++ ZEnvironment[EdgeService](stub[EdgeService]) ++ tracing,
          ),
        ),
      )
      response <- client.batched(request)
    yield response

  private def json(url: URL, body: String, auth: Option[Header.Authorization] = Some(authToken)) =
    val request = Request.post(url, Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))
    auth.fold(request)(request.addHeader(_))

  private val enrollUrl = URL.root / "est" / "enroll"
  private val renewUrl = URL.root / "est" / "renew"
  private val enrollBody = EstEnrollRequest("TOKEN", Some(clientId), "REQUEST").toJson
  private val renewBody = EstRenewRequest("CERT", "REQUEST").toJson

  def spec = suite("ClientEstController")(
    test("enroll answers with the chain and when it ends") {
      val service = stub[ClientEstService]
      for
        _ <- service.enroll.succeedsWith(ClientEstService.Issued("CHAIN", Some(endsAt)))
        response <- call(service, json(enrollUrl, enrollBody))
        answer <- response.body.asJson[EstIssuedResponse]
      yield assertTrue(
        response.status == Status.Ok,
        answer == EstIssuedResponse("CHAIN", Some(endsAt)),
        service.enroll.calls == List(("TOKEN", Some(clientId), "REQUEST")),
      )
    },
    test("renew answers with the chain") {
      val service = stub[ClientEstService]
      for
        _ <- service.renew.succeedsWith(ClientEstService.Issued("CHAIN", None))
        response <- call(service, json(renewUrl, renewBody))
      yield assertTrue(response.status == Status.Ok, service.renew.calls == List(("CERT", "REQUEST")))
    },
    test("a bad token or certificate is 401, with nothing to tell which") {
      val service = stub[ClientEstService]
      for
        _ <- service.enroll.failsWith(ClientEstService.InvalidCredentials)
        _ <- service.renew.failsWith(ClientEstService.InvalidCredentials)
        enrol <- call(service, json(enrollUrl, enrollBody))
        renew <- call(service, json(renewUrl, renewBody))
        text <- enrol.body.asString
      yield assertTrue(enrol.status == Status.Unauthorized, renew.status == Status.Unauthorized, text.isEmpty)
    },
    test("a request the registration does not allow is 400, and a client that may not have a certificate now is 403") {
      val service = stub[ClientEstService]
      for
        _ <- service.enroll.failsWith(ClientEstService.Refused(ClientEstService.Refusal.BadRequest, "wrong subject"))
        _ <- service.renew.failsWith(ClientEstService.Refused(ClientEstService.Refusal.Forbidden, "chain limit"))
        enrol <- call(service, json(enrollUrl, enrollBody))
        renew <- call(service, json(renewUrl, renewBody))
        enrolText <- enrol.body.asString
      yield assertTrue(enrol.status == Status.BadRequest, enrolText == "wrong subject", renew.status == Status.Forbidden)
    },
    // A token that names an edge is refused for every route of `authOnly` by `refuseEdgeCallers` (EdgeCallerRefusalSpec).
    test("the issuing endpoints refuse a caller with no token, and one with the operator's credential") {
      val service = stub[ClientEstService]
      for
        anonymous <- call(service, json(enrollUrl, enrollBody, auth = None))
        operator <- call(service, json(renewUrl, renewBody, auth = Some(TestAdminAuth.basicAuthHeader)))
      yield assertTrue(
        anonymous.status == Status.Unauthorized,
        operator.status == Status.Unauthorized,
        service.enroll.calls.isEmpty,
        service.renew.calls.isEmpty,
      )
    },
    test("an operator creates a token for a client, which is shown with the subject to ask for") {
      val service = stub[ClientEstService]
      for
        _ <- service.issueEnrollmentToken.succeedsWith(ClientEstService.IssuedToken("TOKEN", endsAt, "CN=bank-a"))
        response <- call(
          service,
          Request.post(URL.root / "configuration" / "clients" / "est" / "enrollment-tokens" ++ QueryParams("clientId" -> "bank-a"), Body.empty)
            .addHeader(TestAdminAuth.basicAuthHeader),
        )
        answer <- response.body.asJson[EstEnrollmentTokenResponse]
      yield assertTrue(response.status == Status.Created, answer == EstEnrollmentTokenResponse("TOKEN", endsAt, "CN=bank-a"))
    },
    test("an operator extends a chain; the internal token is not an operator's") {
      val service = stub[ClientEstService]
      val extend = URL.root / "configuration" / "clients" / "est" / "extend" ++ QueryParams("clientId" -> "bank-a")
      for
        _ <- service.extend.succeedsWith(())
        extended <- call(service, Request.post(extend, Body.empty).addHeader(TestAdminAuth.basicAuthHeader))
        internal <- call(service, Request.post(extend, Body.empty).addHeader(authToken))
      yield assertTrue(extended.status == Status.NoContent, internal.status == Status.Unauthorized, service.extend.calls == List(clientId))
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
