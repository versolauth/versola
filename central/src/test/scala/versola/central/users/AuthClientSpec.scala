package versola.central.users

import versola.central.antifraud.*
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.JWT
import versola.util.{PageLimit, PageOffset}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.io.IOException
import java.net.ConnectException
import java.time.Instant
import java.util.UUID

object AuthClientSpec extends ZIOSpecDefault:

  private case class TokenClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = TestCentralConfig.config.secretKey
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val version = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val tenantId = TenantId("t1")
  private val roleId = RoleId("r1")
  private val jsonClaims = Json.Obj("role" -> Json.Str("admin"))
  private val credId = "cred-1"

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

  private val userAgentDto = AuthClient.UserAgentDto(
    id = "00000000-0000-0000-0000-0000000000a1",
    userAgent = Some("Mozilla/5.0 (Macintosh) Chrome/125.0"),
    platform = Some("desktop"),
    os = Some("macOS"),
    browser = Some("Chrome"),
    version = Some("125"),
    createdAt = Instant.parse("2023-12-01T00:00:00Z"),
    expiresAt = Instant.parse("2024-06-01T00:00:00Z"),
  )

  private val sessionDto = AuthClient.SessionDto(
    publicId = "public-session-1",
    clients = List(AuthClient.ClientEntryDto(ClientId("client-1"), Instant.parse("2024-01-01T00:00:00Z"))),
    platform = Some("desktop"),
    os = Some("macOS"),
    browser = Some("Chrome"),
    version = Some("125"),
    createdAt = "2024-01-01T00:00:00Z",
    expiresAt = Instant.parse("2024-01-08T00:00:00Z"),
    idleExpiresAt = Some(Instant.parse("2024-01-01T01:00:00Z")),
    userAgent = Some(userAgentDto),
  )

  private val resetRequest = ResetPasswordRequest(
    userId = userId,
    expiresInSeconds = 3600L,
    channel = Some(DeliveryChannel.email),
  )

  /** Captures the Authorization header from the first captured request. */
  private def captureBearer(seen: Ref[Option[Request]]): Task[String] =
    seen.get.someOrFail(new RuntimeException("No request captured")).flatMap: req =>
      ZIO.fromOption(req.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(t) => t.stringValue).orElseFail(new RuntimeException("No Bearer header"))

  /** Assert the bearer is signed with the shared secret and has the expected claims. */
  private def assertBearerClaims(token: String): Task[TestResult] =
    JWT.deserialize[TokenClaims](token, secretKey, JWT.Type.JWT)
      .mapError(e => new RuntimeException(e.toString))
      .map(claims =>
        assertTrue(
          claims.iss == "central",
          claims.sub == "central",
          claims.aud == List("auth"),
        ),
      )

  /** Fixed token returned by the stub AuthTokenService. */
  private val fixedToken: Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(secretKey),
    )

  private def tokenServiceLayer: ULayer[AuthTokenService] =
    ZLayer.fromZIO(fixedToken.map(t =>
      new AuthTokenService:
        override def getToken: UIO[String] = ZIO.succeed(t)
        override def refreshToken(): Task[String] = ZIO.succeed(t),
    ).orDie)

  private def authClientLayer: ZLayer[Client & CentralConfig & AuthTokenService, Nothing, AuthClient] =
    ZLayer.fromFunction(AuthClient.Impl(_, _, _))

  /** Verifies Bearer token for a void method (route returns 204 No Content). */
  private def mkTest(name: String, call: AuthClient => Task[Unit]) =
    test(name) {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.status(Status.NoContent))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        _ <- call(client)
        token <- captureBearer(seen)
        result <- assertBearerClaims(token)
      yield result
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer)

  /** Verifies Bearer token for a method that needs a JSON body in the response. */
  private def mkJsonTest(name: String, responseJson: String, call: AuthClient => Task[Unit]) =
    test(name) {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json(responseJson))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        _ <- call(client)
        token <- captureBearer(seen)
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
        override def getToken: UIO[String] = ZIO.succeed(token)
        override def refreshToken(): Task[String] = ZIO.succeed(token),
    )

  def spec = suite("AuthClient")(
    bearerSuite,
    decodingSuite,
    retrySuite,
    tokenRefreshSuite,
  ) @@ TestAspect.silentLogging

  // ── Bearer token is attached to every outbound request ─────────────────────
  private val bearerSuite = suite("Bearer token added to every method")(
    mkTest(
      "deleteUser",
      _.deleteUser(userId),
    ),
    mkTest(
      "setPassword",
      _.setPassword(userId, "Temp1234!"),
    ),
    mkTest(
      "syncConfiguration",
      _.syncConfiguration(),
    ),
    mkTest(
      "upsertUser",
      _.upsertUser(userId, version, None, None, None),
    ),
    mkTest(
      "updateUserRoles",
      _.updateUserRoles(userId, tenantId, Set(roleId), Set.empty),
    ),
    mkTest(
      "patchUserClaims",
      _.patchUserClaims(userId, Json.Obj()),
    ),
    mkTest(
      "invalidateSession",
      _.invalidateSession(userId),
    ),
    mkTest(
      "endSession",
      _.endSession(userId, "c2lk").unit,
    ),
    mkTest(
      "forgetUserAgent",
      _.forgetUserAgent(userId, "00000000-0000-0000-0000-0000000000a1").unit,
    ),
    mkTest(
      "revokeRefreshTokens",
      _.revokeRefreshTokens(userId, ClientId("app")),
    ),
    mkTest(
      "resetUserLimits",
      _.resetUserLimits(userId, tenantId, None, None),
    ),
    mkTest(
      "renamePasskey",
      _.renamePasskey(userId, credId, Some("New Name")),
    ),
    mkTest(
      "deletePasskey",
      _.deletePasskey(userId, credId),
    ),
    mkTest(
      "resetPassword",
      _.resetPassword(resetRequest.userId, 3600L, resetRequest.channel).unit,
    ),
    mkJsonTest(
      "getUserClaims",
      """{"claims":{}}""",
      _.getUserClaims(userId).unit,
    ),
    mkJsonTest(
      "getUserRoles",
      """{"roles":[]}""",
      _.getUserRoles(userId, tenantId).unit,
    ),
    mkJsonTest(
      "getUserSessions",
      """{"sessions":[]}""",
      _.getUserSessions(userId).unit,
    ),
    mkJsonTest(
      "getUserDevices",
      """{"devices":[]}""",
      _.getUserDevices(userId).unit,
    ),
    mkJsonTest(
      "listPasskeys",
      """{"passkeys":[]}""",
      _.listPasskeys(userId).unit,
    ),
  )

  // ── Response decoding ───────────────────────────────────────────────────────
  private val decodingSuite = suite("response decoding")(
    test("the antifraud list calls send the entry, the userId and the parameters to auth's /antifraud/lists") {
      val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
      val request = NewListEntryRequest(
        listType = AntifraudListType.deny,
        valueType = AntifraudValueType.phone_hash,
        value = None,
        phone = Some("+77011234567"),
        tenantId = Some(tenantId),
        expiresAt = None,
        reason = None,
        source = None,
        shadow = None,
        userId = userId,
      )
      for
        seen <- Ref.make(List.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.update(_ :+ r).as(Response.json("[]"))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        _ <- client.listAntifraudListEntries(
          ListEntriesQuery(
            listType = Some(AntifraudListType.deny),
            valueType = None,
            tenantId = Some(tenantId),
            global = None,
            limit = Some(PageLimit(5)),
            offset = None,
          ),
          userId,
        )
        _ <- client.antifraudListAudit(None, Some(PageLimit(3)), userId)
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](r => seen.update(_ :+ r).as(Response.status(Status.NoContent))).toRoutes)
        _ <- client.deleteAntifraudListEntry(DeleteListEntryRequest(id = entryId, userId = userId))
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r =>
            seen.update(_ :+ r).as(Response.json("""{"matched":3,"total":10}""")),
          ).toRoutes,
        )
        previewed <- client.previewAntifraudListEntry(request)
        requests <- seen.get
        sent <- requests.last.body.asString
        sentJson = sent.fromJson[Json.Obj].toOption
      yield assertTrue(
        previewed == PreviewResponse(matched = Some(3), total = 10),
        requests(0).url.path.segments == Chunk("antifraud", "lists"),
        requests(0).url.queryParams.queryParam("listType").contains("deny"),
        requests(0).url.queryParams.queryParam("limit").contains("5"),
        requests(0).url.queryParams.queryParam("userId").contains(userId.toString),
        requests(1).url.path.segments == Chunk("antifraud", "lists", "audit"),
        requests(1).url.queryParams.queryParam("userId").contains(userId.toString),
        requests(2).method == Method.DELETE,
        requests(2).url.queryParams.queryParam("id").contains(entryId.toString),
        requests(2).url.queryParams.queryParam("userId").contains(userId.toString),
        requests(3).method == Method.POST && requests(3).url.path.segments == Chunk("antifraud", "lists", "preview"),
        sentJson.flatMap(_.get("phone")).contains(Json.Str("+77011234567")),
        sentJson.flatMap(_.get("userId")).contains(Json.Str(userId.toString)),
      )
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("auth's 400 and 404 are typed errors for an antifraud call, and any other failure is a failure") {
      def answer(status: Status, body: String) = Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text(body).status(status))).toRoutes
      val delete = DeleteListEntryRequest(id = UUID.randomUUID(), userId = userId)
      for
        client <- ZIO.service[AuthClient]
        _ <- TestClient.addRoutes(answer(Status.BadRequest, "not an IP address"))
        invalid <- client.listAntifraudListEntries(ListEntriesQuery(None, None, None, None, None, None), userId).either
        _ <- TestClient.addRoutes(answer(Status.NotFound, ""))
        missing <- client.deleteAntifraudListEntry(delete).either
        _ <- TestClient.addRoutes(answer(Status.InternalServerError, "boom"))
        broken <- client.deleteAntifraudListEntry(delete).either
      yield assertTrue(
        invalid == Left(AntifraudListError.Invalid("not an IP address")),
        missing == Left(AntifraudListError.NotFound),
        broken.left.exists(_.isInstanceOf[RuntimeException]),
      )
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("resetPassword returns None when server responds 204") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NoContent))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.resetPassword(resetRequest.userId, 3600L, resetRequest.channel)
      yield assertTrue(result.isEmpty)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("resetPassword returns the plaintext when server responds 200") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json("""{"password":"Temp1234!"}"""))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.resetPassword(resetRequest.userId, 3600L, resetRequest.channel)
      yield assertTrue(result.contains("Temp1234!"))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("getUserClaims returns Some when server responds 200 with claims") {
      val json = """{"claims":{"role":"admin"}}"""
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserClaims(userId)
      yield assertTrue(result.isDefined)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("getUserClaims returns None when server responds 204") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NoContent))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserClaims(userId)
      yield assertTrue(result.isEmpty)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("getUserRoles decodes the returned role list") {
      val json = """{"roles":["r1","r2"]}"""
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserRoles(userId, tenantId)
      yield assertTrue(result == List(RoleId("r1"), RoleId("r2")))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("endSession answers false when auth does not know the session as the user's") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NotFound))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        ended <- client.endSession(userId, "c2lk")
      yield assertTrue(!ended)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("endSession answers true when auth ended the session") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NoContent))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        ended <- client.endSession(userId, "c2lk")
      yield assertTrue(ended)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("getUserSessions decodes the session list") {
      val json = AuthClient.SessionListResponse(List(sessionDto)).toJson
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserSessions(userId)
      yield assertTrue(result == List(sessionDto))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("getUserDevices decodes a signed-in device, a signed-out one with session null, and the ended session ids") {
      val json =
        """{"devices":[
          |{"userAgent":{"id":"00000000-0000-0000-0000-0000000000a1","userAgent":"raw","platform":"desktop","os":"macOS","browser":"Chrome","version":"125",
          |"createdAt":"2023-12-01T00:00:00Z","expiresAt":"2024-06-01T00:00:00Z"},
          |"session":{"publicId":"public-session-1","clients":[],"createdAt":"2024-01-01T00:00:00Z","expiresAt":"2024-01-08T00:00:00Z"},
          |"endedSessionIds":[]},
          |{"userAgent":{"id":"00000000-0000-0000-0000-0000000000a2","createdAt":"2023-12-01T00:00:00Z","expiresAt":"2024-06-01T00:00:00Z"},
          |"session":null,"endedSessionIds":["old-1"]}
          |]}""".stripMargin
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.getUserDevices(userId)
      yield assertTrue(
        result.size == 2,
        result.head.session.map(_.publicId).contains("public-session-1"),
        result.head.session.flatMap(_.idleExpiresAt).isEmpty,
        result.head.userAgent.flatMap(_.userAgent).contains("raw"),
        result(1).session.isEmpty,
        result(1).userAgent.map(_.id).contains("00000000-0000-0000-0000-0000000000a2"),
        result(1).userAgent.flatMap(_.browser).isEmpty,
        result(1).endedSessionIds == List("old-1"),
      )
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("forgetUserAgent answers false when auth does not know the user agent as the user's") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.status(Status.NotFound))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        forgotten <- client.forgetUserAgent(userId, "00000000-0000-0000-0000-0000000000a1")
      yield assertTrue(!forgotten)
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("forgetUserAgent answers true when auth forgot it, naming the user and the user agent") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](req => seen.set(Some(req)).as(Response.status(Status.NoContent))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        forgotten <- client.forgetUserAgent(userId, "00000000-0000-0000-0000-0000000000a1")
        request <- seen.get
      yield assertTrue(
        forgotten,
        request.exists(_.method == Method.DELETE),
        request.exists(_.url.path.segments.toList == List("users", "user-agents")),
        request.flatMap(_.url.queryParams.getAll("userId").headOption).contains(userId.toString),
        request.flatMap(_.url.queryParams.getAll("userAgentId").headOption).contains("00000000-0000-0000-0000-0000000000a1"),
      )
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("listPasskeys decodes the passkey list") {
      val response = ListPasskeysResponse(List(passkeyInfo))
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(response.toJson))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        result <- client.listPasskeys(userId)
      yield assertTrue(result == List(passkeyInfo))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("send fails with the status and body when the server returns a non-success status") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text("boom").status(Status.InternalServerError))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        exit <- client.deleteUser(userId).exit
      yield assert(exit)(fails(isSubtype[RuntimeException](hasMessage(containsString("500")) && hasMessage(containsString("boom")))))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("sendReceive fails with the status and body when the server returns a non-success status") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text("nope").status(Status.BadRequest))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        exit <- client.getUserRoles(userId, tenantId).exit
      yield assert(exit)(fails(isSubtype[RuntimeException](hasMessage(containsString("400")) && hasMessage(containsString("nope")))))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("sendReceive fails when the response body isn't valid JSON") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text("not-json"))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        exit <- client.getUserRoles(userId, tenantId).exit
      yield assert(exit)(fails(isSubtype[RuntimeException](hasMessage(containsString("decode")))))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("sendReceiveOptional fails with the status and body when the server returns a non-success, non-204 status") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text("denied").status(Status.Forbidden))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        exit <- client.getUserClaims(userId).exit
      yield assert(exit)(fails(isSubtype[RuntimeException](hasMessage(containsString("403")) && hasMessage(containsString("denied")))))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
    test("sendReceiveOptional fails when a 200 response body isn't valid JSON") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.text("not-json"))).toRoutes,
        )
        client <- ZIO.service[AuthClient]
        exit <- client.getUserClaims(userId).exit
      yield assert(exit)(fails(isSubtype[RuntimeException](hasMessage(containsString("decode")))))
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer),
  )

  // ── withConnectionRetry ─────────────────────────────────────────────────────
  private val retrySuite = suite("withConnectionRetry")(
    test("retries on ConnectException and eventually succeeds") {
      for
        token <- fixedToken
        (driver, counter) <- failingDriver(2, () => new ConnectException("refused"))
        client = makeAuthClientWithDriver(driver, token)
        fiber <- client.upsertUser(userId, version, None, None, None).fork
        _ <- TestClock.adjust(1.second)
        _ <- fiber.join
        count <- counter.get
      // failCount=2: n=1 fail, n=2 fail, n=3 succeed → 3 calls total
      yield assertTrue(count == 3)
    },
    test("retries on IOException and eventually succeeds") {
      for
        token <- fixedToken
        (driver, counter) <- failingDriver(1, () => new IOException("broken pipe"))
        client = makeAuthClientWithDriver(driver, token)
        fiber <- client.upsertUser(userId, version, None, None, None).fork
        _ <- TestClock.adjust(1.second)
        _ <- fiber.join
        count <- counter.get
      // failCount=1: n=1 fail, n=2 succeed → 2 calls total
      yield assertTrue(count == 2)
    },
    test("does not retry on non-connection exceptions") {
      for
        token <- fixedToken
        (driver, counter) <- failingDriver(99, () => new RuntimeException("server error"))
        client = makeAuthClientWithDriver(driver, token)
        exit <- client.upsertUser(userId, version, None, None, None).exit
        count <- counter.get
      // recurWhile returns false for RuntimeException → no retry → 1 attempt
      yield assertTrue(count == 1) &&
        assert(exit)(fails(isSubtype[RuntimeException](anything)))
    },
    test("gives up after exactly 3 retries (4 total attempts)") {
      for
        token <- fixedToken
        (driver, counter) <- failingDriver(99, () => new ConnectException("refused"))
        client = makeAuthClientWithDriver(driver, token)
        fiber <- client.upsertUser(userId, version, None, None, None).fork
        _ <- TestClock.adjust(2.seconds)
        exit <- fiber.await
        count <- counter.get
      // recurs(3): 1 initial + 3 retries = 4 total, all fail → final error
      yield assertTrue(count == 4) &&
        assert(exit)(fails(isSubtype[ConnectException](anything)))
    },
  )

  // ── Token refresh ──────────────────────────────────────────────────────────
  private val tokenRefreshSuite = suite("token refresh")(
    test("refreshes token and retries on 401 Unauthorized") {
      for
        token1 <- ZIO.succeed("token-1")
        token2 <- ZIO.succeed("token-2")
        tokens <- Ref.make(List(token1, token2))
        refreshed <- Ref.make(false)

        tokenService = new AuthTokenService:
          override def getToken: UIO[String] = tokens.get.map(_.head)
          override def refreshToken(): Task[String] =
            refreshed.set(true) *> tokens.update(_.tail).flatMap(_ => getToken)

        // Mock driver that returns 401 for token-1 and 204 for token-2
        driver = new ZClient.Driver[Any, Scope, Throwable]:
          override def request(
              version: Version,
              method: Method,
              url: URL,
              headers: Headers,
              body: Body,
              sslConfig: Option[ClientSSLConfig],
              proxy: Option[Proxy],
          )(using trace: Trace): ZIO[Scope, Throwable, Response] =
            val bearer = headers.get(Header.Authorization).flatMap {
              case Header.Authorization.Bearer(t) => Some(t.stringValue)
              case _ => None
            }
            if bearer.contains("token-1") then ZIO.succeed(Response.status(Status.Unauthorized))
            else ZIO.succeed(Response.status(Status.NoContent))

          override def socket[Env1 <: Any](
              version: Version,
              url: URL,
              headers: Headers,
              app: WebSocketApp[Env1],
          )(using trace: Trace, ev: Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
            ZIO.dieMessage("WebSocket not used in AuthClient tests")

        client = AuthClient.Impl(ZClient.fromDriver(driver), TestCentralConfig.config, tokenService)
        _ <- client.upsertUser(userId, version, None, None, None)
        isRefreshed <- refreshed.get
      yield assertTrue(isRefreshed)
    },
  )
