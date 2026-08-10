package versola.oauth.introspect

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.{OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{AuthMethodRef, ClientId, ClientIdWithSecret, OAuthClientRecord, ResourceId, ResourceRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.model.{AccessToken, AccessTokenPayload, RefreshToken}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{PublicSessionId, RefreshTokenRecord, SessionId}
import versola.user.model.UserId
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
  val publicSessionId1 = PublicSessionId("public-session-1")
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  
  val refreshToken1 = RefreshToken(Array.fill(32)(10.toByte))
  val refreshTokenMac1 = MAC(Array.fill(32)(11.toByte))
  
  val accessToken1 = AccessToken(Array.fill(32)(20.toByte))
  
  val clientSecret1 = Secret(Array.fill(32)(30.toByte))
  
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

  def tokenRecord(now: Instant) = RefreshTokenRecord(
    sessionId = sessionId1,
    publicSessionId = publicSessionId1,
    accessToken = accessToken1,
    userId = userId1,
    clientId = clientId1,
    audience = List.empty,
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

  def accessTokenPayload(now: Instant, audience: Vector[ResourceUri] = Vector.empty) = AccessTokenPayload(
    subject = userId1.toString,
    clientId = clientId1,
    scope = scope1,
    requestedClaims = None,
    expiresAt = now.plusSeconds(3600),
    issuedAt = now,
    notBefore = Some(now),
    audience = audience,
    issuer = "https://auth.example.com",
    id = accessToken1,
  )

  class Env:
    val oauthClientService = stub[OAuthConfigurationService]
    val tokenRepository = stub[SessionRepository]
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
        val publicResource = ResourceUri("https://api.example.com")
        val resource = ResourceRecord(
          ResourceId("api"),
          testClient.tenantId,
          publicResource,
          List(testClient.id),
          internal = false,
        )
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = Vector(publicResource))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(List(resource))

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
          result.aud == Some(Vector(publicResource)),
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
      test("returns inactive when requester has no access to the token audience") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = Vector(ResourceUri("https://api.example.com")))
          otherClient = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(otherClient))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(Nil)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials).either
        yield assertTrue(result == Left(IntrospectionError.Unauthenticated))).provide(env.layer)
      },
      test("expands the edge audience to issuer resources before intersecting with requester resources") {
        val env = Env()
        val issuerInternalResource = ResourceRecord(
          ResourceId("internal-api"),
          testClient.tenantId,
          ResourceUri("https://internal.example.com"),
          List(clientId1),
          internal = true,
        )
        val issuerOnlyInternalResource = ResourceRecord(
          ResourceId("internal-admin"),
          testClient.tenantId,
          ResourceUri("https://admin.example.com"),
          List(clientId1),
          internal = true,
        )
        val expectedAudience = Vector(ResourceUri("resource://internal-api"))
        val requester = testClient.copy(id = clientId2)
        val requesterResources = List(
          issuerInternalResource.copy(audience = List(clientId2)),
        )
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = Vector(ResourceUri("resource://edge")))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(requester))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(
            List(issuerInternalResource, issuerOnlyInternalResource),
          )
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(requesterResources)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials)
        yield assertTrue(
          result.active,
          result.aud == Some(expectedAudience),
        )).provide(env.layer)
      },
      test("returns the intersection of internal and public audiences") {
        val env = Env()
        val publicResource = ResourceUri("https://api.example.com")
        val internalAudience = ResourceUri("resource://internal-api")
        val publicResourceRecord = ResourceRecord(
          ResourceId("api"),
          testClient.tenantId,
          publicResource,
          List(clientId2),
          internal = false,
        )
        val internalResourceRecord = ResourceRecord(
          ResourceId("internal-api"),
          testClient.tenantId,
          ResourceUri("https://internal.example.com"),
          List(clientId2),
          internal = true,
        )
        val expectedAudience = Vector(publicResource, internalAudience)
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = expectedAudience)
          requester = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(requester))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(
            List(publicResourceRecord, internalResourceRecord),
          )

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials)
        yield assertTrue(
          result.active,
          result.aud == Some(expectedAudience),
        )).provide(env.layer)
      },
      test("keeps a matching public audience when combined with edge") {
        val env = Env()
        val publicResource = ResourceUri("https://api.example.com")
        val internalResource = ResourceRecord(
          ResourceId("internal-api"),
          testClient.tenantId,
          ResourceUri("https://internal.example.com"),
          List(clientId2),
          internal = true,
        )
        val publicResourceRecord = ResourceRecord(
          ResourceId("api"),
          testClient.tenantId,
          publicResource,
          List(clientId2),
          internal = false,
        )
        val tokenAudience = Vector(publicResource, ResourceResolver.EdgeResource)
        val expectedAudience = Vector(publicResource, ResourceUri("resource://internal-api"))
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId2, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = tokenAudience)
          requester = testClient.copy(id = clientId2)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(requester))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(
            List(publicResourceRecord, internalResource),
          )

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials)
        yield assertTrue(
          result.active,
          result.aud == Some(expectedAudience),
          !result.aud.exists(_.contains(ResourceResolver.EdgeResource)),
        )).provide(env.layer)
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
          _ <- env.tokenRepository.findToken.succeedsWith(Some(record))

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
          result.aud == Some(Vector.empty),
        )).provide(env.layer)
      },
      test("return inactive when token not found") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(None)

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
          _ <- env.tokenRepository.findToken.succeedsWith(Some(record))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
    ),
  )