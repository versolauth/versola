package versola.edge

import versola.edge.model.{EdgeId, ResourceEndpointId, ResourceId}
import versola.util.cel.CelEvaluator
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

  private val dummyProgram: CelEvaluator.Program = new CelEvaluator.Program:
    override def evaluateBoolean(context: Map[String, AnyRef]) = ZIO.succeed(true)
    override def evaluateString(context: Map[String, AnyRef]) = ZIO.succeed(None)

  /** Records every expression handed to `compile`, so a test can assert precompilation reached
    * every rule on a synced endpoint without depending on `CelEvaluator`'s own compile cache. */
  private def trackingEvaluator(compiled: Ref[Vector[String]]): CelEvaluator = new CelEvaluator:
    override def compile(expression: String): UIO[CelEvaluator.Program] =
      compiled.update(_ :+ expression).as(dummyProgram)
    override def validate(expression: String, expectedType: Option[dev.cel.common.types.CelType]) =
      ZIO.succeed(dummyProgram)

  private val celEvaluator = CelEvaluator.Impl(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty)))

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
        service = ResourcesSyncClient.Impl(client, config, securityService, centralSyncTokenService, celEvaluator)
        resources <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("Central sync request was not captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.encode.contains("configuration/resources/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(token)),
        resources(ResourceId("central")).secret.exists(_.sameElements(decryptedSecret)),
      )
    },
    test("precompiles allow, step-up and inject expressions from every synced endpoint") {
      val endpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-8000-000000000001"))
      for
        compiled <- Ref.make(Vector.empty[String])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            ZIO.succeed(
              Response.json(
                s"""{"resources":[{"resourceId":"central","resource":"https://central.example","endpoints":[
                  |{"id":"$endpointId","method":"GET","path":"/orders","fetchUserInfo":false,
                  |"allow":"request.body.total <= 50000",
                  |"inject":[{"target":"header","name":"X-User","expression":"token.sub"}],
                  |"stepUpCondition":"user.subscription == 'premium'",
                  |"stepUpAcr":null,"maxAge":null}],"secret":null}]}""".stripMargin,
              )
            )
          }.toRoutes
        )
        client <- ZIO.service[Client]
        service = ResourcesSyncClient.Impl(client, config, securityService, centralSyncTokenService, trackingEvaluator(compiled))
        _ <- service.getAll
        expressions <- compiled.get
      yield assertTrue(
        expressions.toSet == Set("request.body.total <= 50000", "user.subscription == 'premium'", "token.sub"),
      )
    },
  ).provide(TestClient.layer)