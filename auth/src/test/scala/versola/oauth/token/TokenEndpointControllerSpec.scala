package versola.oauth.token

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{AuthorizationCode, CodeVerifier}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenResponse}
import versola.user.model.UserId
import versola.util.http.{ClientIdWithSecret, ControllerSpec, NoopTracing}
import versola.util.{Base64, CoreConfig, Secret, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.UUID

object TokenEndpointControllerSpec extends UnitSpecBase:
  type Service = OAuthTokenService

  val clientId1 = ClientId("test-client-1")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val authCode1 = AuthorizationCode(Array.fill(16)(1.toByte))
  val codeVerifier1 = CodeVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
  val redirectUri = "https://example.com/callback"
  val accessToken1 = AccessToken(Array.fill(32)(2.toByte))
  val refreshToken1 = RefreshToken(Array.fill(32)(3.toByte))
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"), ScopeToken.OfflineAccess)
  val clientSecret1 = Secret(Array.fill(32)(4.toByte))

  val issuedTokens = IssuedTokens(
    accessToken = accessToken1,
    clientId = clientId1,
    audience = List(clientId1),
    accessTokenTtl = 10.minutes,
    userId = Some(userId1),
    refreshToken = Some(refreshToken1),
    scope = scope1,
    requestedClaims = None,
    uiLocales = None,
  )

  def authHeader(clientId: ClientId, secret: Option[Secret]): Header.Authorization =
    val secretStr = secret.map(s => Base64.urlEncode(s)).getOrElse("")
    Header.Authorization.Basic(clientId, secretStr)

  def tokenEndpointTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[OAuthTokenService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        tokenService = stub[OAuthTokenService]
        clientService = stub[OAuthClientService]
        config = TestEnvConfig.coreConfig
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          TokenEndpointController.routes
            .provideEnvironment(ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(config) ++ tracing)
        )
        _ <- setup(tokenService)

        response <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("TokenEndpointController")(
    suite("POST /v1/token - authorization_code grant")(
      tokenEndpointTestCase(
        description = "successfully exchange authorization code for tokens",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "authorization_code",
              "code" -> Base64.urlEncode(authCode1),
              "redirect_uri" -> redirectUri,
              "code_verifier" -> codeVerifier1,
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = tokenService =>
          tokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            tokenResponse.expiresIn == 600,
            tokenResponse.refreshToken.isDefined,
            tokenResponse.scope.contains("read write offline_access"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidClient when credentials are missing",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "authorization_code",
              "code" -> Base64.urlEncode(authCode1),
              "redirect_uri" -> redirectUri,
              "code_verifier" -> codeVerifier1,
            )
          )
        ),
        expectedStatus = Status.BadRequest,
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_client"),
            response.headers.get(Header.CacheControl).isDefined,
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidGrant when code is invalid",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "authorization_code",
              "code" -> Base64.urlEncode(authCode1),
              "redirect_uri" -> redirectUri,
              "code_verifier" -> codeVerifier1,
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.exchangeAuthorizationCode.failsWith(TokenEndpointError.InvalidGrant),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_grant"),
          ),
      ),
    ),
    suite("POST /v1/token - refresh_token grant")(
      tokenEndpointTestCase(
        description = "successfully refresh access token",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "refresh_token",
              "refresh_token" -> Base64.urlEncode(refreshToken1),
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = tokenService =>
          tokenService.refreshAccessToken.succeedsWith(issuedTokens),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            tokenResponse.refreshToken.isDefined,
          ),
      ),
      tokenEndpointTestCase(
        description = "successfully refresh with reduced scope",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "refresh_token",
              "refresh_token" -> Base64.urlEncode(refreshToken1),
              "scope" -> "read",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = tokenService =>
          tokenService.refreshAccessToken.succeedsWith(issuedTokens.copy(scope = Set(ScopeToken("read")))),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.scope.contains("read"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidGrant when refresh token is invalid",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "refresh_token",
              "refresh_token" -> Base64.urlEncode(refreshToken1),
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_grant"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidScope when requested scope is invalid",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "refresh_token",
              "refresh_token" -> Base64.urlEncode(refreshToken1),
              "scope" -> "admin",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidScope),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_scope"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidGrant when refresh token already exchanged",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "refresh_token",
              "refresh_token" -> Base64.urlEncode(refreshToken1),
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_grant"),
          ),
      ),
    ),
    suite("POST /v1/token - client_credentials grant")(
      tokenEndpointTestCase(
        description = "successfully issue access token for confidential client",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = tokenService =>
          tokenService.clientCredentials.succeedsWith(
            issuedTokens.copy(
              userId = None,
              refreshToken = None,
            )
          ),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            tokenResponse.refreshToken.isEmpty,
            tokenResponse.scope.contains("read write offline_access"),
          ),
      ),
      tokenEndpointTestCase(
        description = "successfully issue access token with requested scope",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "scope" -> "read",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = tokenService =>
          tokenService.clientCredentials.succeedsWith(
            issuedTokens.copy(
              userId = None,
              refreshToken = None,
              scope = Set(ScopeToken("read")),
            )
          ),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.scope.contains("read"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidClient when client authentication fails",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.clientCredentials.failsWith(TokenEndpointError.InvalidClient),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_client"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidScope when requested scope exceeds client scope",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "scope" -> "admin superuser",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        setup = tokenService =>
          tokenService.clientCredentials.failsWith(TokenEndpointError.InvalidScope),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_scope"),
          ),
      ),
    ),
    suite("POST /v1/token - error cases")(
      tokenEndpointTestCase(
        description = "fail with UnsupportedGrantType for unknown grant type",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "password",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("unsupported_grant_type"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidRequest when grant_type is missing",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "code" -> Base64.urlEncode(authCode1),
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.BadRequest,
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("unsupported_grant_type"),
          ),
      ),
    ),
  )

