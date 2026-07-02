package versola.edge

import org.scalamock.stubs.ZIOStubs
import versola.edge.model.*
import versola.util.{RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator

object EdgeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
  )

  private val postLoginUri = RedirectUri("https://app.example/home")

  private def installRoutes(edgeService: EdgeService): ZIO[Scope & TestClient, Throwable, Unit] =
    for
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        EdgeController.routes.provideEnvironment(ZEnvironment(edgeService) ++ ZEnvironment(config) ++ tracing),
      )
    yield ()

  def spec = suite("EdgeController")(
    test("GET /login redirects to the SSO authorize URL") {
      val edgeService = stub[EdgeService]
      val authorizeUrl = URL.decode("https://idp.example/authorize?client_id=web-app").toOption.get
      for
        _ <- edgeService.authorize.succeedsWith(authorizeUrl)
        _ <- installRoutes(edgeService)
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "login").addQueryParam("pid", "preset-1"))
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url == authorizeUrl),
        edgeService.authorize.calls == List(PresetId("preset-1")),
      )
    },
    test("GET /login returns 400 when the preset is not found") {
      val edgeService = stub[EdgeService]
      for
        _ <- edgeService.authorize.failsWith(PresetNotFound())
        _ <- installRoutes(edgeService)
        client <- ZIO.service[Client]
        response <- client.batched(Request.get(URL.empty / "login").addQueryParam("pid", "missing"))
      yield assertTrue(response.status == Status.BadRequest)
    },
    test("GET /complete redirects with an EDGE_SESSION cookie") {
      val edgeService = stub[EdgeService]
      val completion = EdgeService.LoginCompletion(
        accessToken = AccessToken("at-123"),
        cookieTtl = 1.hour,
        postLoginRedirectUri = postLoginUri,
        cookieDomain = Some("app.example"),
        cookiePath = Some("/"),
      )
      for
        _ <- edgeService.complete.succeedsWith(completion)
        _ <- installRoutes(edgeService)
        client <- ZIO.service[Client]
        response <- client.batched(
          Request.get(URL.empty / "complete").addQueryParam("code", "c").addQueryParam("state", "s"),
        )
        setCookie = response.header(Header.SetCookie).map(_.value)
      yield assertTrue(
        response.status == Status.SeeOther,
        setCookie.exists(c => c.name == EdgeSessionCookie.name && c.content == "at-123"),
      )
    },
    test("GET /complete returns 400 when the auth conversation is missing") {
      val edgeService = stub[EdgeService]
      for
        _ <- edgeService.complete.failsWith(AuthConversationNotFound())
        _ <- installRoutes(edgeService)
        client <- ZIO.service[Client]
        response <- client.batched(
          Request.get(URL.empty / "complete").addQueryParam("code", "c").addQueryParam("state", "s"),
        )
      yield assertTrue(response.status == Status.BadRequest)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
