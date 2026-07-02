package versola.edge

import versola.edge.model.*
import versola.util.{RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.test.*

import java.security.KeyPairGenerator

object SSOClientSpec extends ZIOSpecDefault:

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

  private val preset = AuthorizationPreset(
    id = PresetId("preset-1"),
    clientId = ClientId("web-app"),
    description = "default",
    redirectUri = RedirectUri("https://app.example/complete"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val tokenResponse = TokenResponse(
    accessToken = AccessToken("access-1"),
    tokenType = "Bearer",
    expiresIn = 3600L,
    refreshToken = Some(RefreshToken("refresh-1")),
    refreshTokenExpiresIn = Some(7200L),
    scope = Some("openid"),
    idToken = None,
  )

  private val clientSecret = Secret("client-secret".getBytes("UTF-8"))

  private def successRoute(json: String): ZIO[TestClient, Nothing, Unit] =
    TestClient.addRoutes(
      Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(json))).toRoutes,
    )

  private def statusRoute(status: Status, json: String): ZIO[TestClient, Nothing, Unit] =
    TestClient.addRoutes(
      Handler
        .fromFunctionZIO[Request](_ =>
          ZIO.succeed(
            Response(status = status, body = Body.fromString(json))
              .addHeader(Header.ContentType(MediaType.application.json)),
          ),
        )
        .toRoutes,
    )

  def spec = suite("SSOClient")(
    test("authorizeUri builds the authorize URL with PKCE and state params") {
      for
        client <- ZIO.service[Client]
        url <- SSOClient.Impl(client, config).authorizeUri(preset, "challenge-123", State("state-xyz"))
        q = url.queryParams
      yield assertTrue(
        url.path.toString.contains("authorize"),
        q.getAll("client_id") == Chunk("web-app"),
        q.getAll("code_challenge") == Chunk("challenge-123"),
        q.getAll("code_challenge_method") == Chunk("S256"),
        q.getAll("state") == Chunk("state-xyz"),
        q.getAll("scope") == Chunk("openid"),
      )
    },
    test("exchangeAuthorizationCode posts to /token and parses the token response") {
      for
        _ <- successRoute(tokenResponse.toJson)
        client <- ZIO.service[Client]
        result <- SSOClient
          .Impl(client, config)
          .exchangeAuthorizationCode(
            Code("code-1"),
            CodeVerifier("verifier-1"),
            RedirectUri("https://app.example/complete"),
            ClientId("web-app"),
            clientSecret,
          )
      yield assertTrue(result == tokenResponse)
    },
    test("exchangeAuthorizationCode fails when the token endpoint returns an error") {
      for
        _ <- statusRoute(Status.BadRequest, """{"error":"invalid_request","error_description":"bad"}""")
        client <- ZIO.service[Client]
        result <- SSOClient
          .Impl(client, config)
          .exchangeAuthorizationCode(
            Code("code-1"),
            CodeVerifier("verifier-1"),
            RedirectUri("https://app.example/complete"),
            ClientId("web-app"),
            clientSecret,
          )
          .either
      yield assertTrue(result.isLeft)
    },
    test("exchangeRefreshToken fails with InvalidGrant on invalid_grant error") {
      for
        _ <- statusRoute(Status.BadRequest, """{"error":"invalid_grant"}""")
        client <- ZIO.service[Client]
        result <- SSOClient
          .Impl(client, config)
          .exchangeRefreshToken(RefreshToken("refresh-1"), ClientId("web-app"), clientSecret)
          .either
      yield assertTrue(result == Left(SSOClient.InvalidGrant))
    },
    test("exchangeRefreshToken returns the parsed token response on success") {
      for
        _ <- successRoute(tokenResponse.toJson)
        client <- ZIO.service[Client]
        result <- SSOClient
          .Impl(client, config)
          .exchangeRefreshToken(RefreshToken("refresh-1"), ClientId("web-app"), clientSecret)
      yield assertTrue(result == tokenResponse)
    },
    test("userInfo returns the JSON object on success") {
      for
        _ <- successRoute("""{"sub":"user-1","email":"a@b.com"}""")
        client <- ZIO.service[Client]
        result <- SSOClient.Impl(client, config).userInfo(AccessToken("access-1"))
      yield assertTrue(result.fields.exists((k, _) => k == "sub"))
    },
    test("userInfo fails with UserInfoUnauthorized on 401") {
      for
        _ <- statusRoute(Status.Unauthorized, "{}")
        client <- ZIO.service[Client]
        result <- SSOClient.Impl(client, config).userInfo(AccessToken("access-1")).either
      yield assertTrue(result == Left(SSOClient.UserInfoUnauthorized))
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging
