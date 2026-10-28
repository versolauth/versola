package versola.user

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyName, PasskeyRecord, Password}
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.PasswordChannelUnavailable
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.logout.LogoutService
import versola.oauth.session.SessionService
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionUnderUserAgent, UserAgentId, UserAgentRecord, UserDevice}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.http.{NoopTracing, Observability}
import versola.util.{Base64, JWT, Patch}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object UserControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val secretKey = config.central.secretKey
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")

  private val userRepo = stub[UserRepository]
  private val logoutService = stub[LogoutService]
  private val sessionService = stub[SessionService]
  private val noopThrottle = stub[ChallengeThrottleRepository]
  private val noopPasskeyRepo = stub[PasskeyRepository]
  private val noopPasswordSvc = stub[PasswordService]

  private def validToken(key: javax.crypto.SecretKey): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private val passkeyUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val credentialId = CredentialId(Array.fill(32)(7.toByte))

  private val passkeyRecord = PasskeyRecord(
    id = credentialId,
    userId = passkeyUserId,
    publicKey = Array.fill(16)(1.toByte),
    signatureCounter = 0L,
    deviceType = CredentialDeviceType.MultiDevice,
    backedUp = true,
    backupEligible = true,
    transports = List(AuthenticatorTransport.Internal),
    attestationObject = None,
    clientDataJson = None,
    aaguid = None,
    name = Some(PasskeyName("My Phone")),
    lastUsedAt = None,
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
  )

  private def routes(
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
      users: UserRepository = userRepo,
      logout: LogoutService = logoutService,
      throttle: ChallengeThrottleRepository = noopThrottle,
      passkey: PasskeyRepository = noopPasskeyRepo,
      sessionsService: SessionService = sessionService,
  ) =
    Observability.handleErrors(
      UserController.routes
        .provideEnvironment(
          ZEnvironment(users) ++ ZEnvironment(config) ++
            ZEnvironment(logout) ++ ZEnvironment(sessionsService) ++ ZEnvironment(throttle) ++ ZEnvironment(passkey) ++
            ZEnvironment(noopPasswordSvc) ++ tracing,
        ),
    )

  private val agentId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))

  private val agentRecord = UserAgentRecord(
    id = agentId,
    userAgent = Some("Mozilla/5.0 (Macintosh) Chrome/130.0"),
    platform = Some("desktop"),
    os = Some("macOS 14.6"),
    browser = Some("Chrome"),
    version = Some("130"),
    createdAt = Instant.parse("2026-01-01T10:00:00Z"),
    expiresAt = Instant.parse("2026-07-01T10:00:00Z"),
  )

  private val liveSession = SessionUnderUserAgent(
    publicId = PublicSessionId("c2lk"),
    clients = List(ClientEntry(ClientId("app"), Instant.parse("2026-01-02T10:00:00Z"))),
    createdAt = Instant.parse("2026-01-02T10:00:00Z"),
    platform = agentRecord.platform,
    os = agentRecord.os,
    browser = agentRecord.browser,
    version = agentRecord.version,
    expiresAt = Instant.parse("2026-01-09T10:00:00Z"),
    idleExpiresAt = Some(Instant.parse("2026-01-02T11:00:00Z")),
    userAgent = Some(agentRecord),
  )

  private def sessionJson(body: String): Json.Obj =
    body.fromJson[Json.Obj].toOption.get

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
        _ <- userRepo.upsert.succeedsWith(())
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
    test("DELETE /users without Authorization returns 401 and purges nothing") {
      val repo = stub[UserRepository]
      val logout = stub[LogoutService]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing, users = repo, logout = logout))
        resp <- client.batched(
          Request(method = Method.DELETE, url = (URL.empty / "users").addQueryParam("id", passkeyUserId.toString)),
        )
      yield assertTrue(
        resp.status == Status.Unauthorized,
        repo.delete.calls.isEmpty,
        logout.invalidateAllSessions.calls.isEmpty,
      )
    },
    test("DELETE /users with a token signed by the wrong key returns 401 and purges nothing") {
      val repo = stub[UserRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(wrongKey)
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        resp <- client.batched(
          Request(method = Method.DELETE, url = (URL.empty / "users").addQueryParam("id", passkeyUserId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.Unauthorized, repo.delete.calls.isEmpty)
    },
    test("DELETE /users with valid Bearer token logs the user out everywhere, then purges the account") {
      val repo = stub[UserRepository]
      val logout = stub[LogoutService]
      for
        order <- Ref.make(List.empty[String])
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- logout.invalidateAllSessions.returnsZIO(_ => order.update(_ :+ "logout"))
        _ <- repo.delete.returnsZIO(_ => order.update(_ :+ "purge"))
        _ <- TestClient.addRoutes(routes(tracing, users = repo, logout = logout))
        resp <- client.batched(
          Request(method = Method.DELETE, url = (URL.empty / "users").addQueryParam("id", passkeyUserId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        seen <- order.get
      yield assertTrue(
        resp.status == Status.NoContent,
        seen == List("logout", "purge"),
        logout.invalidateAllSessions.calls == List(passkeyUserId),
        repo.delete.calls == List(passkeyUserId),
      ).label("the relying parties are told while the sessions are still there to be read")
    },
    test("DELETE /users without an id is refused before anything is purged") {
      val repo = stub[UserRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        resp <- client.batched(
          Request(method = Method.DELETE, url = URL.empty / "users").addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.BadRequest, repo.delete.calls.isEmpty)
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
        _ <- userRepo.updateRoles.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing))
        body = """{"userId":"00000000-0000-0000-0000-000000000001","tenantId":"t1","add":["r1"],"remove":[]}"""
        resp <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "roles", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("PATCH /users/claims without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.PATCH, url = URL.empty / "users" / "claims", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PATCH /users/claims with valid Bearer token patches claims and returns 204") {
      val repo = stub[UserRepository]
      val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.patchClaims.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        body = """{"id":"00000000-0000-0000-0000-000000000001","claims":{"name":"John"}}"""
        resp <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "claims", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        calls = repo.patchClaims.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._1 == userId,
        calls.head._2 == Json.Obj("name" -> Json.Str("John")),
      )
    },
    test("GET /users/claims without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.get(URL.empty / "users" / "claims"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/claims with valid token returns the user's claims") {
      val repo = stub[UserRepository]
      val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      val record = UserRecord.empty(userId).copy(claims = Json.Obj("name" -> Json.Str("John")))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.find.succeedsWith(Some(record))
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "claims").addQueryParam("id", userId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
        decoded <- ZIO.fromEither(body.fromJson[UserClaimsResponse]).mapError(new RuntimeException(_))
      yield assertTrue(
        resp.status == Status.Ok,
        decoded.claims == Json.Obj("name" -> Json.Str("John")),
      )
    },
    test("GET /users/claims returns 204 when the user is not found") {
      val repo = stub[UserRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.find.succeedsWith(None)
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "claims").addQueryParam("id", "00000000-0000-0000-0000-000000000009"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /users/roles without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.get(URL.empty / "users" / "roles"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/roles with valid token returns the user's roles for the tenant") {
      val repo = stub[UserRepository]
      val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.findRolesByUserAndTenant.succeedsWith(List(RoleId("admin"), RoleId("viewer")))
        _ <- TestClient.addRoutes(routes(tracing, users = repo))
        resp <- client.batched(
          Request.get(
            (URL.empty / "users" / "roles")
              .addQueryParam("id", userId.toString)
              .addQueryParam("tenantId", "t1"),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
        decoded <- ZIO.fromEither(body.fromJson[UserRolesResponse]).mapError(new RuntimeException(_))
        calls = repo.findRolesByUserAndTenant.calls
      yield assertTrue(
        resp.status == Status.Ok,
        decoded.roles == List(RoleId("admin"), RoleId("viewer")),
        calls.size == 1,
        calls.head._1 == userId,
        calls.head._2 == TenantId("t1"),
      )
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
        _ <- sessionService.listByUser.succeedsWith(Nil)
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "sessions").addQueryParam("id", "f077fb08-9935-4a6d-8643-bf97c073bf0f"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
      yield assertTrue(
        resp.status == Status.Ok,
        body == """{"sessions":[]}""",
      )
    },
    test("GET /users/sessions carries the session's expiry, idle expiry and user agent record") {
      val sessions = stub[SessionService]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- sessions.listByUser.succeedsWith(List(
          liveSession,
          liveSession.copy(publicId = PublicSessionId("b3Ro"), idleExpiresAt = None, userAgent = None),
        ))
        _ <- TestClient.addRoutes(routes(tracing, sessionsService = sessions))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "sessions").addQueryParam("id", "f077fb08-9935-4a6d-8643-bf97c073bf0f"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
        listed = sessionJson(body).get("sessions").flatMap(_.asArray).toList.flatMap(_.toList).flatMap(_.asObject)
      yield assertTrue(
        resp.status == Status.Ok,
        listed.size == 2,
        listed.head.get("publicId").contains(Json.Str("c2lk")),
        listed.head.get("expiresAt").contains(Json.Str("2026-01-09T10:00:00Z")),
        listed.head.get("idleExpiresAt").contains(Json.Str("2026-01-02T11:00:00Z")),
        listed.head.get("userAgent").flatMap(_.asObject).flatMap(_.get("id")).contains(Json.Str(agentId.toString)),
        listed.head.get("userAgent").flatMap(_.asObject).flatMap(_.get("userAgent")).contains(Json.Str("Mozilla/5.0 (Macintosh) Chrome/130.0")),
        listed.head.get("userAgent").flatMap(_.asObject).flatMap(_.get("expiresAt")).contains(Json.Str("2026-07-01T10:00:00Z")),
        listed.head.get("platform").contains(Json.Str("desktop")),
        // A session with no idle window has no idleExpiresAt at all, and the old fields are untouched.
        listed(1).get("idleExpiresAt").isEmpty,
        listed(1).get("userAgent").isEmpty,
      )
    },
    test("GET /users/user-agents without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.get(URL.empty / "users" / "user-agents"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/user-agents lists a signed-in device with its session and a signed-out one with session null") {
      val sessions = stub[SessionService]
      val signedOutId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- sessions.listDevicesByUser.succeedsWith(
          List(
            UserDevice(Some(agentRecord), Some(liveSession), Nil),
            UserDevice(Some(agentRecord.copy(id = signedOutId, userAgent = None)), None, List(PublicSessionId("old-1"))),
          ),
        )
        _ <- TestClient.addRoutes(routes(tracing, sessionsService = sessions))
        userId = "f077fb08-9935-4a6d-8643-bf97c073bf0f"
        resp <- client.batched(
          Request.get((URL.empty / "users" / "user-agents").addQueryParam("id", userId)).addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
        devices = sessionJson(body).get("devices").flatMap(_.asArray).toList.flatMap(_.toList).flatMap(_.asObject)
      yield assertTrue(
        resp.status == Status.Ok,
        sessions.listDevicesByUser.calls == List(UserId(UUID.fromString(userId))),
        devices.size == 2,
        devices.head.get("session").flatMap(_.asObject).flatMap(_.get("publicId")).contains(Json.Str("c2lk")),
        devices.head.get("userAgent").flatMap(_.asObject).flatMap(_.get("browser")).contains(Json.Str("Chrome")),
        devices.head.get("endedSessionIds").contains(Json.Arr()),
        // `session` is present and null, not omitted: a signed-out device is a fact the caller reads.
        devices(1).get("session").contains(Json.Null),
        devices(1).get("endedSessionIds").contains(Json.Arr(Json.Str("old-1"))),
        devices(1).get("userAgent").flatMap(_.asObject).flatMap(_.get("userAgent")).isEmpty,
      )
    },
    test("DELETE /users/user-agents without Authorization returns 401") {
      val logout = stub[LogoutService]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(Request(method = Method.DELETE, url = URL.empty / "users" / "user-agents"))
      yield assertTrue(resp.status == Status.Unauthorized, logout.forgetUserAgent.calls.isEmpty)
    },
    test("DELETE /users/user-agents forgets the user agent of the user and answers 204") {
      val logout = stub[LogoutService]
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- logout.forgetUserAgent.succeedsWith(true)
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "user-agents").addQueryParam("userId", testUserId.toString).addQueryParam("userAgentId", agentId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.NoContent, logout.forgetUserAgent.calls == List((testUserId, agentId)))
    },
    test("DELETE /users/user-agents with a user agent that is not the user's answers 404") {
      val logout = stub[LogoutService]
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- logout.forgetUserAgent.succeedsWith(false)
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "user-agents").addQueryParam("userId", testUserId.toString).addQueryParam("userAgentId", agentId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.NotFound)
    },
    test("DELETE /users/user-agents without a userAgentId is a bad request and forgets nothing") {
      val logout = stub[LogoutService]
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "user-agents").addQueryParam("userId", testUserId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.BadRequest, logout.forgetUserAgent.calls.isEmpty)
    },
    test("DELETE /users/sessions without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.DELETE, url = URL.empty / "users" / "sessions"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("DELETE /users/sessions with a sessionId ends that session of the user and answers 204") {
      val logout = stub[LogoutService]
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- logout.invalidateSession.succeedsWith(true)
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "sessions").addQueryParam("userId", testUserId.toString).addQueryParam("sessionId", "c2lk"),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(
        resp.status == Status.NoContent,
        logout.invalidateSession.calls == List((PublicSessionId("c2lk"), testUserId)),
        logout.invalidateAllSessions.calls.isEmpty,
      )
    },
    test("DELETE /users/sessions with a sessionId that is not the user's answers 404") {
      val logout = stub[LogoutService]
      val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- logout.invalidateSession.succeedsWith(false)
        _ <- TestClient.addRoutes(routes(tracing, logout = logout))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "sessions").addQueryParam("userId", testUserId.toString).addQueryParam("sessionId", "c2lk"),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(resp.status == Status.NotFound, logout.invalidateAllSessions.calls.isEmpty)
    },
    test("DELETE /users/refresh-tokens without Authorization returns 401") {
      val sessions = stub[SessionService]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing, sessionsService = sessions))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "refresh-tokens").addQueryParam("userId", passkeyUserId.toString).addQueryParam("clientId", "app"),
          ),
        )
      yield assertTrue(resp.status == Status.Unauthorized, sessions.revokeRefreshTokensByUserAndClient.calls.isEmpty)
    },
    test("DELETE /users/refresh-tokens with valid token revokes the client's refresh tokens for the user") {
      val sessions = stub[SessionService]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- sessions.revokeRefreshTokensByUserAndClient.succeedsWith(2)
        _ <- TestClient.addRoutes(routes(tracing, sessionsService = sessions))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "refresh-tokens").addQueryParam("userId", passkeyUserId.toString).addQueryParam("clientId", "app"),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
      yield assertTrue(
        resp.status == Status.NoContent,
        sessions.revokeRefreshTokensByUserAndClient.calls == List((passkeyUserId, ClientId("app"))),
      )
    },
    test("DELETE /users/sessions with valid token returns 204 and invalidates all of the user's sessions") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
        _ <- logoutService.invalidateAllSessions.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "sessions").addQueryParam("userId", testUserId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
        invalidatedSessions = logoutService.invalidateAllSessions.calls.toSet
      yield assertTrue(
        resp.status == Status.NoContent,
        invalidatedSessions == Set(testUserId),
      )
    },
    test("POST /users/limits/reset without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.POST, url = URL.empty / "users" / "limits" / "reset", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("POST /users/limits/reset clears throttle for userId, email and phone") {
      val throttle = stub[ChallengeThrottleRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- throttle.deleteAllForSubject.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing, throttle = throttle))
        body = """{"userId":"00000000-0000-0000-0000-000000000001","tenantId":"t1","email":"john@doe.com","phone":"+1234567890"}"""
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "limits" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        subjects = throttle.deleteAllForSubject.calls.map(_._2).toSet
      yield assertTrue(
        resp.status == Status.NoContent,
        subjects == Set("00000000-0000-0000-0000-000000000001", "john@doe.com", "+1234567890"),
      )
    },
    test("GET /users/passkeys without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request.get(URL.empty / "users" / "passkeys"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/passkeys with valid Bearer token returns the user's passkeys") {
      val repo = stub[PasskeyRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.listByUser.succeedsWith(Vector(passkeyRecord))
        _ <- TestClient.addRoutes(routes(tracing, passkey = repo))
        resp <- client.batched(
          Request.get((URL.empty / "users" / "passkeys").addQueryParam("id", passkeyUserId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body <- resp.body.asString
        decoded <- ZIO.fromEither(body.fromJson[ListPasskeysResponse]).mapError(new RuntimeException(_))
      yield assertTrue(
        resp.status == Status.Ok,
        decoded.passkeys.size == 1,
        decoded.passkeys.head.name.contains("My Phone"),
        Base64.urlEncode(decoded.passkeys.head.id) == Base64.urlEncode(credentialId),
      )
    },
    test("PATCH /users/passkeys without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.PATCH, url = URL.empty / "users" / "passkeys", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PATCH /users/passkeys with valid Bearer token renames the passkey") {
      val repo = stub[PasskeyRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.rename.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing, passkey = repo))
        payload = RenamePasskeyPayload(passkeyUserId, credentialId, Some(PasskeyName("New Name"))).toJson
        resp <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "passkeys", body = Body.fromString(payload))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        calls = repo.rename.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._2 == passkeyUserId,
        calls.head._3.contains("New Name"),
        Base64.urlEncode(calls.head._1) == Base64.urlEncode(credentialId),
      )
    },
    test("DELETE /users/passkeys without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(Request(method = Method.DELETE, url = URL.empty / "users" / "passkeys"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("DELETE /users/passkeys with valid Bearer token deletes the passkey") {
      val repo = stub[PasskeyRepository]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- repo.deleteByUser.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing, passkey = repo))
        url = (URL.empty / "users" / "passkeys")
          .addQueryParam("id", passkeyUserId.toString)
          .addQueryParam("credentialId", Base64.urlEncode(credentialId))
        resp <- client.batched(
          Request(method = Method.DELETE, url = url).addHeader(Header.Authorization.Bearer(token)),
        )
        calls = repo.deleteByUser.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._2 == passkeyUserId,
        Base64.urlEncode(calls.head._1) == Base64.urlEncode(credentialId),
      )
    },
    test("POST /users/password/reset without Authorization returns 401") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "password" / "reset", body = Body.fromString("{}")),
        )
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("POST /users/password/reset with valid token calls resetPassword and returns 204") {
      val passwordSvc = stub[PasswordService]
      val targetUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- passwordSvc.resetPassword.succeedsWith(None)
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes
              .provideEnvironment(
                ZEnvironment(userRepo) ++ ZEnvironment(config) ++
                  ZEnvironment(logoutService) ++
                  ZEnvironment(noopThrottle) ++ ZEnvironment(noopPasskeyRepo) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(passwordSvc) ++ tracing,
              ),
          ),
        )
        body = s"""{"userId":"${targetUserId}","expiresInSeconds":null,"channel":"email"}"""
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "password" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        calls = passwordSvc.resetPassword.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._1 == targetUserId,
        calls.head._2 == None,
        calls.head._3.contains(versola.oauth.challenge.password.model.DeliveryChannel.email),
      )
    },
    test("POST /users/password/reset answers 503, not a crash, when the channel has no usable server") {
      val passwordSvc = stub[PasswordService]
      val targetUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- passwordSvc.resetPassword.failsWith(
          PasswordChannelUnavailable(targetUserId, versola.oauth.challenge.password.model.DeliveryChannel.email),
        )
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes
              .provideEnvironment(
                ZEnvironment(userRepo) ++ ZEnvironment(config) ++
                  ZEnvironment(logoutService) ++
                  ZEnvironment(noopThrottle) ++ ZEnvironment(noopPasskeyRepo) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(passwordSvc) ++ tracing,
              ),
          ),
        )
        body = s"""{"userId":"${targetUserId}","expiresInSeconds":null,"channel":"email"}"""
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "password" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        text <- resp.body.asString
      yield assertTrue(
        resp.status == Status.ServiceUnavailable,
        text == "the email delivery channel is not available",
      )
    },
    test("POST /users/password/reset without channel sends None") {
      val passwordSvc = stub[PasswordService]
      val targetUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- passwordSvc.resetPassword.succeedsWith(None)
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes
              .provideEnvironment(
                ZEnvironment(userRepo) ++ ZEnvironment(config) ++
                  ZEnvironment(logoutService) ++
                  ZEnvironment(noopThrottle) ++ ZEnvironment(noopPasskeyRepo) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(passwordSvc) ++ tracing,
              ),
          ),
        )
        body = s"""{"userId":"${targetUserId}","expiresInSeconds":3600,"channel":null}"""
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "password" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        calls = passwordSvc.resetPassword.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._2 == Some(3600L),
        calls.head._3 == None,
      )
    },
    test("POST /users/password/reset returns 200 with the plaintext when the service reveals it") {
      val passwordSvc = stub[PasswordService]
      val targetUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        _ <- passwordSvc.resetPassword.succeedsWith(Some(Password("Temp1234!")))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes
              .provideEnvironment(
                ZEnvironment(userRepo) ++ ZEnvironment(config) ++
                  ZEnvironment(logoutService) ++
                  ZEnvironment(noopThrottle) ++ ZEnvironment(noopPasskeyRepo) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(passwordSvc) ++ tracing,
              ),
          ),
        )
        body = s"""{"userId":"${targetUserId}","expiresInSeconds":null,"channel":"show"}"""
        resp <- client.batched(
          Request(method = Method.POST, url = URL.empty / "users" / "password" / "reset", body = Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        respBody <- resp.body.asString
      yield assertTrue(
        resp.status == Status.Ok,
        respBody.contains("Temp1234!"),
        passwordSvc.resetPassword.calls.head._3
          .contains(versola.oauth.challenge.password.model.DeliveryChannel.show),
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
