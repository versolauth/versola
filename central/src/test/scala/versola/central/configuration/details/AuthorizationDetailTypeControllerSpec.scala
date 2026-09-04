package versola.central.configuration.details

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{
  AuthorizationDetailTypeResponse,
  AuthorizationDetailTypeSyncResponse,
  CreateAuthorizationDetailTypeRequest,
  GetAllAuthorizationDetailTypesResponse,
  GetAuthorizationDetailTypesSyncResponse,
  UpdateAuthorizationDetailTypeRequest,
}
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object AuthorizationDetailTypeControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val tenantId = TenantId("tenant-a")
  private val detailType = AuthorizationDetailType("payment_initiation")
  private val schema = Json.Obj("type" -> Json.Str("object"))
  private val typeDescription = Map("en" -> "Payment initiation")

  private val record = AuthorizationDetailTypeRecord(tenantId, detailType, typeDescription, schema)

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[AuthorizationDetailTypeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[AuthorizationDetailTypeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[AuthorizationDetailTypeService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AuthorizationDetailTypeController.routes.provideEnvironment(
              ZEnvironment[AuthorizationDetailTypeService](service) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[CentralConfig](config) ++
                tracing
            )
          )
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _    => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("AuthorizationDetailTypeController")(
    controllerTestCase(
      description = "GET authorization-detail-types returns the tenant's types",
      request = Request.get(
        (URL.empty / "configuration" / "authorization-detail-types")
          .addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getTenantTypes.succeedsWith(Vector(record)),
      // zio-http's schema-derived `asJson` mishandles nested `Json.Obj` fields, so this
      // decodes with the response DTO's own zio-json `JsonCodec` instead (the same one the
      // controller encodes with via `EncoderOps`).
      verify = (response, service) =>
        for
          bodyString <- response.body.asString
          body <- ZIO.fromEither(bodyString.fromJson[GetAllAuthorizationDetailTypesResponse]).mapError(new RuntimeException(_))
        yield assertTrue(
          service.getTenantTypes.calls == List((tenantId, 0, None)),
          body == GetAllAuthorizationDetailTypesResponse(
            Vector(AuthorizationDetailTypeResponse(detailType, typeDescription, schema))
          ),
        ),
    ),
    controllerTestCase(
      description = "GET authorization-detail-types forwards offset and limit query params",
      request = Request.get(
        (URL.empty / "configuration" / "authorization-detail-types")
          .addQueryParam("tenantId", tenantId.toString)
          .addQueryParam("offset", "10")
          .addQueryParam("limit", "5")
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getTenantTypes.succeedsWith(Vector.empty),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getTenantTypes.calls == List((tenantId, 10, Some(5))))),
    ),
    controllerTestCase(
      description = "GET authorization-detail-types/sync returns all types for an authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "authorization-detail-types" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getAllTypes.succeedsWith(Vector(record)),
      verify = (response, service) =>
        for
          bodyString <- response.body.asString
          body <- ZIO.fromEither(bodyString.fromJson[GetAuthorizationDetailTypesSyncResponse]).mapError(new RuntimeException(_))
        yield assertTrue(
          service.getAllTypes.calls.length == 1,
          body == GetAuthorizationDetailTypesSyncResponse(
            Vector(AuthorizationDetailTypeSyncResponse(tenantId, detailType, schema))
          ),
        ),
    ),
    controllerTestCase(
      description = "GET authorization-detail-types/sync rejects a request without a service token",
      request = Request.get(URL.empty / "configuration" / "authorization-detail-types" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) => ZIO.succeed(assertTrue(service.getAllTypes.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "POST authorization-detail-types creates a type and returns 201 Created",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "authorization-detail-types",
        body = Body.fromString(CreateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createType.succeedsWith(Right(())),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.createType.calls == List(CreateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema))
        )),
    ),
    controllerTestCase(
      description = "POST authorization-detail-types returns 400 Bad Request when the service rejects the schema",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "authorization-detail-types",
        body = Body.fromString(CreateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.createType.succeedsWith(Left(AuthorizationDetailTypeValidationError.InvalidSchema(List("bad schema")))),
    ),
    controllerTestCase(
      description = "PUT authorization-detail-types updates a type and returns 204 No Content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "authorization-detail-types",
        body = Body.fromString(UpdateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateType.succeedsWith(Right(())),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.updateType.calls == List(UpdateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema))
        )),
    ),
    controllerTestCase(
      description = "PUT authorization-detail-types returns 400 Bad Request when the service rejects the schema",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "authorization-detail-types",
        body = Body.fromString(UpdateAuthorizationDetailTypeRequest(tenantId, detailType, typeDescription, schema).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.updateType.succeedsWith(Left(AuthorizationDetailTypeValidationError.InvalidSchema(List("bad schema")))),
    ),
    controllerTestCase(
      description = "DELETE authorization-detail-types deletes a type and returns 204 No Content",
      request = Request.delete(
        (URL.empty / "configuration" / "authorization-detail-types")
          .addQueryParam("tenantId", tenantId.toString)
          .addQueryParam("type", detailType.toString)
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteType.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteType.calls == List((tenantId, detailType)))),
    ),
  )
