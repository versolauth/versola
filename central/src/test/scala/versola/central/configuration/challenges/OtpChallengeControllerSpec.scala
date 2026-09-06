package versola.central.configuration.challenges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
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

  private val template =
    OtpTemplateRecord("default", tenantId, Map("en" -> "Code: {{code}}"), purpose = OtpTemplatePurpose.otp, channel = OtpTemplateChannel.sms)

  private def settings(
      authConversationTtlSeconds: Int = 111,
      sessionTtlSeconds: Int = 222,
      sessionIdleTtlSeconds: Option[Int] = Some(333),
      userAgentTtlSeconds: Int = 444,
      acrVocabulary: Option[Map[String, List[String]]] = Some(Map("a" -> List("b"))),
      postLogoutRedirectUris: List[String] = List("https://existing.example/logout"),
  ): ChallengeSettingsRecord =
    ChallengeSettingsRecord(
      tenantId = tenantId,
      allowedPrefixes = List("+1"),
      submissionLimits = SubmissionLimits.empty,
      otpLength = 6,
      otpResendAfter = 30,
      passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
      authConversationTtlSeconds = authConversationTtlSeconds,
      sessionTtlSeconds = sessionTtlSeconds,
      sessionIdleTtlSeconds = sessionIdleTtlSeconds,
      userAgentTtlSeconds = userAgentTtlSeconds,
      ipHeader = "X-Forwarded-For",
      acrVocabulary = acrVocabulary,
      postLogoutRedirectUris = postLogoutRedirectUris,
    )

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
      settingsSetup: Stub[ChallengeSettingsService] => UIO[Unit] = _ => ZIO.unit,
      settingsVerify: (Response, Stub[ChallengeSettingsService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[OtpChallengeService]
        challengeSettingsService = stub[ChallengeSettingsService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            OtpChallengeController.routes.provideEnvironment(
              ZEnvironment[OtpChallengeService](service) ++
                ZEnvironment[ChallengeSettingsService](challengeSettingsService) ++
                tracing ++ ZEnvironment[CentralConfig](config) ++ ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService),
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        _ <- settingsSetup(challengeSettingsService)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
        settingsVerifyResult <- settingsVerify(response, challengeSettingsService)
      yield assertTrue(response.status == expectedStatus) && verifyResult && settingsVerifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("OtpChallengeController")(
    controllerTestCase(
      description = "GET otp-templates returns tenant templates",
      request = Request.get(
        (URL.empty / "configuration" / "challenges" / "otp-templates")
          .addQueryParam("tenantId", tenantId.toString),
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
        body =
          Body.fromString(UpsertOtpTemplateRequest(template.id, template.tenantId, template.localizations, template.purpose, template.channel).toJson),
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
        body = Body.fromString(DeleteOtpTemplateRequest(template.id, template.tenantId, template.purpose, OtpTemplateChannel.sms).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTemplate.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteTemplate.calls == List((template.id, template.tenantId, template.purpose, template.channel)))),
    ),
    controllerTestCase(
      description = "GET challenge-settings returns the tenant's settings",
      request = Request.get(
        (URL.empty / "configuration" / "challenges" / "challenge-settings")
          .addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      settingsSetup = service => service.getSettings.succeedsWith(Some(settings())),
      settingsVerify = (response, service) =>
        for payload <- response.body.asJson[GetChallengeSettingsResponse]
        yield assertTrue(
          service.getSettings.calls == List(tenantId),
          payload == GetChallengeSettingsResponse(Some(settings())),
        ),
    ),
    controllerTestCase(
      description = "GET challenge-settings/sync returns all settings for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "challenges" / "challenge-settings" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      settingsSetup = service => service.getAllSettings.succeedsWith(Vector(settings())),
      settingsVerify = (response, service) =>
        for payload <- response.body.asJson[GetAllChallengeSettingsResponse]
        yield assertTrue(
          service.getAllSettings.calls.length == 1,
          payload == GetAllChallengeSettingsResponse(Vector(settings())),
        ),
    ),
    controllerTestCase(
      description = "reject challenge-settings/sync request without service token",
      request = Request.get(URL.empty / "configuration" / "challenges" / "challenge-settings" / "sync"),
      expectedStatus = Status.Unauthorized,
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.getAllSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "PUT challenge-settings prefers request-provided optional fields over existing settings",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            allowedPrefixes = List("+1"),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = Some(10),
            sessionTtlSeconds = Some(20),
            sessionIdleTtlSeconds = Some(30),
            userAgentTtlSeconds = Some(40),
            ipHeader = "X-Forwarded-For",
            acrVocabulary = Some(Map("x" -> List("y"))),
            postLogoutRedirectUris = Some(List("https://new.example/logout")),
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(
            settings(
              authConversationTtlSeconds = 10,
              sessionTtlSeconds = 20,
              sessionIdleTtlSeconds = Some(30),
              userAgentTtlSeconds = 40,
              acrVocabulary = Some(Map("x" -> List("y"))),
              postLogoutRedirectUris = List("https://new.example/logout"),
            ),
          ),
        )),
    ),
    controllerTestCase(
      description = "PUT challenge-settings falls back to existing settings when optional fields are omitted",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            allowedPrefixes = List("+1"),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(Some(settings())) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertSettings.calls == List(settings()))),
    ),
    controllerTestCase(
      description = "PUT challenge-settings falls back to defaults when there are no existing settings and fields are omitted",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "challenges" / "challenge-settings",
        body = Body.fromString(
          UpsertChallengeSettingsRequest(
            tenantId = tenantId,
            allowedPrefixes = List("+1"),
            submissionLimits = SubmissionLimits.empty,
            otpLength = 6,
            otpResendAfter = 30,
            passkeySettings = PasskeySettings("rp", "RP Name", List("https://rp.example"), "preferred"),
            authConversationTtlSeconds = None,
            sessionTtlSeconds = None,
            sessionIdleTtlSeconds = None,
            userAgentTtlSeconds = None,
            ipHeader = "X-Forwarded-For",
            acrVocabulary = None,
            postLogoutRedirectUris = None,
          ).toJson,
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      settingsSetup = service =>
        service.getSettings.succeedsWith(None) *> service.upsertSettings.succeedsWith(()),
      settingsVerify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.upsertSettings.calls == List(
            settings(
              authConversationTtlSeconds = 900,
              sessionTtlSeconds = 86400,
              sessionIdleTtlSeconds = None,
              userAgentTtlSeconds = 15552000,
              acrVocabulary = None,
              postLogoutRedirectUris = Nil,
            ),
          ),
        )),
    ),
  )
