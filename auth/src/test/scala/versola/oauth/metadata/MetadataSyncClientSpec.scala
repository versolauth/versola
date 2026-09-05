package versola.oauth.metadata

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object MetadataSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("MetadataSyncClient")(
    test("fetches server metadata overrides from central") {
      val overrides = Json.Obj("service_documentation" -> Json.Str("https://docs.example"))
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(overrides.toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = MetadataSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/server-metadata/sync"),
        result == overrides,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
