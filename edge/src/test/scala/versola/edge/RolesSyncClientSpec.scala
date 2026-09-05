package versola.edge

import versola.edge.model.{PermissionId, RoleId, TenantId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object RolesSyncClientSpec extends ZIOSpecDefault:
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val config = EdgeConfig(
    versola.edge.model.EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(versola.util.Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(versola.util.Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  private case class RoleRecordMirror(
      tenantId: String,
      id: String,
      permissions: Set[String],
      active: Boolean,
  ) derives JsonCodec
  private case class ResponseMirror(roles: Vector[RoleRecordMirror]) derives JsonCodec

  def spec = suite("RolesSyncClient")(
    test("maps (tenant, role) to permission ids, dropping inactive roles") {
      val body = ResponseMirror(
        Vector(
          RoleRecordMirror("tenant-a", "admin", Set("oauth:read", "oauth:write"), active = true),
          RoleRecordMirror("tenant-a", "disabled-role", Set("oauth:read"), active = false),
        ),
      ).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = RolesSyncClient.Impl(client, config, centralSyncTokenService)
        roles <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/roles/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        roles == Map(
          (TenantId("tenant-a"), RoleId("admin")) -> Set(PermissionId("oauth:read"), PermissionId("oauth:write")),
        ),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
