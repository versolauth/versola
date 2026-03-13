package versola.oauth.token

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.auth.TestEnvConfig
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken}
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord, CodeChallenge, CodeChallengeMethod, CodeVerifier}
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, SessionId}
import versola.oauth.token.model.{CodeExchangeRequest, RefreshTokenRequest, TokenEndpointError}
import versola.oauth.client.model.Claim
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims}
import versola.user.model.UserId
import versola.util.http.ClientIdWithSecret
import versola.util.{AuthPropertyGenerator, CoreConfig, MAC, Secret, SecurityService}
import zio.*
import zio.http.URL
import zio.prelude.{EqualOps, NonEmptySet}
import zio.test.*

import java.time.Instant
import java.util.UUID

object OAuthTokenServiceSpec extends ZIOSpecDefault, ZIOStubs:

  val clientId1 = ClientId("test-client-1")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val sessionId1 = MAC(Array.fill(32)(1.toByte))
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

  val testClient = OAuthClientRecord(
    id = clientId1,
    clientName = "Test Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = scope1,
    externalAudience = List.empty,
    secret = Some(clientSecret1),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
  )

  class Env:
    val authCodeRepo = stub[AuthorizationCodeRepository]
    val clientService = stub[OAuthClientService]
    val tokenRepo = stub[RefreshTokenRepository]
    val securityService = stub[SecurityService]
    val propertyGenerator = stub[AuthPropertyGenerator]
    val service = OAuthTokenService.Impl(
      authCodeRepo,
      clientService,
      tokenRepo,
      securityService,
      propertyGenerator,
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
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope1,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(refreshToken1)
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.create.succeedsWith(())

          request = CodeExchangeRequest(authCode1, redirectUri1, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials)
        yield assertTrue(
          result.accessToken == accessToken1,
          result.refreshToken.contains(refreshToken1),
          result.clientId == clientId1,
          result.userId == userId1,
          result.scope == scope1,
          result.requestedClaims.contains(requestedClaims1),
          result.uiLocales.contains(uiLocales1),
        )
      },
      test("successfully exchange code without offline_access (no refresh token)") {
        val env = new Env
        for
          codeRecord = AuthorizationCodeRecord(
            sessionId = sessionId1,
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope2, // No offline_access
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))
          _ <- env.authCodeRepo.delete.succeedsWith(())
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)

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
          _ <- env.securityService.macBlake3.succeedsWith(codeMac1)
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
            clientId = clientId1,
            userId = userId1,
            redirectUri = redirectUri1,
            scope = scope1,
            codeChallenge = codeChallenge1,
            codeChallengeMethod = CodeChallengeMethod.S256,
            requestedClaims = None,
            uiLocales = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(codeMac1)
          _ <- env.authCodeRepo.find.succeedsWith(Some(codeRecord))

          request = CodeExchangeRequest(authCode1, wrongRedirectUri, codeVerifier1)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.exchangeAuthorizationCode(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
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
            userId = userId1,
            clientId = clientId1,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(TestEnvConfig.coreConfig.security.refreshTokens.ttl.toSeconds),
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
            previousRefreshToken = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(7.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(8.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(newRefreshTokenMac)
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findRefreshToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.create.succeedsWith(())

          request = RefreshTokenRequest(refreshToken1, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials)

          createCalls = env.tokenRepo.create.calls
        yield assertTrue(
          result.accessToken == accessToken1,
          result.refreshToken.contains(newRefreshToken),
          result.requestedClaims.contains(requestedClaims1),
          result.uiLocales.contains(uiLocales1),
          createCalls.length == 1,
          createCalls.head._2.previousRefreshToken.exists(mac => java.util.Arrays.equals(mac, refreshTokenMac1)),
        )
      },
      test("successfully refresh with reduced scope") {
        val env = new Env
        val reducedScope = Set(ScopeToken("read"), ScopeToken.OfflineAccess)
        for
          now <- Clock.instant

          tokenRecord = RefreshTokenRecord(
            sessionId = sessionId1,
            userId = userId1,
            clientId = clientId1,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(TestEnvConfig.coreConfig.security.refreshTokens.ttl.toSeconds),
            requestedClaims = None,
            uiLocales = None,
            previousRefreshToken = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(9.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(10.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.securityService.macBlake3.succeedsWith(newRefreshTokenMac)
          _ <- env.tokenRepo.findRefreshToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.create.succeedsWith(())

          request = RefreshTokenRequest(refreshToken1, Some(reducedScope))
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials)

          createCalls = env.tokenRepo.create.calls
        yield assertTrue(
          result.scope == reducedScope,
          createCalls.head._2.scope == reducedScope,
        )
      },
      test("fail with InvalidClient when client verification fails") {
        val env = new Env
        for
          _ <- env.clientService.verifySecret.succeedsWith(None)

          request = RefreshTokenRequest(refreshToken1, None)
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
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findRefreshToken.succeedsWith(None)

          request = RefreshTokenRequest(refreshToken1, None)
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
            userId = userId1,
            clientId = clientId1,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(TestEnvConfig.coreConfig.security.refreshTokens.ttl.toSeconds),
            requestedClaims = None,
            uiLocales = None,
            previousRefreshToken = None,
          )

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepo.findRefreshToken.succeedsWith(Some(tokenRecord))

          request = RefreshTokenRequest(refreshToken1, Some(invalidScope))
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
            userId = userId1,
            clientId = clientId1,
            scope = scope1,
            issuedAt = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(TestEnvConfig.coreConfig.security.refreshTokens.ttl.toSeconds),
            requestedClaims = Some(requestedClaims1),
            uiLocales = Some(uiLocales1),
            previousRefreshToken = None,
          )

          newRefreshToken = RefreshToken(Array.fill(32)(7.toByte))
          newRefreshTokenMac = MAC(Array.fill(32)(8.toByte))

          _ <- env.clientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.macBlake3.succeedsWith(refreshTokenMac1)
          _ <- env.securityService.macBlake3.succeedsWith(newRefreshTokenMac)
          _ <- env.tokenRepo.findRefreshToken.succeedsWith(Some(tokenRecord))
          _ <- env.propertyGenerator.nextAccessToken.succeedsWith(accessToken1)
          _ <- env.propertyGenerator.nextRefreshToken.succeedsWith(newRefreshToken)
          _ <- env.tokenRepo.create.failsWith(RefreshAlreadyExchanged())

          request = RefreshTokenRequest(refreshToken1, None)
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          result <- env.service.refreshAccessToken(request, credentials).either
        yield assertTrue(
          result == Left(TokenEndpointError.InvalidGrant),
        )
      },
    ),
  )

