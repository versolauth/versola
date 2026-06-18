package versola.central.configuration.challenges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object OtpChallengeControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val tenantId = TenantId("tenant-a")
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val template = OtpTemplateRecord("default", tenantId, Map("en" -> "Code: {{code}}"))

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[OtpChallengeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[OtpChallengeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client        <- ZIO.service[Client]
        service       = stub[OtpChallengeService]
        challengeSettingsService = stub[ChallengeSettingsService]
        edgeService   = stub[EdgeService]
        tracing       <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            OtpChallengeController.routes.provideEnvironment(
              ZEnvironment[OtpChallengeService](service) ++
                ZEnvironment[ChallengeSettingsService](challengeSettingsService) ++
                tracing ++ ZEnvironment(config) ++ ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _            <- setup(service)
        response     <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("OtpChallengeController")(
    controllerTestCase(
      description = "GET otp-templates returns tenant templates",
      request = Request.get(
        (URL.empty / "configuration" / "challenges" / "otp-templates")
          .addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getTemplates.succeedsWith(Vector(template)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetOtpTemplatesResponse]
        yield assertTrue(
          service.getTemplates.calls == List(tenantId),
          payload == GetOtpTemplatesResponse(Vector(template)),
        ),
    ),
    controllerTestCase(
      description = "GET otp-templates/sync returns all templates for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "challenges" / "otp-templates" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getSyncTemplates.succeedsWith(Vector(template)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetOtpTemplatesResponse]
        yield assertTrue(
          service.getSyncTemplates.calls.length == 1,
          payload == GetOtpTemplatesResponse(Vector(template)),
        ),
    ),
    controllerTestCase(
      description = "reject otp-templates/sync request without service token",
      request = Request.get(URL.empty / "configuration" / "challenges" / "otp-templates" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getSyncTemplates.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT otp-templates upserts template and returns no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "otp-templates",
        body = Body.fromString(UpsertOtpTemplateRequest(template.id, template.tenantId, template.localizations).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.upsertTemplate.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertTemplate.calls == List(template))),
    ),
    controllerTestCase(
      description = "DELETE otp-templates deletes template and returns no content",
      request = Request(
        method = Method.DELETE,
        url = URL.empty / "configuration" / "challenges" / "otp-templates",
        body = Body.fromString(DeleteOtpTemplateRequest(template.id, template.tenantId).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTemplate.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteTemplate.calls == List((template.id, template.tenantId)))),
    ),
  )
