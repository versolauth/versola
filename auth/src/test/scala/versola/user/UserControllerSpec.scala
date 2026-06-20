package versola.user

import versola.auth.TestEnvConfig
import versola.auth.model.TenantId
import versola.oauth.client.model.ClientId
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.http.{NoopTracing, Observability}
import versola.util.{Base64Url, MAC}
import versola.util.{JWT, Patch}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object UserControllerSpec extends ZIOSpecDefault:

  private val config = TestEnvConfig.coreConfig
  private val secretKey = config.central.secretKey
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")

  private val userRepo = new UserRepository:
    override def findOrCreate(userId: UserId, credential: Either[versola.util.Email, versola.util.Phone]) = ZIO.dieMessage("Unused")
    override def create(id: UserId) = ZIO.dieMessage("Unused")
    override def find(id: UserId) = ZIO.dieMessage("Unused")
    override def findByCredential(credential: Either[versola.util.Email, versola.util.Phone]) = ZIO.dieMessage("Unused")
    override def upsert(id: UserId, version: UUID, email: Option[versola.util.Email], phone: Option[versola.util.Phone], login: Option[Login]) =
      ZIO.unit
    override def patchClaims(id: UserId, patch: Json.Obj) = ZIO.unit

  private val rolesRepo = new UserRolesRepository:
    override def findRolesByUser(userId: UserId) = ZIO.dieMessage("Unused")
    override def findRolesByUserAndTenant(userId: UserId, tenant_id: TenantId) = ZIO.succeed(Nil)
    override def updateRoles(userId: UserId, tenantId: TenantId, add: Set[RoleId], remove: Set[RoleId]) = ZIO.unit

  private val sessionRepo = new SessionRepository:
    override def create(id: MAC.Of[SessionId], session: SessionRecord, ttl: Duration): Task[Unit] = ZIO.dieMessage("Unused")
    override def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] = ZIO.dieMessage("Unused")
    override def findByUser(userId: UserId): Task[List[(MAC.Of[SessionId], SessionRecord)]] = ZIO.succeed(Nil)
    override def invalidate(id: MAC.Of[SessionId]): Task[Unit] = ZIO.unit

  private def validToken(key: javax.crypto.SecretKey): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def routes(
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
      sessions: SessionRepository = sessionRepo,
  ) =
    Observability.handleErrors(
      UserController.routes
        .provideEnvironment(
          ZEnvironment(userRepo) ++ ZEnvironment(rolesRepo) ++ ZEnvironment(config) ++ ZEnvironment(sessions) ++ tracing,
        ),
    )

  def spec = suite("UserController")(
    test("PUT /users without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.put(URL.empty / "users", Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PUT /users with token signed by wrong key returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(wrongKey)
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request.put(URL.empty / "users", Body.fromString("{}"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PUT /users with valid Bearer token returns 204") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- TestClient.addRoutes(routes(tracing))
        body =
          """{"id":"00000000-0000-0000-0000-000000000001","version":"00000000-0000-0000-0000-000000000001","email":null,"phone":null,"login":null}"""
        resp <- client.batched(
          Request.put(URL.empty / "users", Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("PATCH /users/roles without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.PATCH, url = URL.empty / "users" / "roles", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PATCH /users/roles with valid Bearer token returns 204") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- TestClient.addRoutes(routes(tracing))
        body = """{"userId":"00000000-0000-0000-0000-000000000001","tenantId":"t1","add":["r1"],"remove":[]}"""
        resp <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "roles", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /users/sessions without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.get(URL.empty / "users" / "sessions"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/sessions with valid token returns session list") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "sessions").addQueryParam("id", "f077fb08-9935-4a6d-8643-bf97c073bf0f"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
      yield assertTrue(
        resp.status == Status.Ok,
        body == "[]",
      )
    },
    test("DELETE /users/sessions/{id} with valid token and matching userId returns 204") {
      val testSessionId = MAC(Array.fill(32)(1.toByte))
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      val testSession = SessionRecord(
        userId = testUserId,
        clientId = ClientId("test-client"),
        userAgent = None,
        createdAt = Instant.EPOCH,
      )
      val localRepo = new SessionRepository:
        override def create(id: MAC.Of[SessionId], session: SessionRecord, ttl: Duration): Task[Unit] = ZIO.dieMessage("Unused")
        override def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] = ZIO.some(testSession)
        override def findByUser(userId: UserId): Task[List[(MAC.Of[SessionId], SessionRecord)]] = ZIO.dieMessage("Unused")
        override def invalidate(id: MAC.Of[SessionId]): Task[Unit] = ZIO.unit

      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        encodedId = Base64Url.encode(testSessionId)
        _ <- TestClient.addRoutes(routes(tracing, localRepo))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "sessions" / encodedId).addQueryParam("userId", testUserId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
  ).provideSomeShared[Scope](TestClient.layer) @@ TestAspect.silentLogging
