package versola.oauth.revoke

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.logout.BackChannelDispatcher
import versola.oauth.client.model.{AuthMethodRef, ClientId, ClientIdWithSecret, OAuthClientRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{PublicSessionId, RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID

object RevocationServiceSpec extends UnitSpecBase:

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val publicSessionId1 = PublicSessionId("public-session-1")
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  
  val refreshToken1 = RefreshToken(Array.fill(32)(10.toByte))
  val refreshTokenMac1 = MAC(Array.fill(32)(11.toByte))
  
  val accessToken1 = AccessToken(Array.fill(32)(20.toByte))
  
  val clientSecret1 = Secret(Array.fill(32)(30.toByte))
  
  val testClient = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = scope1,
    secret = Some(clientSecret1),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
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
  )

  def tokenRecord(now: Instant) = RefreshTokenRecord(
    sessionId = sessionId1,
    publicSessionId = publicSessionId1,
    accessToken = accessToken1,
    userId = userId1,
    clientId = clientId1,
    audience = List.empty,
    authorizationDetails = None,
    scope = scope1,
    issuedAt = now,
    expiresAt = now.plusSeconds(3600),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    previousRefreshToken = None,
    amr = Set(AuthMethodRef.pwd),
    authTime = now,
    acr = None,
  )

  def accessTokenPayload(now: Instant) = AccessTokenPayload(
    subject = userId1.toString,
    clientId = clientId1,
    scope = scope1,
    requestedClaims = None,
    expiresAt = now.plusSeconds(3600),
    issuedAt = now,
    notBefore = None,
    audience = Vector(ResourceUri("https://api.example.com")),
    issuer = "https://auth.example.com",
    id = accessToken1,
    authorizationDetails = None,
    sessionId = None,
  )

  class Env:
    val oauthClientService = stub[OAuthConfigurationService]
    val tokenRepository = stub[SessionRepository]
    val accessTokenRevocationService = stub[AccessTokenRevocationService]
    val securityService = stub[SecurityService]
    val config = TestEnvConfig.coreConfig

    val layer = ZLayer.succeed(oauthClientService) ++
      ZLayer.succeed(tokenRepository) ++
      ZLayer.succeed(accessTokenRevocationService) ++
      ZLayer.succeed(securityService) ++
      ZLayer.succeed(config) >>> RevocationService.live

  val spec = suite("RevocationService")(
    suite("revokeRefreshToken")(
      test("successfully revoke refresh token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(Some(tokenRecord(now)))
          _ <- env.tokenRepository.delete.succeedsWith(())
          _ <- env.accessTokenRevocationService.revoke.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials)
        yield assertTrue(
          result == (),
          // The access token itself was never presented, so its lifetime is bounded by the
          // client's TTL rather than read off the token.
          env.accessTokenRevocationService.revoke.calls ==
            List((testClient, accessToken1, userId1.toString, now.plus(testClient.accessTokenTtl))),
        )).provide(env.layer)
      },
      test("fail with InvalidClient when client authentication fails") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(None)

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
      },
      test("fail with InvalidClient when token belongs to different client") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          otherClient = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(otherClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(Some(tokenRecord(now)))

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
      },
      test("succeed when token not found (idempotent)") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(None)
          _ <- env.tokenRepository.delete.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials)
        yield assertTrue(result == ())).provide(env.layer)
      },
    ),
    suite("revokeAccessToken")(
      test("successfully revoke access token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.accessTokenRevocationService.revoke.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeAccessToken(payload, credentials)
        yield assertTrue(
          result == (),
          // The token was parsed here, so its own `exp` is used rather than an upper bound.
          env.accessTokenRevocationService.revoke.calls ==
            List((testClient, accessToken1, userId1.toString, payload.expiresAt)),
        )).provide(env.layer)
      },
      test("fail with InvalidClient when client authentication fails") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now)

          _ <- env.oauthClientService.verifySecret.succeedsWith(None)

          service <- ZIO.service[RevocationService]
          result <- service.revokeAccessToken(payload, credentials).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
      },
      test("fail with InvalidClient when token audience doesn't match client") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now) // has clientId1 in audience
          otherClient = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(otherClient))

          service <- ZIO.service[RevocationService]
          result <- service.revokeAccessToken(payload, credentials).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
      },
    ),
    suite("AccessTokenRevocationService")(
      test("pushes a token-scoped event, not a session-scoped one, to the client's back channel") {
        val dispatcher = stub[BackChannelDispatcher]
        val backChannelUri = URL.decode("https://rp.example/backchannel").toOption.get
        val client = testClient.copy(backChannelLogoutUri = Some(backChannelUri))
        val service = AccessTokenRevocationService.Impl(dispatcher)
        for
          now <- Clock.instant
          _ <- dispatcher.dispatch.succeedsWith(())
          // Awaited, not forked: one client, one call, so there is nothing to fan out.
          _ <- service.revoke(client, accessToken1, userId1.toString, now.plusSeconds(300))
          calls = dispatcher.dispatch.calls
        yield assertTrue(
          calls.map((audience, uri, subject, _) => (audience.toList, uri, subject)) == List((List(client.id), backChannelUri, userId1.toString)),
          // No `sid`: this must not end the SSO session the token belongs to.
          calls.head._4 == Json.Obj(
            "revoked_jti" -> Json.Str(accessToken1.encoded),
            "revoked_exp" -> Json.Num(now.plusSeconds(300).getEpochSecond),
            "events" -> Json.Obj("versola:event:access-token-revocation" -> Json.Obj()),
          ),
        )
      },
      test("pushes nothing when the client registered no back-channel URI") {
        val dispatcher = stub[BackChannelDispatcher]
        val service = AccessTokenRevocationService.Impl(dispatcher)
        for
          now <- Clock.instant
          _ <- service.revoke(testClient, accessToken1, userId1.toString, now.plusSeconds(300))
        yield assertTrue(dispatcher.dispatch.calls.isEmpty)
      },
      test("still succeeds when the push fails, since the revocation itself is already done") {
        val dispatcher = stub[BackChannelDispatcher]
        val client = testClient.copy(backChannelLogoutUri = Some(URL.decode("https://rp.example/backchannel").toOption.get))
        val service = AccessTokenRevocationService.Impl(dispatcher)
        for
          now <- Clock.instant
          _ <- dispatcher.dispatch.failsWith(RuntimeException("connection refused"))
          result <- service.revoke(client, accessToken1, userId1.toString, now.plusSeconds(300)).either
        yield assertTrue(result == Right(()))
      },
    ),
  )