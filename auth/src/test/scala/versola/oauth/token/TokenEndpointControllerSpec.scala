package versola.oauth.token

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import com.nimbusds.jwt.SignedJWT
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeVerifier, Nonce, RefreshToken}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenResponse}
import versola.oauth.userinfo.UserInfoService
import versola.oauth.userinfo.model.UserInfoResponse
import versola.user.model.{UserId, UserRecord}
import zio.json.ast.Json
import versola.util.http.{ClientIdWithSecret, ControllerSpec, NoopTracing}
import versola.util.{Base64, CoreConfig, Secret, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.UUID

object TokenEndpointControllerSpec extends UnitSpecBase:
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
    nonce = None,
    user = None,
  )

  def authHeader(clientId: ClientId, secret: Option[Secret]): Header.Authorization =
    val secretStr = secret.map(s => Base64.urlEncode(s)).getOrElse("")
    Header.Authorization.Basic(clientId, secretStr)

  case class Services(
      oauthTokenService: Stub[OAuthTokenService],
      userInfoService: Stub[UserInfoService],
  )

  def tokenEndpointTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        tokenService = stub[OAuthTokenService]
        clientService = stub[OAuthClientService]
        userInfoService = stub[UserInfoService]
        config = TestEnvConfig.coreConfig
        tracing <- NoopTracing.layer.build

        services = Services(tokenService, userInfoService)

        _ <- TestClient.addRoutes(
          TokenEndpointController.routes
            .provideEnvironment(ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(userInfoService) ++ ZEnvironment(config) ++ tracing)
        )
        _ <- setup(services)

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
        setup = services =>
          services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
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
        expectedStatus = Status.Unauthorized,
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
        setup = services =>
          services.oauthTokenService.exchangeAuthorizationCode.failsWith(TokenEndpointError.InvalidGrant),
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
        setup = services =>
          services.oauthTokenService.refreshAccessToken.succeedsWith(issuedTokens),
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
        setup = services =>
          services.oauthTokenService.refreshAccessToken.succeedsWith(issuedTokens.copy(scope = Set(ScopeToken("read")))),
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
        setup = services =>
          services.oauthTokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant),
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
        setup = services =>
          services.oauthTokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidScope),
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
        setup = services =>
          services.oauthTokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant),
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
        setup = services =>
          services.oauthTokenService.clientCredentials.succeedsWith(
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
        setup = services =>
          services.oauthTokenService.clientCredentials.succeedsWith(
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
        expectedStatus = Status.Unauthorized,
        setup = services =>
          services.oauthTokenService.clientCredentials.failsWith(TokenEndpointError.InvalidClient),
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
        setup = services =>
          services.oauthTokenService.clientCredentials.failsWith(TokenEndpointError.InvalidScope),
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
    suite("ID Token Issuance")(
      tokenEndpointTestCase(
        description = "issue ID token when openid scope is present in authorization code exchange",
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
        setup = services =>
          val nonce1 = Nonce("test-nonce-value")
          val testUser = UserRecord.empty(userId1)
          val tokensWithOpenId = issuedTokens.copy(
            scope = Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken.OfflineAccess),
            nonce = Some(nonce1),
            user = Some(testUser),
          )
          val userInfoResponse = UserInfoResponse(
            claims = Map(
              "sub" -> Json.Str(userId1.toString),
              "name" -> Json.Str("John Doe"),
              "nonce" -> Json.Str(nonce1.toString),
            )
          )
          for
            _ <- services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(tokensWithOpenId)
            _ <- services.userInfoService.getUserInfoForIdToken.succeedsWith(userInfoResponse)
          yield (),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))

            idToken = tokenResponse.idToken
              .map(SignedJWT.parse)
              .map(_.getJWTClaimsSet)

          yield assertTrue(
            idToken.map(_.getSubject) == Some(userId1.toString),
            idToken.map(_.getClaim("nonce")) == Some("test-nonce-value"),
            idToken.map(_.getClaim("name")) != null,
          ),
      ),
      tokenEndpointTestCase(
        description = "not issue ID token when openid scope is missing",
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
        setup = services =>
          val tokensWithoutOpenId = issuedTokens.copy(
            scope = Set(ScopeToken("profile"), ScopeToken.OfflineAccess),
            user = Some(UserRecord.empty(userId1)),
          )
          services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(tokensWithoutOpenId),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.idToken.isEmpty,
          ),
      ),
      tokenEndpointTestCase(
        description = "issue ID token with nonce from refresh token flow",
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
        setup = services =>
          val nonce1 = Nonce("refresh-nonce")
          val testUser = UserRecord.empty(userId1)
          val tokensWithOpenId = issuedTokens.copy(
            scope = Set(ScopeToken.OpenId, ScopeToken("email"), ScopeToken.OfflineAccess),
            nonce = Some(nonce1),
            user = Some(testUser),
          )
          val userInfoResponse = UserInfoResponse(
            claims = Map(
              "sub" -> Json.Str(userId1.toString),
              "email" -> Json.Str("test@example.com"),
              "nonce" -> Json.Str(nonce1.toString),
            )
          )
          for
            _ <- services.oauthTokenService.refreshAccessToken.succeedsWith(tokensWithOpenId)
            _ <- services.userInfoService.getUserInfoForIdToken.succeedsWith(userInfoResponse)
          yield (),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))

            idToken = tokenResponse.idToken
              .map(SignedJWT.parse)
              .map(_.getJWTClaimsSet)

          yield assertTrue(
            idToken.map(_.getSubject) == Some(userId1.toString),
            idToken.map(_.getClaim("nonce")) == Some("refresh-nonce"),
            idToken.map(_.getClaim("email")) != null,
          ),
      ),
      tokenEndpointTestCase(
        description = "not issue ID token for client_credentials grant",
        request = Request.post(
          url = URL.empty / "v1" / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "scope" -> "openid api",
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Ok,
        setup = services =>
          val clientCredentialsTokens = issuedTokens.copy(
            scope = Set(ScopeToken.OpenId, ScopeToken("api")),
            userId = None,
            refreshToken = None,
            user = None,
          )
          services.oauthTokenService.clientCredentials.succeedsWith(clientCredentialsTokens),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          yield assertTrue(
            tokenResponse.idToken.isEmpty, // No ID token for client_credentials
          ),
      ),
    ),
  )

