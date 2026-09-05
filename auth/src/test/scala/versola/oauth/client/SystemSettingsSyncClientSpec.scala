package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.SystemSettingsRecord
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object SystemSettingsSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("SystemSettingsSyncClient")(
    test("fetches system settings from central") {
      val settings = SystemSettingsRecord.default
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(settings.toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = SystemSettingsSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/system-settings/sync"),
        result == settings,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
