package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.util.{ConfigSnapshot, CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.silentLogging

import javax.crypto.spec.SecretKeySpec

object CentralSyncTokenServiceSpec extends ZIOSpecDefault:

  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = SecretKeySpec(Array.fill(32)(5.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey)),
  )

  /** A real snapshot over a repository that counts what it is asked to store. */
  private val countingSnapshot: ZLayer[Ref[Int], Throwable, ConfigSnapshot] = ZLayer.fromZIO:
    ZIO.serviceWithZIO[Ref[Int]]: writes =>
      val repository = new ConfigSnapshot.Repository:
        override def find(key: String): Task[Option[ConfigSnapshot.Record]] = ZIO.none
        override def save(record: ConfigSnapshot.Record): Task[Unit] = writes.update(_ + 1)
      ConfigSnapshot.make(repository, Array.fill[Byte](32)(7))

  private val syncUrl = URL.decode("http://central.test/configuration/clients/sync?tenant=secret-tenant").toOption.get

  def spec = suite("CentralSyncTokenService")(
    test("getToken returns a JWT signed with the configured secret key") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        claims <- JWT.deserialize[SignedClaims](token, secretKey, JWT.Type.JWT)
      yield assertTrue(
        claims.iss == "auth",
        claims.sub == "auth",
        claims.aud.contains("central"),
      )
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      Client.default,
      Scope.default,
    ),
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
