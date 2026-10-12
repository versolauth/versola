package versola.oauth.logout

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.{CoreConfig, JWT, RoutingKey}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

object BackChannelDispatcherSpec extends ZIOSpecDefault:

  private val audience = NonEmptyChunk(ClientId("client-a"), ClientId("client-b"))
  private val tenantId = TenantId("tenant-a")
  private val uri = URL.decode("https://rp.example/back-channel-logout").toOption.get
  private val customClaims = Json.Obj("events" -> Json.Obj("logout" -> Json.Obj()))

  private def dispatcher(client: Client, config: CoreConfig = TestEnvConfig.coreConfig): BackChannelDispatcher.Impl =
    BackChannelDispatcher.Impl(config, TestEnvConfig.jwksService, client)

  /** The header and the claims of the one event `dispatch` posts, for the deployment `config` describes. */
  private def dispatched(config: CoreConfig): ZIO[TestClient & Client, Throwable | JWT.Error, (Json.Obj, Json.Obj)] =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request] { request =>
          seen.set(Some(request)).as(Response.ok)
        }.toRoutes,
      )
      client <- ZIO.service[Client]
      _ <- dispatcher(client, config).dispatch(audience, tenantId, uri, "user-42", customClaims)
      request <- seen.get.someOrFail(RuntimeException("no request captured"))
      body <- request.body.asString
      token = body.stripPrefix("logout_token=")
      header <- JWT.parseHeader[Json.Obj](token)
      claims <- JWT.deserialize[Json.Obj](token, TestEnvConfig.publicKeys, JWT.Type.JWT)
    yield (header, claims)

  def spec = suite("BackChannelDispatcher")(
    test("signs and posts a logout token form-encoded to the given URI") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.ok)
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        _ <- dispatcher(client).dispatch(audience, tenantId, uri, "user-42", customClaims)
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
        body <- request.body.asString
        token = body.stripPrefix("logout_token=")
        claims <- JWT.deserialize[Json.Obj](token, TestEnvConfig.publicKeys, JWT.Type.JWT)
      yield assertTrue(
        request.method == Method.POST,
        request.url.path.encode == uri.path.encode,
        request.body.mediaType.contains(MediaType.application.`x-www-form-urlencoded`),
        claims.get("iss").contains(Json.Str(TestEnvConfig.coreConfig.jwt.issuer)),
        claims.get("sub").contains(Json.Str("user-42")),
        claims.get("aud").contains(Json.Arr(Json.Str("client-a"), Json.Str("client-b"))),
        claims.get("events") == customClaims.get("events"),
      )
    },
    // versolauth/versola#547 §6.2: every event a cell sends says which cell in its header,
    // whatever the event -- the dispatcher is the one place both logout tokens and
    // revocation SETs pass. A header parameter, not a claim: processing metadata, like kid.
    test("names the cell it runs as in the header of every event") {
      dispatched(TestEnvConfig.cellCoreConfig).map: (header, claims) =>
        assertTrue(
          header.get(RoutingKey.JoseHeader).contains(Json.Str(TestEnvConfig.cellId)),
          claims.get(RoutingKey.JoseHeader).isEmpty,
          claims.get("events") == customClaims.get("events"),
        )
    },
    test("names no cell on a deployment that is not one") {
      dispatched(TestEnvConfig.coreConfig).map: (header, claims) =>
        assertTrue(header.get(RoutingKey.JoseHeader).isEmpty, claims.get(RoutingKey.JoseHeader).isEmpty)
    },
    test("fails with the status and body when the endpoint rejects the event") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            ZIO.succeed(Response.text("client is disabled").status(Status.BadRequest))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        exit <- dispatcher(client).dispatch(audience, tenantId, uri, "user-42", customClaims).exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(_.squashTrace.getMessage.contains("400")),
        exit.causeOption.exists(_.squashTrace.getMessage.contains("client is disabled")),
      )
    },
    test("times out a delivery that never responds") {
      for
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.never).toRoutes)
        client <- ZIO.service[Client]
        fiber <- dispatcher(client).dispatch(audience, tenantId, uri, "user-42", customClaims).exit.fork
        _ <- TestClock.adjust(5.seconds)
        exit <- fiber.join
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(_.squashTrace.getMessage.contains("timed out")),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging @@ TestAspect.sequential
