package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.jwks.JwksService
import versola.central.{AdminClaims, TestCentralConfig}
import versola.util.{Email, JWT}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.UUID

object UserControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val email  = Email("user@example.com")

  // RSA key pair for signing admin access tokens in tests
  private val rsaKeyPair =
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  private val rsaPrivateKey = rsaKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val rsaPublicKey  = rsaKeyPair.getPublic.asInstanceOf[RSAPublicKey]
  private val rsaKeyId      = "test-key-1"

  private val testPublicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      new com.nimbusds.jose.jwk.RSAKey.Builder(rsaPublicKey)
        .keyID(rsaKeyId)
        .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .build()
    )
  )

  private def adminToken(
      clientId: String = "central-admin",
      adminRoles: Map[String, List[String]] = Map.empty,
  ): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = "auth",
        subject = "admin-user",
        audience = List("central"),
        custom = Json.Obj(
          "client_id"   -> Json.Str(clientId),
          "admin_roles" -> adminRoles.toJsonAST.toOption.get,
        ),
      ),
      ttl = 10.minutes,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, rsaKeyId, rsaPrivateKey),
      typ = JWT.Type.AccessToken,
    )

  private val passkeyInfo = PasskeyInfo(
    id = "aQ",
    name = Some("Phone"),
    deviceType = "MultiDevice",
    transports = List("Internal"),
    backedUp = true,
    backupEligible = true,
    lastUsedAt = None,
    createdAt = "2024-01-01T00:00:00Z",
  )

  private val createRequest = CreateUserRequest(
    email = Some(email),
    phone = None,
    login = None,
  )

  private val createRequestBody =
    """{"email":"user@example.com"}"""

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
      setup: Stub[UserService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[UserService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[UserService]
        tracing <- tracingLayer.build
        jwksService = new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(testPublicKeys)
          override def getRaw: UIO[Json.Obj] = ZIO.dieMessage("getRaw unused in this test")
          override def sync(): Task[Unit] = ZIO.unit
          override def createKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("createKey unused in this test")
          override def updateKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("updateKey unused in this test")
          override def deleteKey(kid: String): Task[Unit] = ZIO.dieMessage("deleteKey unused in this test")
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment[JwksService](jwksService) ++
                tracing
            )
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("UserController")(
    controllerTestCase(
      description = "create user returns generated id",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users",
        body = Body.fromString(createRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.create.succeedsWith(userId),
      verify = (response, service) =>
        for body <- response.body.asJson[CreateUserResponse]
        yield assertTrue(
          service.create.calls == List(createRequest),
          body == CreateUserResponse(userId),
        ),
    ),
    controllerTestCase(
      description = "create user returns 409 Conflict when service signals UserConflict",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users",
        body = Body.fromString(createRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = service => service.create.failsWith(UserConflict),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.isEmpty),
    ),
    controllerTestCase(
      description = "patch claims returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "claims",
        body = Body.fromString(s"""{"id":"$userId","claims":{"test":true}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.patchClaims.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(service.patchClaims.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "patch roles returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "roles",
        body = Body.fromString(s"""{"userId":"$userId","tenantId":"t1","add":["r1"],"remove":[]}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.updateRoles.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(service.updateRoles.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "list passkeys returns the user's passkeys",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users" / "passkeys").addQueryParam("id", userId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.listPasskeys.succeedsWith(List(passkeyInfo)),
      verify = (response, service) =>
        for body <- response.body.asJson[ListPasskeysResponse]
        yield assertTrue(
          service.listPasskeys.calls == List(userId),
          body == ListPasskeysResponse(List(passkeyInfo)),
        ),
    ),
    controllerTestCase(
      description = "rename passkey returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "passkeys",
        body = Body.fromString(s"""{"userId":"$userId","credentialId":"cred-1","name":"New Name"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.renamePasskey.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(
          service.renamePasskey.calls == List(RenamePasskeyRequest(userId, "cred-1", Some("New Name"))),
        )),
    ),
    controllerTestCase(
      description = "delete passkey returns 202 Accepted",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "users" / "passkeys")
          .addQueryParam("id", userId.toString)
          .addQueryParam("credentialId", "cred-1"),
      ),
      expectedStatus = Status.Accepted,
      setup = service => service.deletePasskey.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(
          service.deletePasskey.calls == List((userId, "cred-1")),
        )),
    ),
    test("getMyPermissions passes AdminClaims with correct clientId and adminRoles to service") {
      val expectedResponse = MyPermissionsResponse(superAdmin = false, roles = Some(Set("operator")), permissions = Some(Set("users:read")))
      for
        client  <- ZIO.service[Client]
        service =  stub[UserService]
        tracing <- tracingLayer.build
        jwks    =  new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(testPublicKeys)
          override def getRaw: UIO[Json.Obj] = ZIO.dieMessage("getRaw unused in this test")
          override def sync(): Task[Unit] = ZIO.unit
          override def createKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("createKey unused in this test")
          override def updateKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("updateKey unused in this test")
          override def deleteKey(kid: String): Task[Unit] = ZIO.dieMessage("deleteKey unused in this test")
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment[JwksService](jwks) ++
                tracing
            )
          )
        )
        _ <- service.getMyPermissions.succeedsWith(expectedResponse)
        token   <- adminToken(clientId = "central-admin", adminRoles = Map("default" -> List("admin")))
        response <- client.batched(
          Request(method = Method.GET, url = URL.empty / "users" / "permissions" / "me")
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.Accept(MediaType.application.json))
        )
      yield assertTrue(
        response.status == Status.Ok,
        service.getMyPermissions.calls.exists(c => c.clientId.contains("central-admin") && c.adminRoles.exists(_.contains("default"))),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("getMyPermissions returns 401 when no Authorization header") {
      for
        client  <- ZIO.service[Client]
        service =  stub[UserService]
        tracing <- tracingLayer.build
        jwks    =  new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(testPublicKeys)
          override def getRaw: UIO[Json.Obj] = ZIO.dieMessage("getRaw unused in this test")
          override def sync(): Task[Unit] = ZIO.unit
          override def createKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("createKey unused in this test")
          override def updateKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("updateKey unused in this test")
          override def deleteKey(kid: String): Task[Unit] = ZIO.dieMessage("deleteKey unused in this test")
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment[JwksService](jwks) ++
                tracing
            )
          )
        )
        response <- client.batched(Request(method = Method.GET, url = URL.empty / "users" / "permissions" / "me"))
      yield assertTrue(response.status == Status.Unauthorized)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("getMyPermissions delegates to service and returns JSON result") {
      val expectedResponse = MyPermissionsResponse(superAdmin = false, roles = Some(Set("operator")), permissions = Some(Set("users:read")))
      for
        client  <- ZIO.service[Client]
        service =  stub[UserService]
        tracing <- tracingLayer.build
        jwks    =  new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(testPublicKeys)
          override def getRaw: UIO[Json.Obj] = ZIO.dieMessage("getRaw unused in this test")
          override def sync(): Task[Unit] = ZIO.unit
          override def createKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("createKey unused in this test")
          override def updateKey(kid: String, jwk: zio.json.ast.Json.Obj): Task[Unit] = ZIO.dieMessage("updateKey unused in this test")
          override def deleteKey(kid: String): Task[Unit] = ZIO.dieMessage("deleteKey unused in this test")
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment[JwksService](jwks) ++
                tracing
            )
          )
        )
        _ <- service.getMyPermissions.succeedsWith(expectedResponse)
        token   <- adminToken(clientId = "central-admin", adminRoles = Map("default" -> List("operator")))
        response <- client.batched(
          Request(method = Method.GET, url = URL.empty / "users" / "permissions" / "me")
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.Accept(MediaType.application.json))
        )
        body <- response.body.asJson[MyPermissionsResponse]
      yield assertTrue(
        response.status == Status.Ok,
        service.getMyPermissions.calls.nonEmpty,
        body == expectedResponse,
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
  )
