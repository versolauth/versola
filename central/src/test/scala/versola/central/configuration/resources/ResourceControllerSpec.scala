package versola.central.configuration.resources

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.*
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object ResourceControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val resourceId = ResourceId(1)
  private val usersListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000301")
  private val usersCreateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000302")
  private val usersMeEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000303")
  private val allowRule = PermissionRule("role", "equals", zio.json.ast.Json.Str("admin"))
  private val delegatedAllowRule = PermissionRule("department", "equals", zio.json.ast.Json.Str("support"))
  private val denyRule = PermissionRule("country", "equals", zio.json.ast.Json.Str("blocked"))
  private val numericAllowRule = PermissionRule("request.attempt", "gte", zio.json.ast.Json.Num(1))
  private val allowRules = AclRuleTree.any(Vector(
    AclRuleTree.all(Vector(AclRuleTree.rule(allowRule))),
    AclRuleTree.all(Vector(AclRuleTree.rule(delegatedAllowRule))),
  ))
  private val denyRules = AclRuleTree.any(Vector(AclRuleTree.all(Vector(AclRuleTree.rule(denyRule)))))
  private val numericAllowRules = AclRuleTree.any(Vector(AclRuleTree.all(Vector(AclRuleTree.rule(numericAllowRule)))))

  private val createRequestBody = CreateResourceRequest(
    tenantId = tenantId,
    resource = ResourceUri("https://api.example.com"),
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
      CreateResourceEndpointRequest(usersCreateEndpointId, "/users", "POST", false, AclRuleTree.emptyAny, denyRules, Map.empty),
    ),
  )

  private val createRequestBodyWithNumericRule = CreateResourceRequest(
    tenantId = tenantId,
    resource = ResourceUri("https://api.example.com"),
    endpoints = Vector(
      CreateResourceEndpointRequest(usersListEndpointId, "/users", "GET", true, numericAllowRules, AclRuleTree.emptyAny, Map.empty)
    ),
  )

  private val updateRequestBody = UpdateResourceRequest(
    id = resourceId,
    resource = Some(ResourceUri("https://api.internal.example.com")),
    deleteEndpoints = Set(usersCreateEndpointId),
    createEndpoints = Vector(
      CreateResourceEndpointRequest(usersMeEndpointId, "/users/me", "GET", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true"))
    ),
  )

  private val resourceRecords = Vector(
    ResourceRecord(
      tenantId = tenantId,
      id = resourceId,
      resource = createRequestBody.resource,
      endpoints = Vector(
        ResourceEndpointRecord(usersListEndpointId, "/users", "GET", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
        ResourceEndpointRecord(usersCreateEndpointId, "/users", "POST", false, AclRuleTree.emptyAny, denyRules, Map.empty),
      ),
    )
  )

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
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ResourceController.routes.provideEnvironment(ZEnvironment(service) ++ tracing)
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
        (URL.empty / "v1" / "configuration" / "resources")
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
                resource = createRequestBody.resource,
                endpoints = Vector(
                  ResourceEndpointResponse(usersListEndpointId, "GET", "/users", true, allowRules, AclRuleTree.emptyAny, Map("x-user" -> "true")),
                  ResourceEndpointResponse(usersCreateEndpointId, "POST", "/users", false, AclRuleTree.emptyAny, denyRules, Map.empty),
                ),
              )
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get((URL.empty / "v1" / "configuration" / "resources").addQueryParam("tenantId", tenantId.toString)),
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
        url = URL.empty / "v1" / "configuration" / "resources",
        body = Body.fromString(createRequestBody.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createResource.succeedsWith(resourceId),
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
        url = URL.empty / "v1" / "configuration" / "resources",
        body = Body.fromString(createRequestBodyWithNumericRule.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createResource.succeedsWith(resourceId),
      verify = (response, service) =>
        for payload <- decodeJsonBody[CreateResourceResponse](response)
        yield assertTrue(
          payload == CreateResourceResponse(resourceId),
          service.createResource.calls == List(createRequestBodyWithNumericRule),
        ),
    ),
    controllerTestCase(
      description = "update resource with endpoint replacements",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "resources",
        body = Body.fromString(updateRequestBody.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateResource.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.updateResource.calls == List(updateRequestBody))),
    ),
    controllerTestCase(
      description = "delete resource",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "resources").addQueryParam("id", resourceId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteResource.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.deleteResource.calls == List(resourceId))),
    ),
  )