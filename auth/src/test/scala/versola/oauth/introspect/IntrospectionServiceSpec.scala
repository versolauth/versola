package versola.oauth.introspect

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.model.{AccessToken, AccessTokenPayload, RefreshToken}
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.session.model.{RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.http.ClientIdWithSecret
import versola.util.{CoreConfig, MAC, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID

object IntrospectionServiceSpec extends UnitSpecBase:

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

  def accessTokenPayload(now: Instant, audience: Vector[ClientId] = Vector(clientId1)) = AccessTokenPayload(
    subject = userId1.toString,
    clientId = clientId1,
    scope = scope1,
    requestedClaims = None,
    uiLocales = None,
    expiresAt = now.plusSeconds(3600),
    issuedAt = now,
    notBefore = Some(now),
    audience = audience,
    issuer = "https://auth.example.com",
    id = accessToken1,
  )

  class Env:
    val oauthClientService = stub[OAuthClientService]
    val tokenRepository = stub[RefreshTokenRepository]
    val securityService = stub[SecurityService]
    val config = TestEnvConfig.coreConfig

    val layer = ZLayer.succeed(oauthClientService) ++
      ZLayer.succeed(tokenRepository) ++
      ZLayer.succeed(securityService) ++
      ZLayer.succeed(config) >>> IntrospectionService.live

  val spec = suite("IntrospectionService")(
    suite("introspectAccessToken")(
      test("successfully introspect active access token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials)
        yield assertTrue(
          result.active == true,
          result.clientId == Some(clientId1),
          result.scope == Some("read write"),
          result.sub == Some(userId1.toString),
          result.tokenType == Some("Bearer"),
          result.exp == Some(now.plusSeconds(3600).getEpochSecond),
          result.iat == Some(now.getEpochSecond),
          result.nbf == Some(now.getEpochSecond),
          result.aud == Some(Vector(clientId1)),
          result.iss == Some("https://auth.example.com"),
        )).provide(env.layer)
      },
      test("fail with Unauthenticated when client authentication fails") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now)

          _ <- env.oauthClientService.verifySecret.succeedsWith(None)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
      test("fail with Unauthenticated when client not in audience") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = Vector(clientId1)) // clientId2 not in audience
          otherClient = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(otherClient))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
    ),
    suite("introspectRefreshToken")(
      test("successfully introspect active refresh token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          record = tokenRecord(now)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.find.succeedsWith(Some(record))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials)
        yield assertTrue(
          result.active == true,
          result.clientId == Some(clientId1),
          result.scope == Some("read write"),
          result.sub == Some(userId1.toString),
          result.tokenType == Some("Bearer"),
          result.exp == Some(now.plusSeconds(3600).getEpochSecond),
          result.iat == Some(now.getEpochSecond),
          result.iss == Some(env.config.jwt.issuer),
          result.aud == Some(Vector(clientId1)),
        )).provide(env.layer)
      },
      test("return inactive when token not found") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.find.succeedsWith(None)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials)
        yield assertTrue(
          result.active == false,
          result == IntrospectionResponse.Inactive,
        )).provide(env.layer)
      },
      test("fail with Unauthenticated when client authentication fails") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(None)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
      test("fail with Unauthenticated when token belongs to different client") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          record = tokenRecord(now) // belongs to clientId1
          otherClient = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(otherClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.find.succeedsWith(Some(record))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
    ),
  )

