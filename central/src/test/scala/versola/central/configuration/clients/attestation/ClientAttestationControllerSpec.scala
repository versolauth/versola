package versola.central.configuration.clients.attestation

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.attestation.AttestationFixtures.*
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

/** What the operator is told: the policy as stored, and for each refusal the status that says what to change. */
object ClientAttestationControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val url = (URL.root / "configuration" / "clients" / "attestation").addQueryParam("clientId", "mobile")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def call(
      service: Stub[ClientAttestationService],
      request: Request,
      authorized: Boolean = true,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      resources = stub[ResourceService]
      _ <- resources.verifySecret.succeedsWith(true)
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          ClientAttestationController.routes.provideEnvironment(
            ZEnvironment[ClientAttestationService](service) ++ ZEnvironment[ResourceService](resources) ++
              ZEnvironment(TestCentralConfig.config) ++ ZEnvironment[EdgeService](stub[EdgeService]) ++ tracing,
          ),
        ),
      )
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def refuses(kind: ClientAttestationService.Refusal) = ClientAttestationService.Refused(kind, "because")

  private def put(body: String) = Request.put(url, Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("ClientAttestationController")(
    test("GET answers with the policy") {
      val service = stub[ClientAttestationService]
      for
        _ <- service.find.succeedsWith(enforcing)
        response <- call(service, Request.get(url))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[ClientAttestation] == Right(enforcing),
        service.find.calls == List(ClientId("mobile")),
      )
    },
    test("PUT stores the policy and answers with it as stored") {
      val service = stub[ClientAttestationService]
      for
        _ <- service.put.succeedsWith(enforcing)
        response <- call(service, put(enforcing.toJson))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[ClientAttestation] == Right(enforcing),
        service.put.calls == List(ClientId("mobile") -> enforcing),
      )
    },
    test("DELETE takes the policy away") {
      val service = stub[ClientAttestationService]
      for
        _ <- service.delete.succeedsWith(())
        response <- call(service, Request.delete(url))
      yield assertTrue(response.status == Status.NoContent, service.delete.calls == List(ClientId("mobile")))
    },
    test("an unknown client is 404, a client that is not native 422, a policy that cannot be stored 400") {
      val service = stub[ClientAttestationService]
      for
        _ <- service.find.failsWith(refuses(ClientAttestationService.Refusal.UnknownClient))
        _ <- service.put.failsWith(refuses(ClientAttestationService.Refusal.NotNative))
        _ <- service.delete.failsWith(refuses(ClientAttestationService.Refusal.Invalid))
        unknown <- call(service, Request.get(url))
        notNative <- call(service, put(enforcing.toJson))
        invalid <- call(service, Request.delete(url))
      yield assertTrue(unknown.status == Status.NotFound, notNative.status == Status.UnprocessableEntity, invalid.status == Status.BadRequest)
    },
    test("a request without the administrator's credentials reaches nothing, whichever the method") {
      val service = stub[ClientAttestationService]
      for
        put <- call(service, put(enforcing.toJson), authorized = false)
        get <- call(service, Request.get(url), authorized = false)
        delete <- call(service, Request.delete(url), authorized = false)
      yield assertTrue(
        List(put, get, delete).forall(_.status == Status.Unauthorized),
        service.put.calls.isEmpty,
        service.find.calls.isEmpty,
        service.delete.calls.isEmpty,
      )
    },
    test("a body that is not a policy is 400, and nothing is stored") {
      val service = stub[ClientAttestationService]
      for
        malformed <- call(service, put("{not json"))
        unknownMode <- call(service, put("""{"mode":"always","android":[]}"""))
        misspelt <- call(service, put("""{"mode":"enforce","IOS":{"appIds":[],"environment":"production"},"android":[]}"""))
      yield assertTrue(
        List(malformed, unknownMode, misspelt).forall(_.status == Status.BadRequest),
        service.put.calls.isEmpty,
      )
    },
  ).provideSomeLayer[Scope](TestClient.layer) @@ TestAspect.silentLogging
