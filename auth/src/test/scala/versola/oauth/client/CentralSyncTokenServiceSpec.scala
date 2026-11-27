package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.util.{Base64, ConfigSnapshot, CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.silentLogging

import java.nio.charset.StandardCharsets

object CentralSyncTokenServiceSpec extends ZIOSpecDefault:

  private case class SignedClaims(iss: String, sub: String, aud: List[String], jti: String, iat: Long, exp: Long) derives JsonDecoder

  private val configLayer = ZLayer.succeed(TestEnvConfig.coreConfig)

  /** A real snapshot over a repository that counts what it is asked to store. */
  private val countingSnapshot: ZLayer[Ref[Int], Throwable, ConfigSnapshot] = ZLayer.fromZIO:
    ZIO.serviceWithZIO[Ref[Int]]: writes =>
      val repository = new ConfigSnapshot.Repository:
        override def find(key: String): Task[Option[ConfigSnapshot.Record]] = ZIO.none
        override def save(record: ConfigSnapshot.Record): Task[Unit] = writes.update(_ + 1)
      ConfigSnapshot.make(repository, Array.fill[Byte](32)(7))

  private val syncUrl = URL.decode("http://central.test/configuration/clients/sync?tenant=secret-tenant").toOption.get

  private val cellConfigLayer = ZLayer.succeed(TestEnvConfig.cellCoreConfig)

  private val authKeys = TestEnvConfig.publicKeysOf(TestEnvConfig.authKeyPair)

  private val dependencies = (config: ULayer[CoreConfig]) =>
    ZLayer.make[CentralSyncTokenService](
      config,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      Client.default,
      Scope.default,
    )

  /** The header of a JWT, as written. */
  private def headerOf(token: String): zio.json.ast.Json.Obj =
    String(Base64.urlDecode(token.takeWhile(_ != '.')), StandardCharsets.UTF_8).fromJson[zio.json.ast.Json.Obj].toOption.get

  def spec = suite("CentralSyncTokenService")(
    test("getToken returns an RS256 JWT signed with the configured key, under the configured key id, for central") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        claims <- JWT.deserialize[SignedClaims](token, authKeys, JWT.Type.JWT)
        header = headerOf(token)
      yield assertTrue(
        header.get("alg").flatMap(_.asString).contains("RS256"),
        header.get("kid").flatMap(_.asString).contains(TestEnvConfig.authKeyPair.keyId),
        header.get("typ").flatMap(_.asString).contains("JWT"),
        claims.iss == "auth",
        claims.sub == "auth",
        claims.aud == List("central"),
        claims.jti.nonEmpty,
        claims.exp - claims.iat == 600,
      )
    }.provide(dependencies(configLayer)),
    test("getToken is not verifiable with any other key, central's included") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        byCentral <- JWT.deserialize[SignedClaims](token, TestEnvConfig.publicKeysOf(TestEnvConfig.centralKeyPair), JWT.Type.JWT).either
        // the same key id, another key: what a cell claiming to be this one would present
        byImpostor <- JWT.deserialize[SignedClaims](
          token,
          TestEnvConfig.publicKeysOf(
            TestEnvConfig.anotherCellKeyPair.copy(keyId = TestEnvConfig.authKeyPair.keyId),
          ),
          JWT.Type.JWT,
        ).either
      yield assertTrue(byCentral.isLeft, byImpostor.isLeft)
    }.provide(dependencies(configLayer)),
    test("a cell's token names the cell in the cell_id header and as its subject") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        claims <- JWT.deserialize[SignedClaims](token, authKeys, JWT.Type.JWT)
        header = headerOf(token)
      yield assertTrue(
        header.get("cell_id").flatMap(_.asString).contains(TestEnvConfig.cellId: String),
        header.get("kid").flatMap(_.asString).contains(TestEnvConfig.authKeyPair.keyId),
        claims.sub == (TestEnvConfig.cellId: String),
        claims.iss == "auth",
        claims.aud == List("central"),
      )
    }.provide(dependencies(cellConfigLayer)),
    test("the token of an auth with no cell id has no cell_id header") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
      yield assertTrue(!headerOf(token).fields.exists(_._1 == "cell_id"))
    }.provide(dependencies(configLayer)),
    test("getToken returns the cached token on repeated calls") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token1 <- service.getToken
        token2 <- service.getToken
      yield assertTrue(token1 == token2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      Client.default,
      Scope.default,
    ),
    test("syncRequest forwards the request with a Bearer Authorization header") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes,
        )
        service <- ZIO.service[CentralSyncTokenService]
        _ <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        captured <- seen.get.someOrFail(RuntimeException("No request seen"))
      yield assertTrue(
        captured.header(Header.Authorization) match
          case Some(Header.Authorization.Bearer(_)) => true
          case _ => false,
      )
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      TestClient.layer,
      Scope.default,
    ),
    test("syncRequest retries with a fresh token on 401 response") {
      for
        callCount <- Ref.make(0)
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            callCount.getAndUpdate(_ + 1).map { n =>
              if n == 0 then Response.status(Status.Unauthorized)
              else Response.ok
            }
          }.toRoutes,
        )
        service <- ZIO.service[CentralSyncTokenService]
        response <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        calls <- callCount.get
      yield assertTrue(response.status == Status.Ok, calls == 2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      TestClient.layer,
      Scope.default,
    ),
    // versolauth/versola#1052: a delta answer is about the version that was asked for, and is never recorded or replayed.
    test("liveRequest sends a GET with the Bearer token and not through the configuration snapshot, which syncRequest's GET is") {
      for
        recorded <- Ref.make(List.empty[String])
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](req => seen.set(Some(req)).as(Response.ok)).toRoutes)
        recording = new ConfigSnapshot:
          override def through(request: Request)(fetch: ZIO[Scope, Throwable, Response]): ZIO[Scope, Throwable, Response] =
            recorded.update(_ :+ request.url.encode) *> fetch
        service <- ZIO.service[CentralSyncTokenService].provideSome[Client & Scope](
          ZLayer.succeed(TestEnvConfig.coreConfig),
          ZLayer.succeed[ConfigSnapshot](recording),
          CentralSyncTokenService.live,
        )
        _ <- ZIO.scoped(service.liveRequest(Request.get(URL.root / "lists" / "sync").addQueryParam("since", "7")))
        afterLive <- recorded.get
        live <- seen.get.someOrFail(RuntimeException("No request seen"))
        _ <- ZIO.scoped(service.syncRequest(Request.get(URL.root / "lists" / "sync")))
        afterSync <- recorded.get
      yield assertTrue(
        afterLive.isEmpty,
        afterSync == List("/lists/sync"),
        live.url.encode == "/lists/sync?since=7",
        live.header(Header.Authorization).exists(_.isInstanceOf[Header.Authorization.Bearer]),
      )
    }.provide(TestClient.layer, Scope.default),
    test("liveRequest retries with a fresh token on 401 response, as syncRequest does") {
      for
        callCount <- Ref.make(0)
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            callCount.getAndUpdate(_ + 1).map(n => if n == 0 then Response.status(Status.Unauthorized) else Response.ok)
          }.toRoutes,
        )
        service <- ZIO.service[CentralSyncTokenService]
        response <- ZIO.scoped(service.liveRequest(Request.get(URL.empty)))
        calls <- callCount.get
      yield assertTrue(response.status == Status.Ok, calls == 2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      TestClient.layer,
      Scope.default,
    ),
    suite("a configuration sync that is not answered with a success")(
      // The body is valid JSON for what a sync client decodes, as a gateway's or a proxy's error answer can be.
      List(Status.BadRequest, Status.Forbidden, Status.NotFound, Status.TooManyRequests, Status.MovedPermanently).map: status =>
        test(s"fails on ${status.code}, naming the status and the path and nothing of the body or the query") {
          for
            _ <- TestClient.addRoutes(Handler.succeed(Response.json("""{"clients":[],"secret":"leaked"}""").status(status)).toRoutes)
            service <- ZIO.service[CentralSyncTokenService]
            result <- ZIO.scoped(service.syncRequest(Request.get(syncUrl))).either
          yield assertTrue(
            result == Left(CentralSyncTokenService.UnexpectedStatus(status, "/configuration/clients/sync")),
            result.left.exists(error => error.getMessage.contains(status.code.toString) && !error.getMessage.contains("leaked")),
            result.left.exists(!_.getMessage.contains("tenant")),
          )
        }.provide(configLayer, CentralSyncTokenService.live, ZLayer.succeed(ConfigSnapshot.disabled), TestClient.layer, Scope.default)
      ++ List(
        test("fails when central refuses the fresh token too, since it retries once") {
          for
            calls <- Ref.make(0)
            _ <- TestClient.addRoutes(
              Handler.fromFunctionZIO[Request](_ => calls.update(_ + 1).as(Response.json("{}").status(Status.Unauthorized))).toRoutes,
            )
            service <- ZIO.service[CentralSyncTokenService]
            result <- ZIO.scoped(service.syncRequest(Request.get(syncUrl))).either
            count <- calls.get
          yield assertTrue(result.left.exists(_.isInstanceOf[CentralSyncTokenService.UnexpectedStatus]), count == 2)
        }.provide(configLayer, CentralSyncTokenService.live, ZLayer.succeed(ConfigSnapshot.disabled), TestClient.layer, Scope.default),
        // The snapshot sits in front of the check: a 5xx stays what the first load starts from the stored copy on.
        test("leaves a 5xx to the snapshot, which fails it as SourceFailed and records nothing") {
          for
            _ <- TestClient.addRoutes(Handler.succeed(Response.json("""{"clients":[]}""").status(Status.BadGateway)).toRoutes)
            service <- ZIO.service[CentralSyncTokenService]
            result <- ZIO.scoped(service.syncRequest(Request.get(syncUrl))).either
            writes <- ZIO.serviceWithZIO[Ref[Int]](_.get)
          yield assertTrue(result.left.exists(_.isInstanceOf[ConfigSnapshot.SourceFailed]), writes == 0)
        }.provide(
          configLayer,
          CentralSyncTokenService.live,
          countingSnapshot,
          ZLayer.fromZIO(Ref.make(0)),
          TestClient.layer,
          Scope.default,
        ),
        test("hands a success over with its body") {
          for
            _ <- TestClient.addRoutes(Handler.succeed(Response.json("""{"clients":[]}""")).toRoutes)
            service <- ZIO.service[CentralSyncTokenService]
            body <- ZIO.scoped(service.syncRequest(Request.get(syncUrl)).flatMap(_.body.asString))
          yield assertTrue(body == """{"clients":[]}""")
        }.provide(configLayer, CentralSyncTokenService.live, ZLayer.succeed(ConfigSnapshot.disabled), TestClient.layer, Scope.default),
        test("leaves the statuses of a request that is not a sync to its caller") {
          for
            _ <- TestClient.addRoutes(Handler.succeed(Response.text("not allowed").status(Status.Forbidden)).toRoutes)
            service <- ZIO.service[CentralSyncTokenService]
            answer <- ZIO.scoped:
              service.syncRequest(Request.post(syncUrl, Body.empty)).flatMap(response => response.body.asString.map(response.status -> _))
          yield assertTrue(answer == (Status.Forbidden -> "not allowed"))
        }.provide(configLayer, CentralSyncTokenService.live, ZLayer.succeed(ConfigSnapshot.disabled), TestClient.layer, Scope.default),
      )*,
    ),
  ) @@ silentLogging
