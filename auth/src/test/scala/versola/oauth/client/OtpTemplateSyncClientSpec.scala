package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{OtpTemplateChannel, OtpTemplatePurpose, OtpTemplateRecord, TenantId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object OtpTemplateSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("OtpTemplateSyncClient")(
    test("fetches OTP templates from central") {
      val record = OtpTemplateRecord(
        id = "signup-otp",
        tenantId = TenantId.default,
        localizations = Map("en" -> "Your code is {code}"),
        purpose = OtpTemplatePurpose.otp,
        channel = OtpTemplateChannel.sms,
      )
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(OtpTemplateSyncClient.TemplatesResponse(Vector(record)).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = OtpTemplateSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        templates <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/challenges/otp-templates/sync"),
        templates == Vector(record),
      )
    },
    test("reads the subjects of a template, and none from a central that does not send them") {
      val withSubjects =
        """{"id":"t","tenantId":"default","localizations":{"en":"<p>{{code}}</p>"},"purpose":"otp","channel":"email","subjects":{"en":"Your code"}}"""
      val withoutSubjects =
        """{"id":"t","tenantId":"default","localizations":{"en":"<p>{{code}}</p>"},"purpose":"otp","channel":"email"}"""
      assertTrue(
        withSubjects.fromJson[OtpTemplateRecord].map(_.subjects) == Right(Map("en" -> "Your code")),
        withoutSubjects.fromJson[OtpTemplateRecord].map(_.subjects) == Right(Map.empty[String, String]),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
