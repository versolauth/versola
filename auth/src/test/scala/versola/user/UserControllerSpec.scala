package versola.user

import versola.auth.TestEnvConfig
import versola.auth.model.TenantId
import versola.oauth.conversation.limit.{ChallengeThrottleRecord, ChallengeThrottleRepository, ChallengeType}
import versola.oauth.client.model.TenantId as ThrottleTenantId
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
    override def upsert(id: UserId, version: UUID, email: Option[versola.util.Email], phone: Option[versola.util.Phone], login: Option[Login]) = ZIO.unit
    override def patchClaims(id: UserId, patch: Json.Obj) = ZIO.unit

  private val rolesRepo = new UserRolesRepository:
    override def findRolesByUser(userId: UserId)                                            = ZIO.dieMessage("Unused")
    override def findRolesByUserAndTenant(userId: UserId, tenant_id: TenantId)              = ZIO.succeed(Nil)
    override def updateRoles(userId: UserId, tenantId: TenantId, add: Set[RoleId], remove: Set[RoleId]) = ZIO.unit

  private def throttleRepo(deleted: Ref[List[String]]) = new ChallengeThrottleRepository:
    override def find(tenantId: ThrottleTenantId, subject: String, challengeType: ChallengeType) = ZIO.dieMessage("Unused")
    override def findAll(tenantId: ThrottleTenantId, subject: String, challengeTypes: List[ChallengeType]) = ZIO.dieMessage("Unused")
    override def upsert(record: ChallengeThrottleRecord)                                          = ZIO.unit
    override def delete(tenantId: ThrottleTenantId, subject: String, challengeType: ChallengeType) = ZIO.unit
    override def deleteAllForSubject(tenantId: ThrottleTenantId, subject: String)                 = deleted.update(subject :: _)

  private val noopThrottle = new ChallengeThrottleRepository:
    override def find(tenantId: ThrottleTenantId, subject: String, challengeType: ChallengeType) = ZIO.dieMessage("Unused")
    override def findAll(tenantId: ThrottleTenantId, subject: String, challengeTypes: List[ChallengeType]) = ZIO.dieMessage("Unused")
    override def upsert(record: ChallengeThrottleRecord)                                          = ZIO.unit
    override def delete(tenantId: ThrottleTenantId, subject: String, challengeType: ChallengeType) = ZIO.unit
    override def deleteAllForSubject(tenantId: ThrottleTenantId, subject: String)                 = ZIO.unit

  private def validToken(key: javax.crypto.SecretKey): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def routes(
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
      throttle: ChallengeThrottleRepository = noopThrottle,
  ) =
    Observability.handleErrors(
      UserController.routes
        .provideEnvironment(
          ZEnvironment(userRepo) ++ ZEnvironment(rolesRepo) ++ ZEnvironment(config) ++ ZEnvironment(throttle) ++ tracing,
        )
    )

  def spec = suite("UserController")(
    test("PUT /users without Authorization returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request.put(URL.empty / "users", Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PUT /users with token signed by wrong key returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(wrongKey)
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(
          Request.put(URL.empty / "users", Body.fromString("{}"))
            .addHeader(Header.Authorization.Bearer(token))
        )
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PUT /users with valid Bearer token returns 204") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- TestClient.addRoutes(routes(tracing))
        body     = """{"id":"00000000-0000-0000-0000-000000000001","version":"00000000-0000-0000-0000-000000000001","email":null,"phone":null,"login":null}"""
        resp    <- client.batched(
          Request.put(URL.empty / "users", Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("PATCH /users/roles without Authorization returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request(method = Method.PATCH, url = URL.empty / "users" / "roles", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PATCH /users/roles with valid Bearer token returns 204") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- TestClient.addRoutes(routes(tracing))
        body     = """{"userId":"00000000-0000-0000-0000-000000000001","tenantId":"t1","add":["r1"],"remove":[]}"""
        resp    <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "roles", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("POST /users/limits/reset without Authorization returns 401") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request(method = Method.POST, url = URL.empty / "users" / "limits" / "reset", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("POST /users/limits/reset clears throttle for userId, email and phone") {
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        deleted <- Ref.make(List.empty[String])
        _       <- TestClient.addRoutes(routes(tracing, throttleRepo(deleted)))
        body     = """{"userId":"00000000-0000-0000-0000-000000000001","tenantId":"t1","email":"john@doe.com","phone":"+1234567890"}"""
        resp    <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "limits" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
        subjects <- deleted.get
      yield assertTrue(
        resp.status == Status.NoContent,
        subjects.toSet == Set("00000000-0000-0000-0000-000000000001", "john@doe.com", "+1234567890"),
      )
    },
  ).provideSomeShared[Scope](TestClient.layer) @@ TestAspect.silentLogging

