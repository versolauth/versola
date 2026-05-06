package versola.user

import versola.auth.TestEnvConfig
import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.{JWT, Patch}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object UserControllerSpec extends ZIOSpecDefault:

  private val config    = TestEnvConfig.coreConfig
  private val secretKey = config.central.secretKey
  private val wrongKey  = SecretKeySpec(Array.fill(32)(99.toByte), "AES")

  private val userRepo = new UserRepository:
    override def findOrCreate(userId: UserId, credential: Either[versola.util.Email, versola.util.Phone]) = ZIO.dieMessage("Unused")
    override def create(id: UserId) = ZIO.dieMessage("Unused")
    override def find(id: UserId)   = ZIO.dieMessage("Unused")
    override def findByCredential(credential: Either[versola.util.Email, versola.util.Phone]) = ZIO.dieMessage("Unused")
    override def upsert(id: UserId, email: Option[versola.util.Email], phone: Option[versola.util.Phone], login: Option[Login], claims: Json.Obj) = ZIO.unit
    override def patch(id: UserId, email: Option[Patch[versola.util.Email]], phone: Option[Patch[versola.util.Phone]], login: Option[Patch[Login]], claims: Option[Json.Obj]) = ZIO.unit

  private val rolesRepo = new UserRolesRepository:
    override def findRolesByUser(userId: UserId)                                            = ZIO.dieMessage("Unused")
    override def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId)               = ZIO.succeed(Nil)
    override def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId)             = ZIO.unit
    override def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId)             = ZIO.unit

  private def validToken(key: javax.crypto.SecretKey): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def routes(tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing]) =
    Observability.handleErrors(
      UserController.routes
        .provideEnvironment(
          ZEnvironment(userRepo) ++ ZEnvironment(rolesRepo) ++ ZEnvironment(config) ++ tracing,
        )
    )

  def spec = suite("UserController")(
    test("POST /users without Authorization returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request.post(URL.empty / "users", Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("POST /users with token signed by wrong key returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(wrongKey)
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(
          Request.post(URL.empty / "users", Body.fromString("{}"))
            .addHeader(Header.Authorization.Bearer(token))
        )
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("POST /users with valid Bearer token returns 204") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- TestClient.addRoutes(routes(tracing))
        body     = """{"id":"00000000-0000-0000-0000-000000000001","email":null,"phone":null,"login":null,"claims":{}}"""
        resp    <- client.batched(
          Request.post(URL.empty / "users", Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("PATCH /users with valid Bearer token returns 204") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- TestClient.addRoutes(routes(tracing))
        body     = """{"id":"00000000-0000-0000-0000-000000000001"}"""
        resp    <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("POST /users/roles without Authorization returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request.post(URL.empty / "users" / "roles", Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
  ).provideSomeShared[Scope](TestClient.layer) @@ TestAspect.silentLogging
