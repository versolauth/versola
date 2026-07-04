package versola.central.users

import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.JWT
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.io.IOException
import java.net.ConnectException
import java.util.UUID

object AuthClientSpec extends ZIOSpecDefault:

  private case class TokenClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = TestCentralConfig.config.secretKey
  private val userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val version   = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val tenantId  = TenantId("t1")
  private val roleId    = RoleId("r1")
  private val jsonClaims = Json.Obj("role" -> Json.Str("admin"))
  private val credId    = "cred-1"

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

  private val sessionDto = AuthClient.SessionDto(
    clientId = "client-1",
    userAgent = Some("Mozilla"),
    createdAt = "2024-01-01T00:00:00Z",
  )

  private val resetRequest = ResetPasswordRequest(
    userId = userId,
    expiresInSeconds = Some(3600L),
    channel = Some(DeliveryChannel.email),
  )

  /** Captures the Authorization header from the first captured request. */
  private def captureBearer(seen: Ref[Option[Request]]): Task[String] =
    seen.get.someOrFail(new RuntimeException("No request captured")).flatMap: req =>
      ZIO.fromOption(req.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(t) => t.stringValue
      ).orElseFail(new RuntimeException("No Bearer header"))

  /** Assert the bearer is signed with the shared secret and has the expected claims. */
  private def assertBearerClaims(token: String): Task[TestResult] =
    JWT.deserialize[TokenClaims](token, secretKey, JWT.Type.JWT)
      .mapError(e => new RuntimeException(e.toString))
      .map(claims =>
        assertTrue(
          claims.iss == "central",
          claims.sub == "central",
          claims.aud == List("auth"),
        )
      )

  /** Fixed token returned by the stub AuthTokenService. */
  private val fixedToken: Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(secretKey),
    )

  private def tokenServiceLayer: ULayer[AuthTokenService] =
    ZLayer.fromZIO(fixedToken.map(t => new AuthTokenService:
      override def getToken: UIO[String] = ZIO.succeed(t)
    ).orDie)

  private def authClientLayer: ZLayer[Client & CentralConfig & AuthTokenService, Nothing, AuthClient] =
    ZLayer.fromFunction(AuthClient.Impl(_, _, _))

  /** Verifies Bearer token for a void method (route returns 204 No Content). */
  private def mkTest(name: String, call: AuthClient => Task[Unit]) =
    test(name) {
      for
        seen   <- Ref.make(Option.empty[Request])
        _      <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.status(Status.NoContent))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        _      <- call(client)
        token  <- captureBearer(seen)
        result <- assertBearerClaims(token)
      yield result
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer)

  /** Verifies Bearer token for a method that needs a JSON body in the response. */
  private def mkJsonTest(name: String, responseJson: String, call: AuthClient => Task[Unit]) =
    test(name) {
      for
        seen   <- Ref.make(Option.empty[Request])
        _      <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json(responseJson))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        _      <- call(client)
        token  <- captureBearer(seen)
        result <- assertBearerClaims(token)
      yield result
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer)

  /**
   * Creates a ZClient.Driver that fails with a typed exception for the first `failCount`
   * requests, then responds with 204 No Content for subsequent requests.
   * Returns both the driver and a Ref tracking total call count.
   */
  private def failingDriver(
    failCount: Int,
    makeException: () => Throwable,
  ): UIO[(ZClient.Driver[Any, Scope, Throwable], Ref[Int])] =
    Ref.make(0).map { counter =>
      val driver = new ZClient.Driver[Any, Scope, Throwable]:
        override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy],
        )(using trace: Trace): ZIO[Scope, Throwable, Response] =
          counter.updateAndGet(_ + 1).flatMap { n =>
            if n <= failCount then ZIO.fail(makeException())
            else ZIO.succeed(Response.status(Status.NoContent))
          }

        override def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1],
        )(using trace: Trace, ev: Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
          ZIO.dieMessage("WebSocket not used in AuthClient tests")

      (driver, counter)
    }

  /** Constructs an AuthClient.Impl using a custom driver (bypassing TestClient). */
  private def makeAuthClientWithDriver(
    driver: ZClient.Driver[Any, Scope, Throwable],
    token: String,
  ): AuthClient =
    AuthClient.Impl(
      ZClient.fromDriver(driver),
      TestCentralConfig.config,
      new AuthTokenService:
        override def getToken: UIO[String] = ZIO.succeed(token),
    )

  def spec = suite("AuthClient")(
    bearerSuite,
    decodingSuite,
    retrySuite,
  ) @@ TestAspect.silentLogging

  // ── Bearer token is attached to every outbound request ─────────────────────
  private val bearerSuite = suite("Bearer token added to all 12 methods")(
    mkTest("upsertUser",
      _.upsertUser(userId, version, None, None, None)),
    mkTest("updateUserRoles",
      _.updateUserRoles(userId, tenantId, Set(roleId), Set.empty)),
    mkTest("patchUserClaims",
      _.patchUserClaims(userId, Json.Obj())),
    mkTest("invalidateSession",
      _.invalidateSession(userId)),
    mkTest("resetUserLimits",
      _.resetUserLimits(userId, tenantId, None, None)),
    mkTest("renamePasskey",
      _.renamePasskey(userId, credId, Some("New Name"))),
    mkTest("deletePasskey",
      _.deletePasskey(userId, credId)),
    mkTest("resetPassword",
      _.resetPassword(resetRequest)),
    mkJsonTest("getUserClaims",
      """{"claims":{}}""",
      _.getUserClaims(userId).unit),
    mkJsonTest("getUserRoles",
      """{"roles":[]}""",
      _.getUserRoles(userId, tenantId).unit),
    mkJsonTest("getUserSessions",
      """{"sessions":[]}""",
      _.getUserSessions(userId).unit),
    mkJsonTest("listPasskeys",
      """{"passkeys":[]}""",
      _.listPasskeys(userId).unit),
  )

  // ── Response decoding ───────────────────────────────────────────────────────
  private val decodingSuite = suite("response decoding")(

    test("getUserClaims returns Some when server responds 200 with claims") {
      val json = """{"claims":{"role":"admin"}}"""
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserClaims(userId)
      yield assertTrue(result.isDefined)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),

    test("getUserClaims returns None when server responds 204") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NoContent))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserClaims(userId)
      yield assertTrue(result.isEmpty)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),

    test("getUserRoles decodes the returned role list") {
      val json = """{"roles":["r1","r2"]}"""
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserRoles(userId, tenantId)
      yield assertTrue(result == List(RoleId("r1"), RoleId("r2")))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),

    test("getUserSessions decodes the session list") {
      val json = AuthClient.SessionListResponse(List(sessionDto)).toJson
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserSessions(userId)
      yield assertTrue(result == List(sessionDto))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),

    test("listPasskeys decodes the passkey list") {
      val response = ListPasskeysResponse(List(passkeyInfo))
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(response.toJson))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        result <- client.listPasskeys(userId)
      yield assertTrue(result == List(passkeyInfo))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
  )

  // ── withConnectionRetry ─────────────────────────────────────────────────────
  private val retrySuite = suite("withConnectionRetry")(

    test("retries on ConnectException and eventually succeeds") {
      for
        token             <- fixedToken
        (driver, counter) <- failingDriver(2, () => new ConnectException("refused"))
        client            =  makeAuthClientWithDriver(driver, token)
        fiber             <- client.upsertUser(userId, version, None, None, None).fork
        _                 <- TestClock.adjust(1.second)
        _                 <- fiber.join
        count             <- counter.get
      // failCount=2: n=1 fail, n=2 fail, n=3 succeed → 3 calls total
      yield assertTrue(count == 3)
    },

    test("retries on IOException and eventually succeeds") {
      for
        token             <- fixedToken
        (driver, counter) <- failingDriver(1, () => new IOException("broken pipe"))
        client            =  makeAuthClientWithDriver(driver, token)
        fiber             <- client.upsertUser(userId, version, None, None, None).fork
        _                 <- TestClock.adjust(1.second)
        _                 <- fiber.join
        count             <- counter.get
      // failCount=1: n=1 fail, n=2 succeed → 2 calls total
      yield assertTrue(count == 2)
    },

    test("does not retry on non-connection exceptions") {
      for
        token             <- fixedToken
        (driver, counter) <- failingDriver(99, () => new RuntimeException("server error"))
        client            =  makeAuthClientWithDriver(driver, token)
        exit              <- client.upsertUser(userId, version, None, None, None).exit
        count             <- counter.get
      // recurWhile returns false for RuntimeException → no retry → 1 attempt
      yield assertTrue(count == 1) &&
        assert(exit)(fails(isSubtype[RuntimeException](anything)))
    },

    test("gives up after exactly 3 retries (4 total attempts)") {
      for
        token             <- fixedToken
        (driver, counter) <- failingDriver(99, () => new ConnectException("refused"))
        client            =  makeAuthClientWithDriver(driver, token)
        fiber             <- client.upsertUser(userId, version, None, None, None).fork
        _                 <- TestClock.adjust(2.seconds)
        exit              <- fiber.await
        count             <- counter.get
      // recurs(3): 1 initial + 3 retries = 4 total, all fail → final error
      yield assertTrue(count == 4) &&
        assert(exit)(fails(isSubtype[ConnectException](anything)))
    },
  )
