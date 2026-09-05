package versola.edge

import versola.edge.model.{ClientId, EdgeId, PermissionId}
import versola.util.{Base64, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.security.KeyPairGenerator

object OAuthClientsSyncClientSpec extends ZIOSpecDefault:
  private val decryptedSecretA = Array.fill(32)(3.toByte)
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val config = EdgeConfig(
    EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private def fakeSecurityService(decryptedByCiphertext: Map[String, Array[Byte]]): SecurityService =
    new SecurityService:
      override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
      override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
      override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
      override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) =
        ZIO.succeed(decryptedByCiphertext(Base64.urlEncode(data)))
      override def mac(macInput: Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
      override def hashPassword(pw: Secret, salt: versola.util.Salt, pepper: Secret.Bytes16) =
        ZIO.dieMessage("Unused in test")
      override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  private case class SyncClientRecordMirror(
      id: ClientId,
      secret: Option[String],
      accessTokenTtl: Duration,
      permissions: Set[String] = Set.empty,
  ) derives JsonCodec

  private case class SyncResponseMirror(clients: Vector[SyncClientRecordMirror]) derives JsonCodec

  def spec = suite("OAuthClientsSyncClient")(
    test("decrypts every client with a secret and drops clients missing one") {
      val secretACiphertext = Base64.urlEncode(Array.fill(32)(30.toByte))
      val body = SyncResponseMirror(
        Vector(
          SyncClientRecordMirror(ClientId("with-secret"), Some(secretACiphertext), 15.minutes, Set("oauth:read")),
          SyncClientRecordMirror(ClientId("no-secret"), None, 30.minutes),
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
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(secretACiphertext -> decryptedSecretA)),
          centralSyncTokenService,
        )
        clients <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/clients/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        clients.keySet == Set(ClientId("with-secret")),
        clients(ClientId("with-secret")).secret.sameElements(decryptedSecretA),
        clients(ClientId("with-secret")).permissions == Set(PermissionId("oauth:read")),
        clients(ClientId("with-secret")).accessTokenTtl == 15.minutes,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
