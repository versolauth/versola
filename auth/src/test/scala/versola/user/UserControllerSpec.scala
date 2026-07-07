package versola.user

import versola.auth.TestEnvConfig
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.client.model.TenantId
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import org.scalamock.stubs.ZIOStubs
import versola.oauth.session.SessionRepository
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.http.{NoopTracing, Observability}
import versola.util.{JWT, Patch}
import versola.util.{Base64, JWT, Patch}
import versola.util.http.{NoopTracing, Observability}
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

  private val userRepo             = stub[UserRepository]
  private val rolesRepo            = stub[UserRolesRepository]
  private val sessionRepo          = stub[SessionRepository]
  private val noopThrottle         = stub[ChallengeThrottleRepository]
  private val noopPasskeyRepo      = stub[PasskeyRepository]

  private def validToken(key: javax.crypto.SecretKey): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private val passkeyUserId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val credentialId  = CredentialId(Array.fill(32)(7.toByte))

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
    name = Some("My Phone"),
    lastUsedAt = None,
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
  )



  private def routes(
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
      sessions: SessionRepository = sessionRepo,
      throttle: ChallengeThrottleRepository = noopThrottle,
      passkey: PasskeyRepository = noopPasskeyRepo,
  ) =
    Observability.handleErrors(
      UserController.routes
        .provideEnvironment(
          ZEnvironment(userRepo) ++ ZEnvironment(rolesRepo) ++ ZEnvironment(config) ++ ZEnvironment(sessions) ++ ZEnvironment(throttle) ++ ZEnvironment(passkey) ++ tracing,
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
        _ <- rolesRepo.updateRoles.succeedsWith(())
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
        _ <- sessionRepo.findByUserId.succeedsWith(Nil)
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
    test("DELETE /users/sessions with valid token returns 204 and invalidates sessions atomically") {
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token <- validToken(secretKey)
        testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
        _ <- sessionRepo.invalidateByUserId.succeedsWith(())
        _ <- TestClient.addRoutes(routes(tracing))
        resp <- client.batched(
          Request(
            method = Method.DELETE,
            url = (URL.empty / "users" / "sessions").addQueryParam("userId", testUserId.toString),
          ).addHeader(Header.Authorization.Bearer(token)),
        )
        invalidatedSessions = sessionRepo.invalidateByUserId.calls.toSet
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
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request.get(URL.empty / "users" / "passkeys"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /users/passkeys with valid Bearer token returns the user's passkeys") {
      val repo = stub[PasskeyRepository]
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- repo.listByUser.succeedsWith(Vector(passkeyRecord))
        _       <- TestClient.addRoutes(routes(tracing, passkey = repo))
        resp    <- client.batched(
          Request.get((URL.empty / "users" / "passkeys").addQueryParam("id", passkeyUserId.toString))
            .addHeader(Header.Authorization.Bearer(token))
        )
        body    <- resp.body.asString
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
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request(method = Method.PATCH, url = URL.empty / "users" / "passkeys", body = Body.fromString("{}")))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("PATCH /users/passkeys with valid Bearer token renames the passkey") {
      val repo = stub[PasskeyRepository]
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- repo.rename.succeedsWith(())
        _       <- TestClient.addRoutes(routes(tracing, passkey = repo))
        payload  = RenamePasskeyPayload(passkeyUserId, credentialId, Some("New Name")).toJson
        resp    <- client.batched(
          Request(method = Method.PATCH, url = URL.empty / "users" / "passkeys", body = Body.fromString(payload))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
        calls    = repo.rename.calls
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
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _       <- TestClient.addRoutes(routes(tracing))
        resp    <- client.batched(Request(method = Method.DELETE, url = URL.empty / "users" / "passkeys"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("DELETE /users/passkeys with valid Bearer token deletes the passkey") {
      val repo = stub[PasskeyRepository]
      for
        client  <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        token   <- validToken(secretKey)
        _       <- repo.deleteByUser.succeedsWith(())
        _       <- TestClient.addRoutes(routes(tracing, passkey = repo))
        url      = (URL.empty / "users" / "passkeys")
          .addQueryParam("id", passkeyUserId.toString)
          .addQueryParam("credentialId", Base64.urlEncode(credentialId))
        resp    <- client.batched(
          Request(method = Method.DELETE, url = url).addHeader(Header.Authorization.Bearer(token))
        )
        calls    = repo.deleteByUser.calls
      yield assertTrue(
        resp.status == Status.NoContent,
        calls.size == 1,
        calls.head._2 == passkeyUserId,
        Base64.urlEncode(calls.head._1) == Base64.urlEncode(credentialId),
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

