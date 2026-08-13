package versola.oauth.token

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethodRef, AuthorizationDetail, AuthorizationDetailType, AuthorizationDetailTypeRecord, ClientId, ClientIdWithSecret, OAuthClientRecord, ResourceId, ResourceRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AuthorizationCode, AuthorizationCodeRecord, CodeChallenge, CodeChallengeMethod, CodeVerifier, RefreshToken}
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{PublicSessionId, RefreshAlreadyExchanged, RefreshTokenRecord, SessionId}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError}
import versola.oauth.client.model.Claim
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims}
import versola.role.model.RoleId
import versola.user.{UserRepository, UserRolesRepository}
import versola.user.model.UserId
import versola.util.{AuthPropertyGenerator, CoreConfig, JsonSchemaValidator, MAC, Secret, SecurityService}
import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.prelude.{EqualOps, NonEmptySet}
import zio.test.*

import java.time.Instant
import java.util.UUID

object OAuthTokenServiceSpec extends ZIOSpecDefault, ZIOStubs:

  val clientId1 = ClientId("test-client-1")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val publicSessionId1 = PublicSessionId("public-session-1")
  val redirectUri1 = URL.decode("https://example.com/callback").toOption.get
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"), ScopeToken.OfflineAccess)
  val scope2 = Set(ScopeToken("read"))
  val codeChallenge1 = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeVerifier1 = CodeVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

  val requestedClaims1 = RequestedClaims(
    userinfo = Map(
      Claim("email") -> ClaimRequest(Some(true), None, None),
    ),
    idToken = Map.empty,
  )
  val uiLocales1 = List("en-US", "fr-CA")

  val authCode1 = AuthorizationCode(Array.fill(16)(1.toByte))
  val codeMac1 = MAC(Array.fill(32)(2.toByte))
  val accessToken1 = AccessToken(Array.fill(32)(3.toByte))
  val refreshToken1 = RefreshToken(Array.fill(32)(4.toByte))
  val refreshTokenMac1 = MAC(Array.fill(32)(5.toByte))

  val clientSecret1 = Secret(Array.fill(32)(6.toByte))

  val amr1 = Set(AuthMethodRef.pwd)
  val authTime1 = Instant.ofEpochSecond(1700000000)

  val testClient = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = "Test Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = scope1,
    secret = Some(clientSecret1),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
  )

  val publicClientId = ClientId("public-client-1")
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    tenantId = TenantId("default"),
    clientName = "Public Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = scope2,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
  )

  val adminClient = testClient.copy(id = OAuthTokenService.centralAdminClientId)
  val adminRoles1 = Map(TenantId("default") -> List(RoleId("admin")))

  val schemaValidator: JsonSchemaValidator = JsonSchemaValidator.Impl()

  def detail(json: String): AuthorizationDetail =
    AuthorizationDetail.parse(json.fromJson[Json].toOption.get).toOption.get

  val paymentDetail = detail("""{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"}}""")
  val accountDetail = detail("""{"type":"account_information","actions":["read"]}""")

  val paymentType = AuthorizationDetailTypeRecord(
    tenantId = TenantId("default"),
    `type` = AuthorizationDetailType("payment_initiation"),
    schema = """{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"}"""
      .fromJson[Json.Obj].toOption.get,
  )

  val authorizationCodeRecord = AuthorizationCodeRecord(
    sessionId = sessionId1,
    publicSessionId = publicSessionId1,
    clientId = clientId1,
    userId = userId1,
    redirectUri = redirectUri1,
    scope = scope2,
    codeChallenge = codeChallenge1,
    codeChallengeMethod = CodeChallengeMethod.S256,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    accessToken = accessToken1,
    amr = amr1,
    authTime = authTime1,
    acr = None,
    resources = Nil,
    authorizationDetails = None,
  )

  /** Runs a refresh with the given granted/requested authorization details. */
  def refresh(
      env: Env,
      granted: List[AuthorizationDetail],
      requested: Option[List[AuthorizationDetail]],
  ): ZIO[Any, Nothing, Either[Throwable | TokenEndpointError, IssuedTokens]] =
    for
      now <- Clock.instant

      tokenRecord = RefreshTokenRecord(
        sessionId = sessionId1,
        publicSessionId = publicSessionId1,
        accessToken = accessToken1,
        userId = userId1,
        clientId = clientId1,
        audience = Nil,
        authorizationDetails = Some(granted),
        scope = scope1,
        issuedAt = now.minusSeconds(3600),
        expiresAt = now.plusSeconds(testClient.refreshTokenTtl.toSeconds),
        requestedClaims = None,
        uiLocales = None,
        nonce = None,
        previousRefreshToken = None,
        amr = amr1,
        authTime = authTime1,
        acr = None,
      )

      _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
      _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
      _ <- env.tokenRepo.findToken.succeedsWith(Some(tokenRecord))
      _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
      _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(RefreshToken(Array.fill(32)(7.toByte)))
      _ <- env.tokenRepo.createRefreshToken.succeedsWith(())
      _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

      result <- env.service.refreshAccessToken(
        RefreshTokenRequest(refreshToken1, None, None, requested),
        ClientIdWithSecret(clientId1, Some(clientSecret1)),
      ).either
    yield result

  class Env:
    val authCodeRepo = stub[AuthorizationCodeRepository]
    val clientService = stub[OAuthConfigurationService]
    clientService.getResourcesForClient.returnsWith(ZIO.succeed(Nil))
    val tokenRepo = stub[SessionRepository]
    val accessTokenRevocationService = stub[AccessTokenRevocationService]
    val securityService = stub[SecurityService]
    val propertyGenerator = stub[AuthPropertyGenerator]
    val userRepo = stub[UserRepository]
    val userRolesRepo = stub[UserRolesRepository]
    val service = OAuthTokenService.Impl(
      authCodeRepo,
      clientService,
      tokenRepo,
      accessTokenRevocationService,
      securityService,
      propertyGenerator,
      userRepo,
      userRolesRepo,
      schemaValidator,
      TestEnvConfig.coreConfig,
    )

  def spec = suite("OAuthTokenService")(
    suite("exchangeAuthorizationCode")(
      test("successfully exchange code for tokens with offline_access") {
        val env = new Env
        for
          now <- Clock.instant

          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope1,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = List(ResourceUri("https://api.example.com"), ResourceUri("resource://edge")),
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(refreshToken1)
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.createRefreshToken.succeedsWith(())
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = CodeExchangeRequest(
            authCode1,
            redirectUri1,
            codeVerifier1,
          )
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
          createCalls = env.tokenRepo.createRefreshToken.calls
        yield assertTrue(
          result.accessToken == accessToken1,
          result.refreshToken.contains(refreshToken1),
          result.clientId == clientId1,
          result.userId.contains(userId1),
          result.scope == scope1,
          result.requestedClaims.contains(requestedClaims1),
          result.uiLocales.contains(uiLocales1),
          result.audience == List(ResourceUri("https://api.example.com"), ResourceUri("resource://edge")),
          result.amr == amr1,
          result.authTime.contains(authTime1),
          createCalls.head._2.audience == List(ResourceUri("https://api.example.com"), ResourceUri("resource://edge")),
          env.clientService.getResourcesForClient.calls.isEmpty,
          env.clientService.findResource.calls.isEmpty,
          env.clientService.findResourceById.calls.isEmpty,
        )
      },
      test("successfully exchange code without offline_access (no refresh token)") {
        val env = new Env
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope2,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
        yield assertTrue(
          result.accessToken == accessToken1,
          result.refreshToken.isEmpty,
          result.scope == scope2,
        )
      },
      test("fail with InvalidClient when client verification fails") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(None)

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidClient),
        )
      },
      test("fail with InvalidGrant when code not found") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(None)

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
        )
      },
      test("fail with InvalidGrant when redirect_uri doesn't match") {
        val env = new Env
        val wrongRedirectUri = URL.decode("https://wrong.com/callback").toOption.get
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope1,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))

          request = CodeExchangeRequest(authCode1, wrongRedirectUri, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
        )
      },
      test("fail with InvalidGrant and revoke the previously issued token when code is reused (double-spend)") {
        val env = new Env
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope1,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Left(accessToken1))
          _ <- env.accessTokenRevocationService.revoke.succeedsWith(())
          _ <- env.tokenRepo.deleteByAccessToken.succeedsWith(())

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
          env.accessTokenRevocationService.revoke.calls == List(accessToken1),
          env.tokenRepo.deleteByAccessToken.calls == List(accessToken1),
          env.tokenRepo.createRefreshToken.calls.isEmpty,
        )
      },
      test("issues the access token embedded in the authorization code, not a freshly generated one") {
        val env = new Env
        val freshAccessToken = AccessToken(Array.fill(32)(11.toByte))
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope2,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(freshAccessToken)
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
        yield assertTrue(
          result.accessToken == accessToken1,
          result.accessToken != freshAccessToken,
        )
      },
      test("does not fetch admin roles for non-admin clients") {
        val env = new Env
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope2,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
        yield assertTrue(
          result.tenantId.contains("default"),
          result.roles.isEmpty,
          env.userRolesRepo.findRolesByUser.calls.isEmpty,
        )
      },
      test("fetches and embeds admin roles for the central-admin client") {
        val env = new Env
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            clientId = OAuthTokenService.centralAdminClientId,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope2,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            accessToken = accessToken1,
            amr = amr1,
            authTime = authTime1,
            acr = None,
            resources = Nil,
            authorizationDetails = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(adminClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List(RoleId("admin")))

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(OAuthTokenService.centralAdminClientId, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
        yield assertTrue(
          env.userRolesRepo.findRolesByUserAndTenant.calls.nonEmpty,
          result.tenantId.contains("default"),
          result.roles == List("admin"),
        )
      },
    ),
    suite("refreshAccessToken")(
      test("successfully refresh access token and rotate refresh token") {
        val env = new Env
        for
          now <- Clock.instant

          tokenRecord = RefreshTokenRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            accessToken = accessToken1,
            userId = userId1,
            clientId = clientId1,
            audience = List(ResourceUri("resource://edge"), ResourceUri("https://api.example.com")),
            authorizationDetails = None,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(testClient.refreshTokenTtl.toSeconds),
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
            nonce = None,
            previousRefreshToken = None,
            amr = amr1,
            authTime = authTime1,
            acr = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(7.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(8.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(newRefreshTokenMac)
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.createRefreshToken.succeedsWith(())
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = RefreshTokenRequest(
            refreshToken1,
            None,
            Some(List(ResourceUri("https://api.example.com"))),
            None,
          )
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials)

          createCalls = env.tokenRepo.createRefreshToken.calls
        yield assertTrue(
          result.accessToken == accessToken1,
          result.refreshToken.contains(newRefreshToken),
          result.requestedClaims.contains(requestedClaims1),
          result.uiLocales.contains(uiLocales1),
          result.audience == List(ResourceUri("https://api.example.com")),
          result.amr == amr1,
          result.authTime.contains(authTime1),
          createCalls.length == 1,
          createCalls.head._2.previousRefreshToken.exists(mac => java.util.Arrays.equals(mac, refreshTokenMac1)),
          createCalls.head._2.audience == List(ResourceUri("resource://edge"), ResourceUri("https://api.example.com")),
        )
      },
      test("successfully refresh with reduced scope") {
        val env = new Env
        val reducedScope = Set(ScopeToken("read"), ScopeToken.OfflineAccess)
        for
          now <- Clock.instant

          tokenRecord = RefreshTokenRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            accessToken = accessToken1,
            userId = userId1,
            clientId = clientId1,
            audience = List.empty,
            authorizationDetails = None,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(testClient.refreshTokenTtl.toSeconds),
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            previousRefreshToken = None,
            amr = amr1,
            authTime = authTime1,
            acr = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(9.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(10.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.securityService.mac.succeedsWith(newRefreshTokenMac)
          _ <- env.tokenRepo.findToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.createRefreshToken.succeedsWith(())
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          request = RefreshTokenRequest(refreshToken1, Some(reducedScope), None, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials)

          createCalls = env.tokenRepo.createRefreshToken.calls
        yield assertTrue(
          result.scope == reducedScope,
          createCalls.head._2.scope == reducedScope,
        )
      },
      test("fail with InvalidClient when client verification fails") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(None)

          request = RefreshTokenRequest(refreshToken1, None, None, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidClient),
        )
      },
      test("fail with InvalidGrant when refresh token not found") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findToken.succeedsWith(None)

          request = RefreshTokenRequest(refreshToken1, None, None, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
        )
      },
      test("fail with InvalidScope when requested scope exceeds client scope") {
        val env = new Env
        val invalidScope = Set(ScopeToken("admin"), ScopeToken.OfflineAccess)
        for
          now <- Clock.instant

          tokenRecord = RefreshTokenRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            accessToken = accessToken1,
            userId = userId1,
            clientId = clientId1,
            audience = List.empty,
            authorizationDetails = None,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(testClient.refreshTokenTtl.toSeconds),
            requestedClaims = None,
            uiLocales = None,
            nonce = None,
            previousRefreshToken = None,
            amr = amr1,
            authTime = authTime1,
            acr = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findToken.succeedsWith(Some(tokenRecord))

          request = RefreshTokenRequest(refreshToken1, Some(invalidScope), None, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidScope),
        )
      },
      test("fail with InvalidGrant when race condition occurs (create returns RefreshAlreadyExchanged)") {
        val env = new Env
        for
          now <- Clock.instant

          tokenRecord = RefreshTokenRecord(
            sessionId = sessionId1,
            publicSessionId = publicSessionId1,
            accessToken = accessToken1,
            userId = userId1,
            clientId = clientId1,
            audience = List.empty,
            authorizationDetails = None,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(testClient.refreshTokenTtl.toSeconds),
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
            nonce = None,
            previousRefreshToken = None,
            amr = amr1,
            authTime = authTime1,
            acr = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(7.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(8.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.securityService.mac.succeedsWith(newRefreshTokenMac)
          _ <- env.tokenRepo.findToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.createRefreshToken.failsWith(RefreshAlreadyExchanged())

          request = RefreshTokenRequest(refreshToken1, None, None, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
        )
      },
    ),
    suite("clientCredentials")(
      test("successfully issue access token for confidential client") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)

          request = ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.clientCredentials(request, credentials)
        yield assertTrue(
          result.accessToken == accessToken1,
          result.clientId == clientId1,
          result.userId.isEmpty,
          result.refreshToken.isEmpty,
          result.scope == scope1,
          result.requestedClaims.isEmpty,
          result.uiLocales.isEmpty,
        )
      },
      test("successfully issue access token with requested scope") {
        val env = new Env
        val requestedScope = Some(scope2)
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)

          request = ClientCredentialsRequest(scope = requestedScope, resources = None, authorizationDetails = None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.clientCredentials(request, credentials)
        yield assertTrue(
          result.scope == scope2,
        )
      },
        test("uses every resource available to the client when resources are omitted") {
          val env = new Env
          val publicResource = ResourceUri("https://api.example.com")
          val internalResource = ResourceUri("https://internal.example.com")
          val resources = List(
            ResourceRecord(ResourceId("api"), testClient.tenantId, publicResource, List(testClient.id), internal = false),
            ResourceRecord(ResourceId("internal"), testClient.tenantId, internalResource, List(testClient.id), internal = true),
          )
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            _ <- env.clientService.getResourcesForClient.succeedsWith(resources)
            _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
            result <- env.service.clientCredentials(
              ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = None),
              ClientIdWithSecret(clientId1, Some(clientSecret1)),
            )
          yield assertTrue(result.audience == List(publicResource, ResourceUri("resource://edge")))
        },
        test("fails with InvalidRequest when the resource list is explicitly empty") {
          val env = new Env
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            result <- env.service.clientCredentials(
              ClientCredentialsRequest(scope = None, resources = Some(Nil), authorizationDetails = None),
              ClientIdWithSecret(clientId1, Some(clientSecret1)),
            ).either
          yield assertTrue(result == Left(TokenEndpointError.InvalidRequest))
        },
        test("validates and includes requested public and edge resources") {
          val env = new Env
          val publicResource = ResourceUri("https://api.example.com")
          val edgeResource = ResourceUri("resource://edge")
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            _ <- env.clientService.findResource.succeedsWith(Some(ResourceRecord(ResourceId("api"), testClient.tenantId, publicResource, List(testClient.id), internal = false)))
            _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)

            request = ClientCredentialsRequest(scope = None, resources = Some(List(publicResource, edgeResource)), authorizationDetails = None)
            credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
            result <- env.service.clientCredentials(request, credentials)
          yield assertTrue(
            result.audience == List(publicResource, edgeResource),
            env.clientService.findResource.calls == List((testClient.tenantId, publicResource)),
            env.clientService.getResourcesForClient.calls.isEmpty,
          )
        },
        test("resolves an exact internal resource indicator by resource ID") {
          val env = new Env
          val internalResource = ResourceUri("resource://internal-api")
          val resource = ResourceRecord(
            ResourceId("internal-api"),
            testClient.tenantId,
            ResourceUri("https://internal.example.com"),
            List(testClient.id),
            internal = true,
          )
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            _ <- env.clientService.findResourceById.succeedsWith(Some(resource))
            _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
            result <- env.service.clientCredentials(
              ClientCredentialsRequest(scope = None, resources = Some(List(internalResource)), authorizationDetails = None),
              ClientIdWithSecret(clientId1, Some(clientSecret1)),
            )
          yield assertTrue(
            result.audience == List(internalResource),
            env.clientService.findResourceById.calls == List((testClient.tenantId, ResourceId("internal-api"))),
            env.clientService.findResource.calls.isEmpty,
            env.clientService.getResourcesForClient.calls.isEmpty,
          )
        },
        test("fails with InvalidTarget when edge is combined with an internal resource") {
          val env = new Env
          val edgeResource = ResourceUri("resource://edge")
          val internalResource = ResourceUri("resource://internal-api")
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            result <- env.service.clientCredentials(
              ClientCredentialsRequest(scope = None, resources = Some(List(edgeResource, internalResource)), authorizationDetails = None),
              ClientIdWithSecret(clientId1, Some(clientSecret1)),
            ).either
          yield assertTrue(result == Left(TokenEndpointError.InvalidTarget(edgeResource)))
        },
        test("fails with InvalidTarget when a requested resource is not registered for the tenant") {
          val env = new Env
          val resource = ResourceUri("https://unknown.example.com")
          for
            _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
            _ <- env.clientService.findResource.succeedsWith(None)
            result <- env.service.clientCredentials(
              ClientCredentialsRequest(scope = None, resources = Some(List(resource)), authorizationDetails = None),
              ClientIdWithSecret(clientId1, Some(clientSecret1)),
            ).either
          yield assertTrue(result == Left(TokenEndpointError.InvalidTarget(resource)))
        },
      test("fail with InvalidClient when client verification fails") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(None)

          request = ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.clientCredentials(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidClient),
        )
      },
      test("fail with InvalidClient when public client attempts to use client_credentials") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(publicClient))

          request = ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = None)
          credentials = ClientIdWithSecret(publicClientId, None)

          result <- env.service.clientCredentials(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidClient),
        )
      },
      test("fail with InvalidScope when requested scope exceeds client scope") {
        val env = new Env
        val invalidScope = Some(Set(ScopeToken("admin"), ScopeToken("superuser")))
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))

          request = ClientCredentialsRequest(scope = invalidScope, resources = None, authorizationDetails = None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.clientCredentials(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidScope),
        )
      },
    ),
    suite("authorization_details")(
      test("carries the granted details into the tokens issued from an authorization code") {
        val env = new Env
        for
          codeRecord = authorizationCodeRecord.copy(authorizationDetails = Some(List(paymentDetail)))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.markAsUsed.succeedsWith(Right(()))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.userRolesRepo.findRolesByUserAndTenant.succeedsWith(List.empty)

          result <- env.service.exchangeAuthorizationCode(
            CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1),
            ClientIdWithSecret(clientId1, Some(clientSecret1)),
          )
        yield assertTrue(result.authorizationDetails == List(paymentDetail))
      },
      test("keeps the granted details when a refresh request omits authorization_details") {
        val env = new Env
        for
          result <- refresh(env, granted = List(paymentDetail, accountDetail), requested = None)
        yield assertTrue(result.map(_.authorizationDetails) == Right(List(paymentDetail, accountDetail)))
      },
      test("narrows to the requested subset of the granted details") {
        val env = new Env
        for
          _ <- env.clientService.findAuthorizationDetailType.succeedsWith(
            Some(paymentType.copy(`type` = AuthorizationDetailType("account_information"))),
          )
          result <- refresh(env, granted = List(paymentDetail, accountDetail), requested = Some(List(accountDetail)))
        yield assertTrue(result.map(_.authorizationDetails) == Right(List(accountDetail)))
      },
      test("accepts a requested detail whose members differ only in order") {
        val env = new Env
        val reordered = detail("""{"instructedAmount":{"amount":"1.00","currency":"EUR"},"type":"payment_initiation"}""")
        for
          _ <- env.clientService.findAuthorizationDetailType.succeedsWith(Some(paymentType))
          result <- refresh(env, granted = List(paymentDetail), requested = Some(List(reordered)))
        yield assertTrue(result.map(_.authorizationDetails) == Right(List(reordered)))
      },
      test("rejects a requested detail that was not granted") {
        val env = new Env
        for
          result <- refresh(env, granted = List(paymentDetail), requested = Some(List(accountDetail)))
        yield assertTrue(result == Left(TokenEndpointError.InvalidAuthorizationDetails(
          "account_information was not granted by the underlying grant",
        )))
      },
      test("rejects an empty authorization_details on a refresh request") {
        val env = new Env
        for
          result <- refresh(env, granted = List(paymentDetail), requested = Some(Nil))
        yield assertTrue(result == Left(TokenEndpointError.InvalidRequest))
      },
      test("validates client_credentials details against the registry") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.clientService.findAuthorizationDetailType.succeedsWith(None)
          result <- env.service.clientCredentials(
            ClientCredentialsRequest(scope = None, resources = None, authorizationDetails = Some(List(paymentDetail))),
            ClientIdWithSecret(clientId1, Some(clientSecret1)),
          ).either
        yield assertTrue(result == Left(TokenEndpointError.InvalidAuthorizationDetails(
          "payment_initiation - unknown authorization details type",
        )))
      },
    ),
  ) 