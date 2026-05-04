package versola.oauth.revoke

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ClientIdWithSecret, OAuthClientRecord, ScopeToken}
import versola.oauth.model.{AccessToken, AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.session.model.{RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID

object RevocationServiceSpec extends UnitSpecBase:

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  
  val refreshToken1 = RefreshToken(Array.fill(32)(10.toByte))
  val refreshTokenMac1 = MAC(Array.fill(32)(11.toByte))
  
  val accessToken1 = AccessToken(Array.fill(32)(20.toByte))
  
  val clientSecret1 = Secret(Array.fill(32)(30.toByte))
  
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

  def tokenRecord(now: Instant) = RefreshTokenRecord(
    sessionId = sessionId1,
    accessToken = accessToken1,
    userId = userId1,
    clientId = clientId1,
    externalAudience = List.empty,
    scope = scope1,
    issuedAt = now,
    expiresAt = now.plusSeconds(3600),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    previousRefreshToken = None,
  )

  def accessTokenPayload(now: Instant) = AccessTokenPayload(
    subject = userId1.toString,
    clientId = clientId1,
    scope = scope1,
    requestedClaims = None,
    uiLocales = None,
    expiresAt = now.plusSeconds(3600),
    issuedAt = now,
    notBefore = None,
    audience = Vector(clientId1),
    issuer = "https://auth.example.com",
    id = accessToken1,
  )

  class Env:
    val oauthClientService = stub[OAuthConfigurationService]
    val tokenRepository = stub[RefreshTokenRepository]
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
          _ <- env.tokenRepository.find.succeedsWith(Some(tokenRecord(now)))
          _ <- env.tokenRepository.delete.succeedsWith(())
          _ <- env.accessTokenRevocationService.revoke.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials)
        yield assertTrue(result == ())).provide(env.layer)
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
          _ <- env.tokenRepository.find.succeedsWith(Some(tokenRecord(now)))

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
          _ <- env.tokenRepository.find.succeedsWith(None)
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
        yield assertTrue(result == ())).provide(env.layer)
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
  )

