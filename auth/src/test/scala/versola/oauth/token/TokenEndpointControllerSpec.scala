package versola.oauth.token

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import com.nimbusds.jwt.SignedJWT
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.jwks.JwksService
import versola.oauth.client.model.{AuthMethodRef, ClientId, ClientIdWithSecret, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeVerifier, Nonce, RefreshToken}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenResponse}
import versola.oauth.userinfo.UserInfoService
import versola.oauth.userinfo.model.UserInfoResponse
import versola.user.model.{UserId, UserRecord}
import zio.json.ast.Json
import versola.util.http.{ControllerSpec, NoopTracing, Observability}
import versola.util.{Base64, CoreConfig, JWT, Secret, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
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
    audience = List(ResourceUri("https://api.example.com")),
    authorizationDetails = Nil,
    accessTokenTtl = 10.minutes,
    userId = Some(userId1),
    refreshToken = Some(refreshToken1),
    scope = scope1,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    user = None,
    tenantId = TenantId("default"),
    roles = List.empty,
    sessionId = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = Some(java.time.Instant.ofEpochSecond(1700000000)),
    acr = None,
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
        clientService = stub[OAuthConfigurationService]
        userInfoService = stub[UserInfoService]
        config = TestEnvConfig.coreConfig
        jwksService = TestEnvConfig.jwksService
        tracing <- NoopTracing.layer.build

        services = Services(tokenService, userInfoService)

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            TokenEndpointController.routes
              .provideEnvironment(ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(userInfoService) ++ ZEnvironment(jwksService) ++ ZEnvironment(config) ++ tracing)
          )
        )
        _ <- setup(services)

        response <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("TokenEndpointController")(
    suite("POST /token - authorization_code grant")(
      tokenEndpointTestCase(
        description = "successfully exchange authorization code for tokens",
        request = Request.post(
          url = URL.empty / "token",
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
            accessToken = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            tokenResponse.expiresIn == 600,
            tokenResponse.refreshToken.isDefined,
            tokenResponse.scope.contains("read write offline_access"),
            accessToken.getAudience == java.util.List.of(
              "https://api.example.com",
            ),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidClient when credentials are missing",
        request = Request.post(
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
    suite("POST /token - refresh_token grant")(
      tokenEndpointTestCase(
        description = "successfully refresh access token",
        request = Request.post(
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
    suite("POST /token - client_credentials grant")(
      tokenEndpointTestCase(
        description = "successfully issue access token for confidential client",
        request = Request.post(
          url = URL.empty / "token",
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
              accessToken = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            tokenResponse.refreshToken.isEmpty,
            tokenResponse.scope.contains("read write offline_access"),
              accessToken.getAudience == java.util.List.of(
                "https://api.example.com",
              ),
          ),
      ),
        test("decodes repeated resource parameters") {
          val resources = List(
            ResourceUri("https://api.example.com"),
            ResourceUri("resource://internal-api"),
          )
          val form = Form.fromStrings(
            "resource" -> resources.head,
            "resource" -> resources.last,
          )
          for
            result <- TokenEndpointController.clientCredentialsRequestDecoder.decode(form)
          yield assertTrue(result == ClientCredentialsRequest(scope = None, resources = Some(resources), authorizationDetails = None))
        },
        test("decodes a single comma-joined resource field, as zio-http produces from a repeated form field") {
          val resources = List(
            ResourceUri("https://api.example.com"),
            ResourceUri("resource://internal-api"),
          )
          val form = Form.fromStrings(
            "resource" -> resources.mkString(","),
          )
          for
            result <- TokenEndpointController.clientCredentialsRequestDecoder.decode(form)
          yield assertTrue(result == ClientCredentialsRequest(scope = None, resources = Some(resources), authorizationDetails = None))
        },
        test("preserves an omitted resource parameter") {
          for
            result <- TokenEndpointController.clientCredentialsRequestDecoder.decode(Form.empty)
          yield assertTrue(result == ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = None))
        },
        test("preserves an explicitly empty resource list") {
          for
            result <- TokenEndpointController.clientCredentialsRequestDecoder.decode(Form.fromStrings("resource" -> ""))
          yield assertTrue(result == ClientCredentialsRequest(scope = None, resources = Some(Nil), authorizationDetails = None))
        },
        tokenEndpointTestCase(
          description = "fail with InvalidRequest when the resource list is explicitly empty",
          request = Request.post(
            url = URL.empty / "token",
            body = Body.fromURLEncodedForm(Form.fromStrings(
              "grant_type" -> "client_credentials",
              "resource" -> "",
            )),
          ).addHeader(authHeader(clientId1, Some(clientSecret1))),
          expectedStatus = Status.BadRequest,
          setup = services =>
            services.oauthTokenService.clientCredentials.failsWith(TokenEndpointError.InvalidRequest),
          verify = response =>
            for
              body <- response.body.asString
            yield assertTrue(body.contains("invalid_request")),
        ),
        tokenEndpointTestCase(
          description = "uses requested resource audiences",
          request = Request.post(
            url = URL.empty / "token",
            body = Body.fromURLEncodedForm(Form.fromStrings(
              "grant_type" -> "client_credentials",
              "resource" -> "resource://internal-api",
              "resource" -> "https://api.example.com",
            )),
          ).addHeader(authHeader(clientId1, Some(clientSecret1))),
          expectedStatus = Status.Ok,
          setup = services => services.oauthTokenService.clientCredentials.succeedsWith(
            issuedTokens.copy(
              audience = List(
                ResourceUri("resource://internal-api"),
                ResourceUri("https://api.example.com"),
              ),
              userId = None,
              refreshToken = None,
            )
          ),
          verify = response =>
            for
              body <- response.body.asString
              tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
              audience = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet.getAudience
            yield assertTrue(
              audience == java.util.List.of(
                "resource://internal-api",
                "https://api.example.com",
              ),
              !audience.contains(TestEnvConfig.coreConfig.jwt.issuer),
            ),
        ),
      tokenEndpointTestCase(
        description = "successfully issue access token with requested scope",
        request = Request.post(
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
        description = "successfully authenticate with client_secret_post",
        request = Request.post(
          url = URL.empty / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "client_id" -> clientId1,
              "client_secret" -> Base64.urlEncode(clientSecret1),
            )
          )
        ),
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
          ),
      ),
      tokenEndpointTestCase(
        description = "authenticate a public client with client_id only",
        request = Request.post(
          url = URL.empty / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "client_id" -> clientId1,
            )
          )
        ),
        expectedStatus = Status.Ok,
        setup = services =>
          services.oauthTokenService.clientCredentials.succeedsWith(
            issuedTokens.copy(
              userId = None,
              refreshToken = None,
            )
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidClient when both Basic and post credentials are used",
        request = Request.post(
          url = URL.empty / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "client_id" -> clientId1,
              "client_secret" -> Base64.urlEncode(clientSecret1),
            )
          )
        ).addHeader(authHeader(clientId1, Some(clientSecret1))),
        expectedStatus = Status.Unauthorized,
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_client"),
          ),
      ),
      tokenEndpointTestCase(
        description = "fail with InvalidClient when client_secret is sent without client_id",
        request = Request.post(
          url = URL.empty / "token",
          body = Body.fromURLEncodedForm(
            Form.fromStrings(
              "grant_type" -> "client_credentials",
              "client_secret" -> Base64.urlEncode(clientSecret1),
            )
          )
        ),
        expectedStatus = Status.Unauthorized,
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
          url = URL.empty / "token",
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
    suite("POST /token - error cases")(
      tokenEndpointTestCase(
        description = "fail with UnsupportedGrantType for unknown grant type",
        request = Request.post(
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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

            signedIdToken = tokenResponse.idToken.map(SignedJWT.parse)
            idToken = signedIdToken.map(_.getJWTClaimsSet)

            expectedAtHash = signedIdToken.map(jwt =>
              JWT.leftHalfHash(tokenResponse.accessToken, JWT.Algorithm.valueOf(jwt.getHeader.getAlgorithm.getName))
            )

          yield assertTrue(
            idToken.map(_.getSubject) == Some(userId1.toString),
            idToken.map(_.getClaim("nonce")) == Some("test-nonce-value"),
            idToken.map(_.getClaim("name")) != null,
            idToken.map(_.getStringListClaim("amr")) == Some(java.util.List.of("pwd")),
            idToken.map(_.getLongClaim("auth_time")) == Some(1700000000L),
            idToken.map(_.getClaim("at_hash")) == expectedAtHash,
          ),
      ),
      tokenEndpointTestCase(
        description = "not issue ID token when openid scope is missing",
        request = Request.post(
          url = URL.empty / "token",
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
          url = URL.empty / "token",
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

            signedIdToken = tokenResponse.idToken.map(SignedJWT.parse)
            idToken = signedIdToken.map(_.getJWTClaimsSet)

            expectedAtHash = signedIdToken.map(jwt =>
              JWT.leftHalfHash(tokenResponse.accessToken, JWT.Algorithm.valueOf(jwt.getHeader.getAlgorithm.getName))
            )

          yield assertTrue(
            idToken.map(_.getSubject) == Some(userId1.toString),
            idToken.map(_.getClaim("nonce")) == Some("refresh-nonce"),
            idToken.map(_.getClaim("email")) != null,
            idToken.map(_.getStringListClaim("amr")) == Some(java.util.List.of("pwd")),
            idToken.map(_.getLongClaim("auth_time")) == Some(1700000000L),
            idToken.map(_.getClaim("at_hash")) == expectedAtHash,
          ),
      ),
      tokenEndpointTestCase(
        description = "not issue ID token for client_credentials grant",
        request = Request.post(
          url = URL.empty / "token",
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
    // Regression for #104: JwksService reloads the JWKS from central on a schedule and its
    // "active" (first) key can drift out of sync with auth's static private key. Signing must
    // always use config.jwt.keyId — never whatever kid JwksService currently reports as active —
    // otherwise the issued token carries a kid that doesn't match the key that actually signed it.
    suite("signing key consistency (regression for #104)")(
      test("signs the access token with config.jwt.keyId even when JwksService reports a different active kid") {
        // Simulates central having rotated in a brand-new key pair that JwksService now
        // reports as "active", while auth's own config.jwt.keyId/privateKey (TestEnvConfig's
        // pair, kid = "test-key-id") hasn't changed.
        val staleActiveKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        staleActiveKeyPairGenerator.initialize(2048)
        val staleActiveKeyPair = staleActiveKeyPairGenerator.generateKeyPair()
        val staleActiveJwk = new RSAKey.Builder(staleActiveKeyPair.getPublic.asInstanceOf[RSAPublicKey])
          .keyID("stale-active-kid-from-central")
          .algorithm(JWSAlgorithm.RS256)
          .keyUse(KeyUse.SIGNATURE)
          .build()
        val staleActiveJwkJson = staleActiveJwk.toJSONString.fromJson[Json.Obj].toOption.get
        val driftedJwks = JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(staleActiveJwkJson)))
        val driftedJwksService: JwksService = new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(driftedJwks)

        for
          client <- ZIO.service[Client]
          tokenService = stub[OAuthTokenService]
          clientService = stub[OAuthConfigurationService]
          userInfoService = stub[UserInfoService]
          tracing <- NoopTracing.layer.build
          _ <- tokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens)
          _ <- TestClient.addRoutes(
            Observability.handleErrors(
              TokenEndpointController.routes
                .provideEnvironment(
                  ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(userInfoService) ++
                    ZEnvironment(driftedJwksService) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ tracing,
                )
            )
          )
          response <- client.batched(
            Request.post(
              url = URL.empty / "token",
              body = Body.fromURLEncodedForm(
                Form.fromStrings(
                  "grant_type" -> "authorization_code",
                  "code" -> Base64.urlEncode(authCode1),
                  "redirect_uri" -> redirectUri,
                  "code_verifier" -> codeVerifier1,
                )
              )
            ).addHeader(authHeader(clientId1, Some(clientSecret1))),
          )
          body <- response.body.asString
          tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
          signedJwt = SignedJWT.parse(tokenResponse.accessToken)
        yield assertTrue(
          // kid must be the one that matches the private key actually used to sign,
          // never the unrelated "active" kid JwksService happened to report.
          TestEnvConfig.jwtConfig.keyId.contains(signedJwt.getHeader.getKeyID),
          signedJwt.getHeader.getKeyID != "stale-active-kid-from-central",
          // and the signature must actually verify against the public key that
          // corresponds to that kid, proving kid and signature are for the same key.
          signedJwt.verify(new RSASSAVerifier(TestEnvConfig.publicKey)),
        )
      }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
      test("fails the request (not the whole service) when jwt.key-id is not configured") {
        // config and code are deployed separately (see PR #104 review discussion); an old
        // runtime config paired with this code has jwt.key-id absent. That must not crash
        // config parsing / service startup -- it should fail only the requests that need to
        // sign, leaving the rest of the service up.
        val configWithoutKeyId = TestEnvConfig.coreConfig.copy(
          jwt = TestEnvConfig.jwtConfig.copy(keyId = None),
        )

        for
          client <- ZIO.service[Client]
          tokenService = stub[OAuthTokenService]
          clientService = stub[OAuthConfigurationService]
          userInfoService = stub[UserInfoService]
          tracing <- NoopTracing.layer.build
          _ <- tokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens)
          _ <- TestClient.addRoutes(
            Observability.handleErrors(
              TokenEndpointController.routes
                .provideEnvironment(
                  ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(userInfoService) ++
                    ZEnvironment(configWithoutKeyId) ++ tracing,
                )
            )
          )
          response <- client.batched(
            Request.post(
              url = URL.empty / "token",
              body = Body.fromURLEncodedForm(
                Form.fromStrings(
                  "grant_type" -> "authorization_code",
                  "code" -> Base64.urlEncode(authCode1),
                  "redirect_uri" -> redirectUri,
                  "code_verifier" -> codeVerifier1,
                )
              )
            ).addHeader(authHeader(clientId1, Some(clientSecret1))),
          )
        yield assertTrue(response.status == Status.InternalServerError)
      }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    ),
    suite("resource parameter")(
      test("does not decode resource for authorization_code") {
        val form = Form.fromStrings(
          "code" -> Base64.urlEncode(authCode1),
          "redirect_uri" -> redirectUri,
          "code_verifier" -> codeVerifier1,
          "resource" -> "not a URI",
        )
        TokenEndpointController.codeExchangeRequestDecoder.decode(form).map: request =>
          assertTrue(
            request.code == authCode1,
            request.redirectUri.encode == redirectUri,
            request.codeVerifier == codeVerifier1,
          )
      },
      test("decodes resource for refresh_token") {
        val resource = ResourceUri("https://api.example.com")
        val form = Form.fromStrings(
          "refresh_token" -> Base64.urlEncode(refreshToken1),
          "resource" -> resource,
        )
        TokenEndpointController.refreshTokenRequestDecoder.decode(form).map: request =>
          assertTrue(request.resources == Some(List(resource)))
      },
      test("rejects malformed resource") {
        val form = Form.fromStrings(
          "refresh_token" -> Base64.urlEncode(refreshToken1),
          "resource" -> "not a URI",
        )
        TokenEndpointController.refreshTokenRequestDecoder.decode(form).either.map: result =>
          assertTrue(result.isLeft)
      },
    ),
  )

