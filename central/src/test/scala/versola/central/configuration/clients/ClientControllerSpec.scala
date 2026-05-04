package versola.central.configuration.clients

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.CentralConfig
import versola.central.configuration.*
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import versola.util.{Base64, Base64Url, JWT, RedirectUri, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object ClientControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("web-app")
  private val redirectUri1 = RedirectUri("https://example.com/callback")
  private val redirectUri2 = RedirectUri("https://example.com/alt-callback")
  private val readScope = ScopeToken("read")
  private val writeScope = ScopeToken("write")
  private val readPermission = Permission("users:read")
  private val writePermission = Permission("users:write")
  private val currentSecret = Secret(Array.fill(48)(1.toByte))
  private val previousSecret = Secret(Array.fill(48)(2.toByte))
  private val rotatedSecret = Secret(Array.fill(32)(3.toByte))
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val config = CentralConfig(
    initialize = false,
    clientSecretsPepper = Secret(Array.fill(16)(5.toByte)),
    secretKey = secretKey,
  )
  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj("tenantId" -> Json.Str(tenantId.toString))),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val createRequest = CreateClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = "Web App",
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    audience = List(ClientId("api")),
    permissions = Set(readPermission),
    accessTokenTtl = 300,
  )

  private val updateRequest = UpdateClientRequest(
    tenantId = tenantId,
    clientId = clientId,
    clientName = Some("Updated Web App"),
    redirectUris = PatchClientRedirectUris(
      add = Set(redirectUri2),
      remove = Set(redirectUri1),
    ),
    scope = PatchClientScope(
      add = Set(writeScope),
      remove = Set(readScope),
    ),
    permissions = PatchPermissions(
      add = Set(writePermission),
      remove = Set(readPermission),
    ),
    accessTokenTtl = Some(900L),
  )

  private val clients = Vector(
    OAuthClientRecord(
      id = clientId,
      tenantId = tenantId,
      clientName = "Web App",
      redirectUris = Set(redirectUri1),
      scope = Set(readScope),
      externalAudience = List(ClientId("api")),
      secret = Some(currentSecret),
      previousSecret = None,
      accessTokenTtl = 5.minutes,
      permissions = Set(readPermission),
    ),
    OAuthClientRecord(
      id = ClientId("mobile-app"),
      tenantId = tenantId,
      clientName = "Mobile App",
      redirectUris = Set(redirectUri2),
      scope = Set(writeScope),
      externalAudience = List(ClientId("api")),
      secret = Some(currentSecret),
      previousSecret = Some(previousSecret),
      accessTokenTtl = 10.minutes,
      permissions = Set(writePermission),
    ),
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val securityLayer: ULayer[SecurityService] =
    SecureRandom.live >>> SecurityService.live

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[OAuthClientService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[OAuthClientService], SecurityService) => Task[TestResult] = (_, _, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[OAuthClientService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        securityService = security.get[SecurityService]
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment(service) ++ ZEnvironment(config) ++ tracing ++ security,
            ),
          ),
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service, securityService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def decryptSyncedSecret(value: String, securityService: SecurityService): Task[Secret] =
    for
      encrypted <- ZIO.attempt(Base64.urlDecode(value))
      decrypted <- securityService.decryptAes256(encrypted, config.secretKey)
    yield Secret(decrypted)

  private case class DecryptedSyncOAuthClientRecord(
      id: String,
      clientName: String,
      redirectUris: Set[RedirectUri],
      scope: Set[ScopeToken],
      externalAudience: List[ClientId],
      secret: Option[Chunk[Byte]],
      previousSecret: Option[Chunk[Byte]],
      accessTokenTtl: Duration,
  )

  private def decryptSyncedClient(
      client: SyncOAuthClientRecord,
      securityService: SecurityService,
  ): Task[DecryptedSyncOAuthClientRecord] =
    for
      secret <- ZIO.foreach(client.secret)(decryptSyncedSecret(_, securityService))
      previousSecret <- ZIO.foreach(client.previousSecret)(decryptSyncedSecret(_, securityService))
    yield DecryptedSyncOAuthClientRecord(
      id = client.id,
      clientName = client.clientName,
      redirectUris = client.redirectUris,
      scope = client.scope,
      externalAudience = client.externalAudience,
      secret = secret.map(Chunk.fromArray),
      previousSecret = previousSecret.map(Chunk.fromArray),
      accessTokenTtl = client.accessTokenTtl,
    )

  def spec = suite("ClientController")(
    controllerTestCase(
      description = "return tenant clients with pagination params",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "clients")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "5", "limit" -> "10")),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantClients.succeedsWith(clients),
      verify = (response, service, _) =>
        for
          payload <- response.body.asJson[GetAllClientsResponse]
          calls = service.getTenantClients.calls
        yield assertTrue(
          calls == List((tenantId, 5, Some(10))),
          payload == GetAllClientsResponse(
            List(
              OAuthClientResponse(
                id = clientId,
                clientName = "Web App",
                redirectUris = Set(redirectUri1),
                scope = Set(readScope),
                permissions = Set(readPermission),
                secretRotation = false,
              ),
              OAuthClientResponse(
                id = ClientId("mobile-app"),
                clientName = "Mobile App",
                redirectUris = Set(redirectUri2),
                scope = Set(writeScope),
                permissions = Set(writePermission),
                secretRotation = true,
              ),
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "clients")
          .addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantClients.succeedsWith(Vector.empty),
      verify = (response, service, _) =>
        for
          payload <- response.body.asJson[GetAllClientsResponse]
        yield assertTrue(
          service.getTenantClients.calls == List((tenantId, 0, None)),
          payload == GetAllClientsResponse(Nil),
        ),
    ),
    controllerTestCase(
      description = "return synced tenant clients with encrypted secrets for authorized service token",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "clients" / "sync")
          .addQueryParam("tenantId", tenantId.toString),
      ).addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllClients.succeedsWith(clients),
      verify = (response, service, securityService) =>
        for
          payload <- response.body.asJson[GetOAuthClientsSyncResponse]
          decryptedClients <- ZIO.foreach(payload.clients)(decryptSyncedClient(_, securityService))
          decryptedPepper <- securityService.decryptAes256(Base64.urlDecode(payload.pepper), secretKey)
        yield assertTrue(
          service.getAllClients.calls.length == 1,
          decryptedPepper.sameElements(config.clientSecretsPepper),
          decryptedClients == Vector(
            DecryptedSyncOAuthClientRecord(
              id = clientId.toString,
              clientName = "Web App",
              redirectUris = Set(redirectUri1),
              scope = Set(readScope),
              externalAudience = List(ClientId("api")),
              secret = Some(Chunk.fromArray(currentSecret)),
              previousSecret = None,
              accessTokenTtl = 5.minutes,
            ),
            DecryptedSyncOAuthClientRecord(
              id = "mobile-app",
              clientName = "Mobile App",
              redirectUris = Set(redirectUri2),
              scope = Set(writeScope),
              externalAudience = List(ClientId("api")),
              secret = Some(Chunk.fromArray(currentSecret)),
              previousSecret = Some(Chunk.fromArray(previousSecret)),
              accessTokenTtl = 10.minutes,
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "reject synced tenant clients request without service token",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "clients" / "sync")
          .addQueryParam("tenantId", tenantId),
      ),
      expectedStatus = Status.Unauthorized,
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.getAllClients.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "create client and return encoded secret",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "clients",
        body = Body.fromString(createRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.registerClient.succeedsWith(rotatedSecret),
      verify = (response, service, _) =>
        for
          body <- response.body.asJson[CreateClientResponse]
        yield assertTrue(
          service.registerClient.calls == List(createRequest),
          body == CreateClientResponse(Base64Url.encode(rotatedSecret)),
        ),
    ),
    controllerTestCase(
      description = "update client and return no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "clients",
        body = Body.fromString(updateRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.updateClient.calls == List(updateRequest)),
        ),
    ),
    controllerTestCase(
      description = "rotate client secret and return encoded value",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "v1" / "configuration" / "clients" / "rotate-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.rotateClientSecret.succeedsWith(rotatedSecret),
      verify = (response, service, _) =>
        for
          body <- response.body.asJson[RotateSecretResponse]
        yield assertTrue(
          service.rotateClientSecret.calls == List((tenantId, clientId)),
          body == RotateSecretResponse(Base64Url.encode(rotatedSecret)),
        ),
    ),
    controllerTestCase(
      description = "delete previous client secret",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "clients" / "previous-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deletePreviousClientSecret.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.deletePreviousClientSecret.calls == List((tenantId, clientId))),
        ),
    ),
    controllerTestCase(
      description = "delete client",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "clients")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deleteClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.deleteClient.calls == List((tenantId, clientId))),
        ),
    ),
  )
