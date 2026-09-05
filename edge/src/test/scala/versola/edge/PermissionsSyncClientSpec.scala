package versola.edge

import versola.edge.model.{PermissionId, ResourceEndpointId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.UUID

object PermissionsSyncClientSpec extends ZIOSpecDefault:
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

  private case class PermissionRecordMirror(id: String, endpointIds: Set[UUID]) derives JsonCodec
  private case class ResponseMirror(permissions: Vector[PermissionRecordMirror]) derives JsonCodec

  def spec = suite("PermissionsSyncClient")(
    test("maps permission ids to their endpoint ids") {
      val endpointA = UUID.randomUUID()
      val endpointB = UUID.randomUUID()
      val body = ResponseMirror(
        Vector(PermissionRecordMirror("oauth:read", Set(endpointA, endpointB))),
      ).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = PermissionsSyncClient.Impl(client, config, centralSyncTokenService)
        permissions <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/permissions/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        permissions == Map(
          PermissionId("oauth:read") -> Set(ResourceEndpointId(endpointA), ResourceEndpointId(endpointB)),
        ),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
