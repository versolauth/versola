package versola.oauth.token

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import com.nimbusds.jwt.SignedJWT
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.dpop.DpopService
import versola.oauth.mtls.{ClientAuthentication, ClientCertificate}
import versola.util.Dpop
import versola.oauth.jwks.JwksService
import versola.oauth.client.model.{AuthMethodRef, ClientId, ClientIdWithSecret, MtlsCertificateEncoding, MtlsCertificateSource, MutualTlsSubjectType, OAuthClientRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AuthorizationCode, Cnf, CodeVerifier, Nonce, RefreshToken}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenResponse}
import versola.oauth.session.model.RefreshTokenFamilyId
import versola.oauth.userinfo.UserInfoService
import versola.oauth.userinfo.model.UserInfoResponse
import versola.user.model.{UserId, UserRecord}
import zio.json.ast.Json
import versola.util.http.{ControllerSpec, NoopTracing, Observability}
import versola.util.{Base64, CoreConfig, JWT, Secret, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.prelude.NonEmptySet
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
  val refreshToken2 = RefreshToken(Array.fill(32)(4.toByte))
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
    refreshTokenFamilyId = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = Some(java.time.Instant.ofEpochSecond(1700000000)),
    acr = None,
    cnf = None,
  )

  val jkt1 = "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I"

  val proof1 = Dpop.Proof(
    jkt = jkt1,
    jti = "jti-1",
    iat = java.time.Instant.ofEpochSecond(1700000000),
    nonce = None,
    ath = None,
  )

  def authHeader(clientId: ClientId, secret: Option[Secret]): Header.Authorization =
    val secretStr = secret.map(s => Base64.urlEncode(s)).getOrElse("")
    Header.Authorization.Basic(clientId, secretStr)

  val codeExchangeRequest = Request.post(
    url = URL.empty / "token",
    body = Body.fromURLEncodedForm(
      Form.fromStrings(
        "grant_type" -> "authorization_code",
        "code" -> Base64.urlEncode(authCode1),
        "redirect_uri" -> redirectUri,
        "code_verifier" -> codeVerifier1,
      ),
    ),
  ).addHeader(authHeader(clientId1, Some(clientSecret1)))

  val dpopCodeExchangeRequest = codeExchangeRequest.addHeader(Header.Custom("DPoP", "a.b.c"))

  import TestEnvConfig.{
    escapedClientCertificatePem as escapedCertificatePem,
    base64DerClientCertificate as base64DerCertificate,
    clientCertificateThumbprint as certificateThumbprint,
    nginxCertificateSource,
    traefikCertificateSource,
  }

  /** A client whose tokens are certificate-bound, which is what makes the controller look for
    * a certificate at all. */
  val mtlsClient = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Payments Client"),
    redirectUris = NonEmptySet(redirectUri),
    scope = scope1,
    secret = Some(clientSecret1),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    mtlsAuth = None,
    certificateBoundAccessTokens = true,
  )


  case class Services(
      oauthTokenService: Stub[OAuthTokenService],
      userInfoService: Stub[UserInfoService],
      dpopService: Stub[DpopService],
      clientService: Stub[OAuthConfigurationService],
  )

  /** Stands the endpoint up over stubbed services and hands back the client and the stubs, so
    * a test can drive more than one request against the same server. */
  def withTokenEndpoint[A](
      config: CoreConfig = TestEnvConfig.coreConfig,
      requireDpopNonce: Boolean = false,
  )(use: (Client, Services) => ZIO[Scope, Throwable, A]): ZIO[Client & TestClient & Scope, Throwable, A] =
    for
      client <- ZIO.service[Client]
      tokenService = stub[OAuthTokenService]
      clientService = stub[OAuthConfigurationService]
      // The controller looks the client up only to decide whether reading a client
      // certificate could matter to it; an unknown client never needs one.
      _ = clientService.find.returnsWith(ZIO.none)
      clientAuthentication = ClientAuthentication.Impl(clientService)
      userInfoService = stub[UserInfoService]
      jwksService = TestEnvConfig.jwksService
      dpopService = stub[DpopService]
      tracing <- NoopTracing.layer.build

      // RFC 9449 §8 is the requesting client's tenant setting, consulted only where a proof
      // is actually present -- a request with no `DPoP` header never reaches it.
      _ <- clientService.requireDpopNonce.succeedsWith(requireDpopNonce)

      services = Services(tokenService, userInfoService, dpopService, clientService)

      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          TokenEndpointController.routes
            .provideEnvironment(ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(clientAuthentication) ++ ZEnvironment(userInfoService) ++ ZEnvironment(jwksService) ++ ZEnvironment(config) ++ ZEnvironment(dpopService) ++ tracing)
        )
      )
      result <- use(client, services)
    yield result

  def tokenEndpointTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      verifyServices: Services => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
      config: CoreConfig = TestEnvConfig.coreConfig,
      requireDpopNonce: Boolean = false,
  ) =
    test(description) {
            withTokenEndpoint(config, requireDpopNonce): (client, services) =>
                for
                    _ <- setup(services)
                    response <- client.batched(request)
                    verifyResult <- verify(response)
                    verifyServicesResult <- verifyServices(services)
                yield assertTrue(response.status == expectedStatus) && verifyResult && verifyServicesResult
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
          services.oauthTokenService.exchangeAuthorizationCode.failsWith(TokenEndpointError.InvalidGrant.CodeNotFound),
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
          services.oauthTokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant.RefreshTokenNotFound),
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
          services.oauthTokenService.refreshAccessToken.failsWith(TokenEndpointError.InvalidGrant.RefreshTokenReplayed),
        verify = response =>
          for
            body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_grant"),
          ),
      ),
    ),
    suite("POST /token - Idempotency-Key")(
      {
        def refreshRequest(idempotencyKey: Option[String]) =
          val request = Request.post(
            url = URL.empty / "token",
            body = Body.fromURLEncodedForm(
              Form.fromStrings(
                "grant_type" -> "refresh_token",
                "refresh_token" -> Base64.urlEncode(refreshToken1),
              )
            ),
          ).addHeader(authHeader(clientId1, Some(clientSecret1)))
          idempotencyKey.fold(request)(request.addHeader("Idempotency-Key", _))

        def headerTest(description: String)(
            run: (Client, Services) => ZIO[Scope, Throwable, TestResult],
        ) =
          test(description) {
            withTokenEndpoint()(run)
          }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

        List(
          headerTest("the header reaches the service, which decides what it means") { (client, services) =>
            for
              _ <- services.oauthTokenService.refreshAccessToken.succeedsWith(issuedTokens)
              response <- client.batched(refreshRequest(Some("key-1")))
              keys = services.oauthTokenService.refreshAccessToken.calls.map(_._5)
            yield assertTrue(response.status == Status.Ok, keys == List(Some("key-1")))
          },
          headerTest("no header means no key") { (client, services) =>
            for
              _ <- services.oauthTokenService.refreshAccessToken.succeedsWith(issuedTokens)
              _ <- client.batched(refreshRequest(None))
              keys = services.oauthTokenService.refreshAccessToken.calls.map(_._5)
            yield assertTrue(keys == List(None))
          },
          headerTest("the header is ignored for grants other than refresh_token") { (client, services) =>
            val codeRequest = Request.post(
              url = URL.empty / "token",
              body = Body.fromURLEncodedForm(
                Form.fromStrings(
                  "grant_type" -> "authorization_code",
                  "code" -> Base64.urlEncode(authCode1),
                  "redirect_uri" -> "https://client.example.com/callback",
                  "code_verifier" -> codeVerifier1,
                )
              ),
            ).addHeader(authHeader(clientId1, Some(clientSecret1))).addHeader("Idempotency-Key", "key-1")

            for
              _ <- services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens)
              response <- client.batched(codeRequest)
            yield assertTrue(response.status == Status.Ok)
          },
        )
      }*
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
    // Regression for #104: signing must never use whichever entry the JWKS's "active" (i.e.
    // first) key happens to be -- that can drift out of sync with this instance's static
    // private key across a central-side rotation. Signing must always use
    // JwksService.signingKey, which resolves to the entry matching this instance's own
    // private key regardless of position (see JwksServiceSpec for that resolution logic) --
    // otherwise the issued token carries a kid that doesn't match the key that actually
    // signed it.
    suite("signing key consistency (regression for #104)")(
      test("signs the access token with JwksService.signingKey, never with the JWKS's raw 'active' entry") {
        // Simulates central's JWKS holding an unrelated key it just rotated in -- reported
        // as "active" because it happens to be listed first -- alongside the one that
        // actually matches this instance's own private key (TestEnvConfig's pair, kid =
        // "test-key-id"). JwksServiceSpec covers telling them apart; this only proves the
        // controller trusts JwksService.signingKey and never falls back to `.active`.
        val staleActiveKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        staleActiveKeyPairGenerator.initialize(2048)
        val staleActiveKeyPair = staleActiveKeyPairGenerator.generateKeyPair()
        val staleActiveJwk = new RSAKey.Builder(staleActiveKeyPair.getPublic.asInstanceOf[RSAPublicKey])
          .keyID("stale-active-kid-from-central")
          .algorithm(JWSAlgorithm.RS256)
          .keyUse(KeyUse.SIGNATURE)
          .build()
        val staleActiveJwkJson = staleActiveJwk.toJSONString.fromJson[Json.Obj].toOption.get
        // Same-modulus JWK entry as TestEnvConfig's own pair, so the drifted JWKS looks like
        // a real rotation window: the unrelated stale key listed first, this instance's own
        // key listed second.
        val ownJwk = new RSAKey.Builder(TestEnvConfig.publicKey)
          .keyID(TestEnvConfig.publicKeys.active.id)
          .algorithm(JWSAlgorithm.RS256)
          .keyUse(KeyUse.SIGNATURE)
          .build()
        val ownJwkJson = ownJwk.toJSONString.fromJson[Json.Obj].toOption.get
        val driftedJwks = JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(staleActiveJwkJson, ownJwkJson)))
        val driftedJwksService: JwksService = new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(driftedJwks)
          override def signingKey: Task[JWT.PublicKey] = ZIO.succeed(TestEnvConfig.publicKeys.active)

        for
          client <- ZIO.service[Client]
          tokenService = stub[OAuthTokenService]
          clientService = stub[OAuthConfigurationService]
          _ = clientService.find.returnsWith(ZIO.none)
          clientAuthentication = ClientAuthentication.Impl(clientService)
          userInfoService = stub[UserInfoService]
          tracing <- NoopTracing.layer.build
          _ <- tokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens)
          _ <- TestClient.addRoutes(
            Observability.handleErrors(
              TokenEndpointController.routes
                .provideEnvironment(
                  ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(clientAuthentication) ++ ZEnvironment(userInfoService) ++
                    ZEnvironment(driftedJwksService) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(stub[DpopService]) ++ tracing,
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
          // kid must be the one JwksService.signingKey resolved to, never the unrelated
          // "active" kid the raw JWKS happened to report first.
          signedJwt.getHeader.getKeyID == TestEnvConfig.publicKeys.active.id,
          signedJwt.getHeader.getKeyID != "stale-active-kid-from-central",
          // and the signature must actually verify against the public key that
          // corresponds to that kid, proving kid and signature are for the same key.
          signedJwt.verify(new RSASSAVerifier(TestEnvConfig.publicKey)),
        )
      }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
      test("fails the request (not the whole service) when no JWKS entry matches this instance's private key") {
        // Can happen transiently right after a private-key rotation, before central's JWKS
        // sync has caught up with the new public half. That must not crash the whole
        // service -- only requests that need to sign should fail, until the next sync.
        val noSigningKeyJwksService: JwksService = new JwksService:
          override def getPublicKeys: UIO[JWT.PublicKeys] = ZIO.succeed(TestEnvConfig.publicKeys)
          override def signingKey: Task[JWT.PublicKey] =
            ZIO.fail(RuntimeException("no JWKS entry matches this instance's configured private key -- signing key not yet published"))

        for
          client <- ZIO.service[Client]
          tokenService = stub[OAuthTokenService]
          clientService = stub[OAuthConfigurationService]
          _ = clientService.find.returnsWith(ZIO.none)
          clientAuthentication = ClientAuthentication.Impl(clientService)
          userInfoService = stub[UserInfoService]
          tracing <- NoopTracing.layer.build
          _ <- tokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens)
          _ <- TestClient.addRoutes(
            Observability.handleErrors(
              TokenEndpointController.routes
                .provideEnvironment(
                  ZEnvironment(tokenService) ++ ZEnvironment(clientService) ++ ZEnvironment(clientAuthentication) ++ ZEnvironment(userInfoService) ++
                    ZEnvironment(noSigningKeyJwksService) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(stub[DpopService]) ++ tracing,
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
    suite("POST /token - DPoP")(
      tokenEndpointTestCase(
        description = "rejects requests carrying multiple DPoP headers",
        request = dpopCodeExchangeRequest.addHeader(Header.Custom("DPoP", "second-proof")),
        expectedStatus = Status.BadRequest,
        verify = response =>
          response.body.asString.map(body => assertTrue(body.contains("invalid_dpop_proof"))),
      ),
      tokenEndpointTestCase(
        // The edge revokes a leaked chain by this claim, so a token issued without it is one
        // no family revocation can reach.
        description = "carries the refresh-token family in fam",
        request = codeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(
            issuedTokens.copy(refreshTokenFamilyId = Some(RefreshTokenFamilyId("family-1"))),
          ),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
            claims = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(claims.getStringClaim("fam") == "family-1"),
      ),
      tokenEndpointTestCase(
        description = "omits fam from a token no refresh-token family stands behind",
        request = codeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
            claims = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(claims.getStringClaim("fam") == null),
      ),
      tokenEndpointTestCase(
        description = "issues a DPoP-bound token and echoes the binding in cnf.jkt",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.dpopService.verify.succeedsWith(proof1) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(
              issuedTokens.copy(cnf = Some(Cnf.dpop(jkt1))),
            ),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
            claims = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(
            tokenResponse.tokenType == "DPoP",
            claims.getJSONObjectClaim("cnf").get("jkt") == jkt1,
          ),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._3.contains(jkt1),
          )),
      ),
      tokenEndpointTestCase(
        description = "leaves a request without a proof as an unbound Bearer token",
        request = codeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verify = response =>
          for
            body <- response.body.asString
            tokenResponse <- ZIO.fromEither(body.fromJson[TokenResponse]).mapError(new RuntimeException(_))
            claims = SignedJWT.parse(tokenResponse.accessToken).getJWTClaimsSet
          yield assertTrue(
            tokenResponse.tokenType == "Bearer",
            claims.getJSONObjectClaim("cnf") == null,
          ),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.dpopService.verify.calls.isEmpty,
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._3.isEmpty,
          )),
      ),
      tokenEndpointTestCase(
        description = "rejects a proof that does not validate with invalid_dpop_proof",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.BadRequest,
        setup = services =>
          services.dpopService.verify.failsWith(DpopService.Error.InvalidProof(Dpop.Error.UriMismatch)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("invalid_dpop_proof")),
      ),
      tokenEndpointTestCase(
        description = "rejects a replayed proof with invalid_dpop_proof",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.BadRequest,
        setup = services =>
          services.dpopService.verify.failsWith(DpopService.Error.Replayed),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("invalid_dpop_proof")),
      ),
      tokenEndpointTestCase(
        description = "answers a nonce challenge with use_dpop_nonce and serves the nonce in the DPoP-Nonce header",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.BadRequest,
        setup = services =>
          services.dpopService.verify.failsWith(DpopService.Error.NonceRequired("fresh-nonce")),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(
            body.contains("use_dpop_nonce"),
            response.headers.get("DPoP-Nonce").contains("fresh-nonce"),
          ),
      ),
      // RFC 9449 §8 is the requesting client's tenant setting, not an endpoint property, so
      // what has to be asserted is that the setting reaches the check -- the challenge itself
      // is covered above.
      tokenEndpointTestCase(
        description = "does not demand a nonce unless the client's tenant asks for one",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.dpopService.verify.succeedsWith(proof1) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(services.dpopService.verify.calls.map(_._4) == List(false))),
      ),
      tokenEndpointTestCase(
        description = "requires a nonce on every proof once the client's tenant asks for one",
        request = dpopCodeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.dpopService.verify.succeedsWith(proof1) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(services.dpopService.verify.calls.map(_._4) == List(true))),
        requireDpopNonce = true,
      ),
    ),
    suite("POST /token - mutual TLS")(
      tokenEndpointTestCase(
        description = "reads a percent-escaped PEM from the header nginx was configured to set",
        request = codeExchangeRequest.addHeader(Header.Custom("ssl-client-cert", escapedCertificatePem)),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(nginxCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed:
            val certificate = services.oauthTokenService.exchangeAuthorizationCode.calls.head._4
            assertTrue(
              certificate.map(_.thumbprint).contains(certificateThumbprint),
              certificate.map(_.subjectDn).contains("C=KZ,O=Versola Test,CN=payments-client"),
            ),
      ),
      tokenEndpointTestCase(
        description = "reads base64 DER from the header Traefik was configured to set",
        request = codeExchangeRequest.addHeader(Header.Custom("X-Forwarded-Tls-Client-Cert", base64DerCertificate)),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(traefikCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4
              .map(_.thumbprint).contains(certificateThumbprint),
          )),
      ),
      tokenEndpointTestCase(
        description = "takes the leaf from a chain Traefik forwarded as one comma-separated header",
        request = codeExchangeRequest.addHeader(
          // `passTLSClientCert` joins the chain it validated with commas, leaf first. The
          // base64 of a padded certificate does not survive being concatenated with the next
          // one, so this is rejected outright rather than read as the leaf.
          Header.Custom("X-Forwarded-Tls-Client-Cert", s"$base64DerCertificate,$base64DerCertificate"),
        ),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(traefikCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4
              .map(_.thumbprint).contains(certificateThumbprint),
          )),
      ),
      tokenEndpointTestCase(
        description = "takes the leaf from a chain nginx forwarded as one comma-separated header",
        request = codeExchangeRequest.addHeader(
          Header.Custom("ssl-client-cert", s"$escapedCertificatePem,$escapedCertificatePem"),
        ),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(nginxCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4
              .map(_.thumbprint).contains(certificateThumbprint),
          )),
      ),
      tokenEndpointTestCase(
        description = "carries every subject alternative name RFC 8705 registers a client by",
        request = codeExchangeRequest.addHeader(Header.Custom("ssl-client-cert", escapedCertificatePem)),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(nginxCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed:
            val sans = services.oauthTokenService.exchangeAuthorizationCode.calls.head._4
              .map(_.subjectAlternativeNames).getOrElse(Map.empty)
            assertTrue(
              sans.get(MutualTlsSubjectType.san_dns).contains(Set("client.example.com")),
              sans.get(MutualTlsSubjectType.san_uri).contains(Set("https://client.example.com/id")),
              sans.get(MutualTlsSubjectType.san_ip).contains(Set("203.0.113.7")),
              sans.get(MutualTlsSubjectType.san_email).contains(Set("ops@client.example.com")),
            ),
      ),
      tokenEndpointTestCase(
        description = "ignores the header for a client whose tokens are not certificate-bound",
        request = codeExchangeRequest.addHeader(Header.Custom("ssl-client-cert", escapedCertificatePem)),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient.copy(certificateBoundAccessTokens = false))) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4.isEmpty,
            // Not even asked for: the tenant's configuration cannot matter to this client.
            services.clientService.getMtlsCertificateSource.calls.isEmpty,
          )),
      ),
      tokenEndpointTestCase(
        description = "passes no certificate when the tenant's proxy terminates no mutual TLS",
        request = codeExchangeRequest.addHeader(Header.Custom("ssl-client-cert", escapedCertificatePem)),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(None) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4.isEmpty,
          )),
      ),
      tokenEndpointTestCase(
        description = "passes no certificate when the configured header is absent from the request",
        request = codeExchangeRequest,
        expectedStatus = Status.Ok,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(nginxCertificateSource)) *>
            services.oauthTokenService.exchangeAuthorizationCode.succeedsWith(issuedTokens),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.head._4.isEmpty,
          )),
      ),
      tokenEndpointTestCase(
        description = "rejects a header it cannot read as a certificate with invalid_client",
        request = codeExchangeRequest.addHeader(Header.Custom("ssl-client-cert", "not-a-certificate")),
        expectedStatus = Status.Unauthorized,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(nginxCertificateSource)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(
            body.contains("invalid_client"),
            // The reason names the deployment's proxy, so it stays in the log.
            !body.contains("X.509"),
          ),
        verifyServices = services =>
          ZIO.succeed(assertTrue(
            services.oauthTokenService.exchangeAuthorizationCode.calls.isEmpty,
          )),
      ),
      tokenEndpointTestCase(
        description = "rejects a certificate encoded differently from what the tenant configured",
        request = codeExchangeRequest.addHeader(Header.Custom("X-Forwarded-Tls-Client-Cert", escapedCertificatePem)),
        expectedStatus = Status.Unauthorized,
        setup = services =>
          services.clientService.find.succeedsWith(Some(mtlsClient)) *>
            services.clientService.getMtlsCertificateSource.succeedsWith(Some(traefikCertificateSource)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("invalid_client")),
      ),
    ),
  )
