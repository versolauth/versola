package versola.oauth.introspect

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.{OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{AuthMethod, AuthMethodRef, AuthorizationDetail, ClientId, ClientIdWithSecret, MutualTlsAuth, MutualTlsSubjectType, OAuthClientRecord, ResourceId, ResourceRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.clientauth.{ClientAssertionService, ClientAuthentication}
import versola.oauth.model.{AccessToken, AccessTokenPayload, Cnf, RefreshToken}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId, RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object IntrospectionServiceSpec extends UnitSpecBase:

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val sessionId1 = MAC(Array.fill(32)(1.toByte))
  val publicSessionId1 = PublicSessionId("public-session-1")
  val familyId1 = RefreshTokenFamilyId("family-1")
  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  
  val refreshToken1 = RefreshToken(Array.fill(32)(10.toByte))
  val refreshTokenMac1 = MAC(Array.fill(32)(11.toByte))
  
  val accessToken1 = AccessToken(Array.fill(32)(20.toByte))
  
  val clientSecret1 = Secret(Array.fill(32)(30.toByte))
  
  val testClient = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = Set("https://example.com/callback"),
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
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.client_secret,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
  )

  def tokenRecord(now: Instant) = RefreshTokenRecord(
    familyId = familyId1,
    sessionId = sessionId1,
    publicSessionId = publicSessionId1,
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
    amr = Set(AuthMethodRef.pwd),
    authTime = now,
    acr = None,
    cnf = None,
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
    authorizationDetails = None,
    sessionId = None,
    confirmation = None,
  )

  val paymentDetail = AuthorizationDetail.parse(
    """{"type":"payment_initiation","instructedAmount":{"currency":"EUR","amount":"1.00"}}"""
      .fromJson[Json].toOption.get,
  ).toOption.get

  /** RFC 8705 §2.1: authenticates by certificate, so it holds no secret. */
  val mtlsClient = testClient.copy(
    secret = None,
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, TestEnvConfig.clientCertificateDnsName)),
  )

  class Env:
    val oauthClientService = stub[OAuthConfigurationService]
    // Authentication looks the client up first to see whether it registered an mTLS
    // subject; an unregistered one falls through to the secret it presented.
    oauthClientService.find.returnsWith(ZIO.none)
    val clientAuthentication = ClientAuthentication.Impl(oauthClientService, stub[ClientAssertionService], TestEnvConfig.coreConfig)
    val tokenRepository = stub[SessionRepository]
    val securityService = stub[SecurityService]
    val config = TestEnvConfig.coreConfig

    val layer = ZLayer.succeed(oauthClientService) ++
      ZLayer.succeed(clientAuthentication) ++
      ZLayer.succeed(tokenRepository) ++
      ZLayer.succeed(securityService) ++
      ZLayer.succeed(config) >>> IntrospectionService.live

  val spec = suite("IntrospectionService")(
    suite("introspectAccessToken")(
      test("returns the authorization details carried by the access token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          payload = accessTokenPayload(now, audience = Vector(ResourceUri("https://api.example.com")))
            .copy(authorizationDetails = Some(List(paymentDetail)))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(List(ResourceRecord(
            ResourceId("api"),
            testClient.tenantId,
            ResourceUri("https://api.example.com"),
            List(testClient.id),
            internal = false,
          )))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials, None)
        yield assertTrue(
          result.authorizationDetails == Some(Json.Arr(paymentDetail.value)),
        )).provide(env.layer)
      },
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
          result <- service.introspectAccessToken(payload, credentials, None)
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
          result.cnf == None,
        )).provide(env.layer)
      },
      test("introspects a DPoP-bound access token as such, echoing its cnf.jkt") {
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
            .copy(confirmation = Some(Cnf.dpop("test-key-thumbprint")))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(List(resource))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials, None)
        yield assertTrue(
          result.active == true,
          result.tokenType == Some("DPoP"),
          result.cnf == Some(Json.Obj("jkt" -> Json.Str("test-key-thumbprint"))),
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
          result <- service.introspectAccessToken(payload, credentials, None).either
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
          result <- service.introspectAccessToken(payload, credentials, None).either
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
          result <- service.introspectAccessToken(payload, credentials, None)
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
          result <- service.introspectAccessToken(payload, credentials, None)
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
          result <- service.introspectAccessToken(payload, credentials, None)
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
          result <- service.introspectRefreshToken(refreshToken1, credentials, None)
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
          result.cnf == None,
        )).provide(env.layer)
      },
      test("introspects a DPoP-bound refresh token as such, echoing its cnf.jkt") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          record = tokenRecord(now).copy(cnf = Some(Cnf.dpop("test-key-thumbprint")))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(Some(record))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials, None)
        yield assertTrue(
          result.active == true,
          result.tokenType == Some("DPoP"),
          result.cnf == Some(Json.Obj("jkt" -> Json.Str("test-key-thumbprint"))),
        )).provide(env.layer)
      },
      test("returns the authorization details granted by the refresh token") {
        val env = Env()
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))
          record = tokenRecord(now).copy(authorizationDetails = Some(List(paymentDetail)))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(Some(record))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials, None)
        yield assertTrue(
          result.authorizationDetails == Some(Json.Arr(paymentDetail.value)),
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
          result <- service.introspectRefreshToken(refreshToken1, credentials, None)
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
          result <- service.introspectRefreshToken(refreshToken1, credentials, None).either
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
          result <- service.introspectRefreshToken(refreshToken1, credentials, None).either
        yield assertTrue(result.isLeft)).provide(env.layer)
      },
    ),
    suite("mutual TLS")(
      test("authenticates a client by its certificate, with no secret presented") {
        val env = Env()
        (for
          now <- Clock.instant
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(Some(tokenRecord(now)))

          // No secret: the certificate is the credential.
          credentials = ClientIdWithSecret(clientId1, None)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials, Some(TestEnvConfig.clientCertificate))
        yield assertTrue(
          result.active,
          env.oauthClientService.verifySecret.calls.isEmpty,
        )).provide(env.layer)
      },
      test("authenticates an access token introspection by certificate too") {
        val env = Env()
        val resource = ResourceRecord(
          ResourceId("api"),
          testClient.tenantId,
          ResourceUri("https://api.example.com"),
          List(testClient.id),
          internal = false,
        )
        (for
          now <- Clock.instant
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))
          _ <- env.oauthClientService.getResourcesForClient.succeedsWith(List(resource))

          credentials = ClientIdWithSecret(clientId1, None)
          payload = accessTokenPayload(now, audience = Vector(resource.resource))

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectAccessToken(payload, credentials, Some(TestEnvConfig.clientCertificate))
        yield assertTrue(result.active)).provide(env.layer)
      },
      test("fails with InvalidClient when the certificate's subject is not the registered one") {
        val env = Env()
        (for
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))

          credentials = ClientIdWithSecret(clientId1, None)

          service <- ZIO.service[IntrospectionService]
          result <- service
            .introspectRefreshToken(refreshToken1, credentials, Some(TestEnvConfig.otherClientCertificate))
            .either
        yield assertTrue(result == Left(IntrospectionError.InvalidClient))).provide(env.layer)
      },
      test("fails with InvalidClient when a certificate-authenticated client presents none") {
        val env = Env()
        (for
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))

          credentials = ClientIdWithSecret(clientId1, None)

          service <- ZIO.service[IntrospectionService]
          result <- service.introspectRefreshToken(refreshToken1, credentials, None).either
        yield assertTrue(
          result == Left(IntrospectionError.InvalidClient),
          // The registered method is the certificate, so no secret can stand in for it.
          env.oauthClientService.verifySecret.calls.isEmpty,
        )).provide(env.layer)
      },
      test("still refuses a client that presents nothing but its id") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, None)

          service <- ZIO.service[IntrospectionService]
          // RFC 7662: a public client's id is not a secret, and knowing one must not be enough
          // to read the tokens issued for its audience.
          result <- service.introspectRefreshToken(refreshToken1, credentials, None).either
        yield assertTrue(
          result == Left(IntrospectionError.InvalidClient),
          env.oauthClientService.verifySecret.calls.isEmpty,
        )).provide(env.layer)
      },
    ),
  )
