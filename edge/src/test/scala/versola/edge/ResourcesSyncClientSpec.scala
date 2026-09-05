package versola.edge

import versola.edge.model.{EdgeId, ResourceId}
import versola.util.{Base64, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator

object ResourcesSyncClientSpec extends ZIOSpecDefault:
  private val encryptedSecret = Array.fill(32)(3.toByte)
  private val decryptedSecret = Array.fill(32)(7.toByte)
  private val token = "central-sync-token"

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val securityService = new SecurityService:
    override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
    override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
    override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
    override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = ZIO.succeed(decryptedSecret)
    override def mac(secret: Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
    override def hashPassword(password: Secret, salt: versola.util.Salt, pepper: Secret.Bytes16) = ZIO.dieMessage("Unused in test")
    override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(token)

  def spec = suite("ResourcesSyncClient")(
    test("decrypts synced resource secrets") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(
              Response.json(
                s"""{"resources":[{"resourceId":"central","resource":"https://central.example","endpoints":[],"secret":"${Base64.urlEncode(encryptedSecret)}"}]}""",
              )
            )
          }.toRoutes
        )
        client <- ZIO.service[Client]
        service = ResourcesSyncClient.Impl(client, config, securityService, centralSyncTokenService)
        resources <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("Central sync request was not captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.encode.contains("configuration/resources/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(token)),
        resources(ResourceId("central")).secret.exists(_.sameElements(decryptedSecret)),
      )
    },
  ).provide(TestClient.layer)