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
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
