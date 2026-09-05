package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{AuthorizationDetailType, AuthorizationDetailTypeRecord, TenantId}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object AuthorizationDetailTypeSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("AuthorizationDetailTypeSyncClient")(
    test("fetches authorization detail types from central") {
      val record = AuthorizationDetailTypeRecord(TenantId.default, AuthorizationDetailType("payment"), Json.Obj())
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(AuthorizationDetailTypeSyncClient.TypesResponse(Vector(record)).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = AuthorizationDetailTypeSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        types <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/authorization-detail-types/sync"),
        types == Vector(record),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
