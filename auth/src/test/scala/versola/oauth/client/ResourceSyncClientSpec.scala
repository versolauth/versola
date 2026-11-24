package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ResourceId, ResourceUri, TenantId}
import versola.util.{Base64, RsaKeyPair, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object ResourceSyncClientSpec extends ZIOSpecDefault:

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private def encryptedTo(pair: RsaKeyPair, plaintext: Array[Byte]): ZIO[SecurityService, Throwable, String] =
    ZIO.serviceWithZIO[SecurityService](_.encryptRsaHybrid(plaintext, pair.publicKey)).map(Base64.urlEncode)

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
    test("maps the resource registry and decrypts current + previous auth secrets with the auth's own key") {
      for
        current <- encryptedTo(TestEnvConfig.authKeyPair, Array.fill(32)(1.toByte))
        previous <- encryptedTo(TestEnvConfig.authKeyPair, Array.fill(32)(2.toByte))
        body = RegistryResponseMirror(
          resources = Vector(resource),
          authResourceSecret = Some(current),
          authResourcePreviousSecret = Some(previous),
        ).toJson
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        service = ResourceSyncClient.Impl(TestEnvConfig.coreConfig, security, tokenService(client))
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
        result.authResourceSecrets.head.sameElements(Array.fill(32)(1.toByte)),
        result.authResourceSecrets.last.sameElements(Array.fill(32)(2.toByte)),
      )
    },
    test("fails the sync for a secret encrypted to another key, rather than serving none") {
      for
        forSomeoneElse <- encryptedTo(TestEnvConfig.anotherCellKeyPair, Array.fill(32)(1.toByte))
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            ZIO.succeed(Response.json(RegistryResponseMirror(Vector(resource), Some(forSomeoneElse), None).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        result <- ResourceSyncClient.Impl(TestEnvConfig.coreConfig, security, tokenService(client)).getAll.either
      yield assertTrue(result.isLeft)
    },
    test("returns no auth secrets when central sends none") {
      val body = RegistryResponseMirror(Vector.empty, None, None).toJson
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ => ZIO.succeed(Response.json(body)) }.toRoutes,
        )
        client <- ZIO.service[Client]
        security <- ZIO.service[SecurityService]
        service = ResourceSyncClient.Impl(TestEnvConfig.coreConfig, security, tokenService(client))
        result <- service.getAll
      yield assertTrue(result.resources.isEmpty, result.authResourceSecrets.isEmpty)
    },
  ).provide(TestClient.layer, SecureRandom.live, SecurityService.live) @@ TestAspect.silentLogging
