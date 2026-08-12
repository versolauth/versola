package versola.central.configuration.resources

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.central.configuration.edges.{EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.*
import versola.util.{Base64Url, JWT, RsaKeyPair, Secret, SecurityService}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import zio.json.ast.Json

import java.util.UUID
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object ResourceControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val resourceId = ResourceId("users-api")
  private val audience = List(ClientId("test-client"))
  private val usersListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000301")
  private val usersCreateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000302")
  private val usersMeEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000303")
  private val allow = Some("token.role == 'admin' || token.department == 'support'")
  private val denyAware = Some("token.country != 'blocked'")
  private val numericAllow = Some("request.attempt >= 1")
  private val inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub"))

  private val createRequestBody = CreateResourceRequest(
    tenantId = tenantId,
    resourceId = resourceId,
    resource = ResourceUri("https://api.example.com"),
    audience = audience,
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, allow, inject, stepUpCondition = None, stepUpAcr = None, maxAge = None),
      CreateResourceEndpointRequest(usersCreateEndpointId, "/users", "POST", false, denyAware, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None),
    ),
    internal = false,
  )

  private val createRequestBodyWithNumericRule = CreateResourceRequest(
    tenantId = tenantId,
    resourceId = resourceId,
    resource = ResourceUri("https://api.example.com"),
    audience = audience,
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, numericAllow, Vector.empty, stepUpCondition = None, stepUpAcr = None, maxAge = None)
    ),
    internal = false,
  )

  private val updateRequestBody = UpdateResourceRequest(
    resourceId = resourceId,
    resource = Some(ResourceUri("https://api.internal.example.com")),
    audience = Some(audience),
    deleteEndpoints = Set(usersCreateEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(usersMeEndpointId, "/users/me", "GET", true, allow, inject, stepUpCondition = None, stepUpAcr = None, maxAge = None)
    ),
  )

  private val resourceRecords = Vector(
    ResourceRecord(
      tenantId = tenantId,
      resourceId = resourceId,
      resource = createRequestBody.resource,
      audience = audience,
      endpoints = Vector(
        ResourceEndpointRecord(usersCreateEndpointId, "/users/me", "POST", false, denyAware, Vector.empty, None, None, None),
        ResourceEndpointRecord(usersListEndpointId, "/users", "GET", true, allow, inject, None, None, None),
      ),
      secret = None,
      previousSecret = None,
    )
  )
  private val syncSecret = Secret(Array.fill(32)(7.toByte))
  private val previousSyncSecret = Secret(Array.fill(32)(8.toByte))
  private val edgeId = EdgeId("edge-1")
  private val edgeKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(
      keyId = "resource-controller-edge-key",
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey],
    )
  private val edgeRecord = EdgeRecord(edgeId, edgeKeyPair.toPublicJwk, None)

  private val config = TestCentralConfig.config

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](Tracing.live(logAnnotated = false), OpenTelemetry.contextZIO, ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")))

  private val securityLayer: ULayer[SecurityService] =
    ZLayer.succeed(new SecurityService:
      override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
      override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
      override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
      override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = ZIO.dieMessage("Unused in test")
      override def mac(secret: versola.util.Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
      override def hashPassword(password: versola.util.Secret, salt: versola.util.Salt, pepper: versola.util.Secret.Bytes16) = ZIO.dieMessage("Unused in test")
      override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[ResourceService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[ResourceService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(
              ZEnvironment[ResourceService](service) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _ <- service.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _    => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def decodeJsonBody[A: JsonDecoder](response: Response): Task[A] =
    response.body.asString
      .flatMap(body => ZIO.fromEither(body.fromJson[A]))
      .mapError(message => RuntimeException(s"Failed to decode JSON: $message"))

  def spec = suite("ResourceController")(
    controllerTestCase(
      description = "return tenant resources with pagination params",
      request = Request.get(
        (URL.empty / "configuration" / "resources")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "1", "limit" -> "5"))
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getTenantResources.succeedsWith(resourceRecords),
      verify = (response, service) =>
        for payload <- decodeJsonBody[GetAllResourcesResponse](response)
        yield assertTrue(
          service.getTenantResources.calls == List((tenantId, 1, Some(5))),
          payload == GetAllResourcesResponse(
            Vector(
              ResourceResponse(
                resourceId = resourceId,
                resource = createRequestBody.resource,
                audience = audience,
                endpoints = Vector(
                  ResourceEndpointResponse(usersListEndpointId, "GET", "/users", true, allow, inject, None, None, None),
                  ResourceEndpointResponse(usersCreateEndpointId, "POST", "/users/me", false, denyAware, Vector.empty, None, None, None),
                ),
                internal = false,
                secretRotation = false,
              )
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get((URL.empty / "configuration" / "resources").addQueryParam("tenantId", tenantId.toString)),
      expectedStatus = Status.Ok,
      setup = service => service.getTenantResources.succeedsWith(Vector.empty),
      verify = (response, service) =>
        for payload <- decodeJsonBody[GetAllResourcesResponse](response)
        yield assertTrue(
          service.getTenantResources.calls == List((tenantId, 0, None)),
          payload == GetAllResourcesResponse(Vector.empty),
        ),
    ),
    controllerTestCase(
      description = "create resource with endpoints",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(createRequestBody.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createResource.succeedsWith(Right((resourceId, None))),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId, None),
          service.createResource.calls == List(createRequestBody),
        ),
    ),
    controllerTestCase(
      description = "reject edge resource id",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(createRequestBody.copy(resourceId = ResourceId("edge")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.createResource.succeedsWith(Left(ResourceValidationError.ReservedResourceId)),
      verify = (_, service) => ZIO.succeed(assertTrue(
        service.createResource.calls == List(createRequestBody.copy(resourceId = ResourceId("edge"))),
      )),
    ),
    controllerTestCase(
      description = "create internal resource returns the generated secret",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(createRequestBody.copy(internal = true).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createResource.succeedsWith(Right((resourceId, Some(syncSecret)))),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId, Some(Base64Url.encode(syncSecret))),
          service.createResource.calls == List(createRequestBody.copy(internal = true)),
        ),
    ),
    controllerTestCase(
      description = "create resource with numeric ACL rule values",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(createRequestBodyWithNumericRule.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createResource.succeedsWith(Right((resourceId, None))),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId, None),
          service.createResource.calls == List(createRequestBodyWithNumericRule),
        ),
    ),
    controllerTestCase(
      description = "create resource returns bad request when allow expression invalid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(createRequestBody.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.createResource.succeedsWith(
        Left(ResourceValidationError.InvalidAllowExpression(usersListEndpointId, "token.foo +", "Unexpected token: EOF")),
      ),
    ),
    controllerTestCase(
      description = "update resource with endpoint replacements",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "resources",
        body = Body.fromString(updateRequestBody.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateResource.succeedsWith(Right(())),
      verify = (_, service) => ZIO.succeed(assertTrue(service.updateResource.calls == List(updateRequestBody))),
    ),
    controllerTestCase(
      description = "delete resource",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "resources").addQueryParam("resourceId", resourceId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteResource.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.deleteResource.calls == List(resourceId))),
    ),
    controllerTestCase(
      description = "rotate resource secret",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "resources" / "rotate-secret")
          .addQueryParam("resourceId", resourceId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.rotateSecret.succeedsWith(syncSecret),
      verify = (response, service) =>
        for body <- response.body.asString
        yield assertTrue(
          body == s"""{"secret":"${Base64Url.encode(syncSecret)}"}""",
          service.rotateSecret.calls == List(resourceId),
        ),
    ),
    controllerTestCase(
      description = "delete previous resource secret",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "resources" / "previous-secret")
          .addQueryParam("resourceId", resourceId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deletePreviousSecret.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.deletePreviousSecret.calls == List(resourceId))),
    ),
      controllerTestCase(
        description = "return conflict when resource secret rotation is already in progress",
        request = Request(
          method = Method.POST,
          url = (URL.empty / "configuration" / "resources" / "rotate-secret")
            .addQueryParam("resourceId", resourceId.toString),
        ),
        expectedStatus = Status.Conflict,
        setup = service => service.rotateSecret.failsWith(ResourceService.SecretRotationInProgress),
      ),
    test("sync returns encrypted resource secret") {
      for
        client <- ZIO.service[Client]
        service = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        token <- JWT.serialize(
          JWT.Claims("auth", "auth", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(config.secretKey),
        )
        _ <- service.getResourcesForSync.succeedsWith(Vector(resourceRecords.head.copy(secret = Some(syncSecret))))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(
              ZEnvironment[ResourceService](service) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        response <- client.batched(
          Request.get(URL.empty / "configuration" / "resources" / "sync")
            .addHeader(Header.Authorization.Bearer(token))
        )
        payload <- decodeJsonBody[GetResourcesSyncResponse](response)
      yield assertTrue(
        response.status == Status.Ok,
        payload.resources.head.secret.contains(Base64Url.encode(syncSecret)),
      )
    }.provideSomeLayer(TestClient.layer),
    test("sync keeps previous resource secret active during rotation") {
      for
        client <- ZIO.service[Client]
        service = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        token <- JWT.serialize(
          JWT.Claims("auth", "auth", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(config.secretKey),
        )
        _ <- service.getResourcesForSync.succeedsWith(Vector(resourceRecords.head.copy(
          secret = Some(syncSecret),
          previousSecret = Some(previousSyncSecret),
        )))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(
              ZEnvironment[ResourceService](service) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        response <- client.batched(
          Request.get(URL.empty / "configuration" / "resources" / "sync")
            .addHeader(Header.Authorization.Bearer(token))
        )
        payload <- decodeJsonBody[GetResourcesSyncResponse](response)
      yield assertTrue(
        response.status == Status.Ok,
        payload.resources.head.secret.contains(Base64Url.encode(previousSyncSecret)),
      )
    }.provideSomeLayer(TestClient.layer),
    test("registry scopes resources to the edge in an edge-issued internal token") {
      for
        client <- ZIO.service[Client]
        service = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        token <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
          headers = Map("edge_id" -> edgeId.toString),
        )
        _ <- edgeService.find.succeedsWith(Some(edgeRecord))
        _ <- service.getResourcesForSync.succeedsWith(resourceRecords)
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(
              ZEnvironment[ResourceService](service) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        response <- client.batched(
          Request.get(URL.empty / "configuration" / "resources" / "registry")
            .addHeader(Header.Authorization.Bearer(token))
        )
        payload <- decodeJsonBody[GetResourcesRegistryResponse](response)
      yield assertTrue(
        response.status == Status.Ok,
        service.getResourcesForSync.calls == List(Some(edgeId)),
        payload.resources.map(_.resourceId) == resourceRecords.map(_.resourceId),
      )
    }.provideSomeLayer(TestClient.layer),
  )