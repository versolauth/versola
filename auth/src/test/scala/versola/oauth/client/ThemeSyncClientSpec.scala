package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ThemeRecord
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object ThemeSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("ThemeSyncClient")(
    test("fetches themes from central") {
      val theme = ThemeRecord("default", "body { color: black; }", tenantId = None)
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(ThemeSyncClient.ThemesResponse(Vector(theme)).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = ThemeSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        themes <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/themes/sync"),
        themes == Vector(theme),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
