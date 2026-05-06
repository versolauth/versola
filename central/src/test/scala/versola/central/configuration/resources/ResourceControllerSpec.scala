package versola.central.configuration.resources

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.*
import versola.util.Secret
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object ResourceControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val resourceId = ResourceId(1)
  private val usersListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000301")
  private val usersCreateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000302")
  private val usersMeEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000303")
  private val allow = Some("token.role == 'admin' || token.department == 'support'")
  private val denyAware = Some("token.country != 'blocked'")
  private val numericAllow = Some("request.attempt >= 1")
  private val inject = Vector(InjectRule(InjectTarget.header, "x-user", "token.sub"))

  private val createRequestBody = CreateResourceRequest(
    tenantId = tenantId,
    alias = "users-api",
    resource = ResourceUri("https://api.example.com"),
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, allow, inject),
      CreateResourceEndpointRequest(usersCreateEndpointId, "/users", "POST", false, denyAware, Vector.empty),
    ),
  )

  private val createRequestBodyWithNumericRule = CreateResourceRequest(
    tenantId = tenantId,
    alias = "users-api",
    resource = ResourceUri("https://api.example.com"),
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, numericAllow, Vector.empty)
    ),
  )

  private val updateRequestBody = UpdateResourceRequest(
    id = resourceId,
    alias = Some("users-internal"),
    resource = Some(ResourceUri("https://api.internal.example.com")),
    deleteEndpoints = Set(usersCreateEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(usersMeEndpointId, "/users/me", "GET", true, allow, inject)
    ),
  )

  private val resourceRecords = Vector(
    ResourceRecord(
      tenantId = tenantId,
      id = resourceId,
      alias = createRequestBody.alias,
      resource = createRequestBody.resource,
      endpoints = Vector(
        ResourceEndpointRecord(usersListEndpointId, "/users", "GET", true, allow, inject),
        ResourceEndpointRecord(usersCreateEndpointId, "/users", "POST", false, denyAware, Vector.empty),
      ),
    )
  )

  private val config = TestCentralConfig.config

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](Tracing.live(logAnnotated = false), OpenTelemetry.contextZIO, ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")))

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
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(
              ZEnvironment[ResourceService](service) ++ ZEnvironment(config) ++ tracing ++ ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
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
                id = resourceId,
                alias = createRequestBody.alias,
                resource = createRequestBody.resource,
                endpoints = Vector(
                  ResourceEndpointResponse(usersListEndpointId, "GET", "/users", true, allow, inject),
                  ResourceEndpointResponse(usersCreateEndpointId, "POST", "/users", false, denyAware, Vector.empty),
                ),
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
      setup = service => service.createResource.succeedsWith(Right(resourceId)),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId),
          service.createResource.calls == List(createRequestBody),
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
      setup = service => service.createResource.succeedsWith(Right(resourceId)),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId),
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
        url = (URL.empty / "configuration" / "resources").addQueryParam("id", resourceId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteResource.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.deleteResource.calls == List(resourceId))),
    ),
  )