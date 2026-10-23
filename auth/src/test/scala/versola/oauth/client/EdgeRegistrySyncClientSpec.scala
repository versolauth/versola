package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.TenantId
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

/** [[EdgeRegistrySyncClient]] mirrors [[ResourceSyncClient]]: central's edges registry, ids and the
  * tenants each edge is assigned to, keyed by edge id. */
object EdgeRegistrySyncClientSpec extends ZIOSpecDefault:

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private def respondWith(body: String) =
    TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(body))).toRoutes)

  def spec = suite("EdgeRegistrySyncClient")(
    test("requests central's edges registry and keys the result by edge id, with each edge's tenants") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { r =>
            seen.set(Some(r)).as(
              Response.json("""{"edges":[{"id":"edge-1","tenantIds":["tenant-a","tenant-b"]},{"id":"edge-2","tenantIds":[]}]}"""),
            )
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        result <- EdgeRegistrySyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)).getAll
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/edges/registry"),
        result == Map("edge-1" -> Set(TenantId("tenant-a"), TenantId("tenant-b")), "edge-2" -> Set.empty[TenantId]),
      )
    },
    test("returns an empty registry when central has no edges") {
      for
        _ <- respondWith("""{"edges":[]}""")
        client <- ZIO.service[Client]
        result <- EdgeRegistrySyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)).getAll
      yield assertTrue(result.isEmpty)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
