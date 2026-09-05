package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ResourceId, ResourceUri, TenantId}
import versola.util.{Base64, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object ResourceSyncClientSpec extends ZIOSpecDefault:
  private val decryptedSecret = Array.fill(32)(4.toByte)

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private val fakeSecurityService = new SecurityService:
    override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
    override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(decryptedSecret)
    override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
    override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = ZIO.dieMessage("Unused in test")
    override def mac(macInput: Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
    override def hashPassword(pw: Secret, salt: versola.util.Salt, pepper: Secret.Bytes16) =
      ZIO.dieMessage("Unused in test")
    override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")

  private case class RegistryEntryMirror(
      resourceId: ResourceId,
      tenantId: TenantId,
      resource: ResourceUri,
      audience: List[ClientId],
      internal: Boolean,
  ) derives JsonCodec

  private case class RegistryResponseMirror(
      resources: Vector[RegistryEntryMirror],
      authResourceSecret: Option[String],
      authResourcePreviousSecret: Option[String],
  ) derives JsonCodec

  private val resource = RegistryEntryMirror(
    ResourceId("central"),
    TenantId.default,
    ResourceUri("https://central.example"),
    List(ClientId("web-app")),
    internal = true,
  )

  def spec = suite("ResourceSyncClient")(
    test("maps the resource registry and decrypts current + previous auth secrets") {
      val body = RegistryResponseMirror(
        resources = Vector(resource),
        authResourceSecret = Some(Base64.urlEncode(Array.fill(32)(1.toByte))),
        authResourcePreviousSecret = Some(Base64.urlEncode(Array.fill(32)(2.toByte))),
      ).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = ResourceSyncClient.Impl(TestEnvConfig.coreConfig, fakeSecurityService, tokenService(client))
        result <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/resources/registry"),
        result.resources == Vector(
          versola.oauth.client.model.ResourceRecord(
            ResourceId("central"),
            TenantId.default,
            ResourceUri("https://central.example"),
            List(ClientId("web-app")),
            true,
          ),
        ),
        result.authResourceSecrets.size == 2,
        result.authResourceSecrets.forall(_.sameElements(decryptedSecret)),
      )
    },
    test("returns no auth secrets when central sends none") {
      val body = RegistryResponseMirror(Vector.empty, None, None).toJson
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ => ZIO.succeed(Response.json(body)) }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = ResourceSyncClient.Impl(TestEnvConfig.coreConfig, fakeSecurityService, tokenService(client))
        result <- service.getAll
      yield assertTrue(result.resources.isEmpty, result.authResourceSecrets.isEmpty)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
