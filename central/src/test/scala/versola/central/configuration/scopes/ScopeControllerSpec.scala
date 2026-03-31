package versola.central.configuration.scopes

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.CentralConfig
import versola.central.configuration.*
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import versola.util.{JWT, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object ScopeControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val profileScope = ScopeToken("profile")
  private val emailScope = ScopeToken("email")
  private val emailClaim = Claim("email")
  private val nameClaim = Claim("name")
  private val localeClaim = Claim("locale")
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
          JWT.Signature.Symmetric(secretKey)
        )
      )
      .getOrThrowFiberFailure()
  }

  private val scopes = Vector(
    ScopeRecord(
      tenantId = tenantId,
      id = profileScope,
      description = Map("en" -> "User profile"),
      claims = Vector(
        ClaimRecord(emailClaim, Map("en" -> "Email")),
        ClaimRecord(nameClaim, Map("en" -> "Full name")),
      ),
    ),
    ScopeRecord(
      tenantId = tenantId,
      id = emailScope,
      description = Map("en" -> "Email access"),
      claims = Vector(
        ClaimRecord(emailClaim, Map("en" -> "Email")),
      ),
    ),
  )

  private val createRequest = CreateScopeRequest(
    tenantId = tenantId,
    id = profileScope,
    description = Map("en" -> "User profile"),
    claims = List(
      CreateClaim(emailClaim, Map("en" -> "Email")),
      CreateClaim(nameClaim, Map("en" -> "Full name")),
    ),
  )

  private val updateRequest = UpdateScopeRequest(
    tenantId = tenantId,
    id = profileScope,
    patch = PatchScope(
      add = List(CreateClaim(localeClaim, Map("en" -> "Locale"))),
      update = List(
        PatchClaim(
          id = emailClaim,
          description = PatchDescription(
            add = Map("ru" -> "Почта"),
            delete = Set.empty,
          ),
        )
      ),
      delete = Set(nameClaim),
      description = PatchDescription(
        add = Map("ru" -> "Профиль пользователя"),
        delete = Set.empty,
      ),
    ),
  )

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
      setup: Stub[OAuthScopeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[OAuthScopeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[OAuthScopeService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ScopeController.routes.provideEnvironment(
              ZEnvironment(service) ++ ZEnvironment(config) ++ tracing
            )
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ScopeController")(
    controllerTestCase(
      description = "return tenant scopes with pagination params",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "scopes")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "1", "limit" -> "3"))
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantScopes.succeedsWith(scopes),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllScopesResponse]
        yield assertTrue(
          service.getTenantScopes.calls == List((tenantId, 1, Some(3))),
          payload == GetAllScopesResponse(
            Vector(
              ScopeWithClaimsResponse(
                scope = profileScope,
                description = Map("en" -> "User profile"),
                claims = Vector(
                  ClaimResponse(emailClaim, Map("en" -> "Email")),
                  ClaimResponse(nameClaim, Map("en" -> "Full name")),
                ),
              ),
              ScopeWithClaimsResponse(
                scope = emailScope,
                description = Map("en" -> "Email access"),
                claims = Vector(
                  ClaimResponse(emailClaim, Map("en" -> "Email")),
                ),
              ),
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "scopes")
          .addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantScopes.succeedsWith(Vector.empty),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllScopesResponse]
        yield assertTrue(
          service.getTenantScopes.calls == List((tenantId, 0, None)),
          payload == GetAllScopesResponse(Vector.empty),
        ),
    ),
    controllerTestCase(
      description = "return synced tenant scopes for authorized service token",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "scopes" / "sync")
          .addQueryParam("tenantId", tenantId.toString)
      ).addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllScopes.succeedsWith(scopes),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllScopesResponse]
        yield assertTrue(
          service.getAllScopes.calls.length == 1,
          payload == GetAllScopesResponse(
            Vector(
              ScopeWithClaimsResponse(
                scope = profileScope,
                description = Map("en" -> "User profile"),
                claims = Vector(
                  ClaimResponse(emailClaim, Map("en" -> "Email")),
                  ClaimResponse(nameClaim, Map("en" -> "Full name")),
                ),
              ),
              ScopeWithClaimsResponse(
                scope = emailScope,
                description = Map("en" -> "Email access"),
                claims = Vector(
                  ClaimResponse(emailClaim, Map("en" -> "Email")),
                ),
              ),
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "create scope",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "scopes",
        body = Body.fromString(createRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.createScope.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createScope.calls == List(createRequest))),
    ),
    controllerTestCase(
      description = "update scope",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "scopes",
        body = Body.fromString(updateRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updateScope.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.updateScope.calls == List(updateRequest))),
    ),
    controllerTestCase(
      description = "delete scope",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "scopes")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "scopeId" -> profileScope.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deleteScope.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteScope.calls == List((tenantId, profileScope)))),
    ),
  )