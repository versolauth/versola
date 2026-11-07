package versola.oauth.revoke

import org.scalamock.stubs.ZIOStubs
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents, ClientAuthFailures}
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ApplicationType
import versola.oauth.client.model.{
  AuthMethod,
  AuthMethodRef,
  ClientId,
  ClientIdWithSecret,
  MutualTlsAuth,
  MutualTlsSubjectType,
  OAuthClientRecord,
  ResourceUri,
  ScopeToken,
  TenantId,
}
import versola.oauth.clientauth.{ClientAssertionService, ClientAuthentication}
import versola.oauth.logout.BackChannelOutbox
import versola.oauth.model.{AccessToken, AccessTokenPayload, RefreshToken}
import versola.oauth.revocation.{AuthRevocationKeys, AuthRevocationRepository, RevocationCause, RevocationTerms}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId, RefreshTokenRecord, SessionId}
import versola.user.model.UserId
import versola.util.Base64
import versola.util.audit.AuditEvents
import versola.util.audit.AuditOutcome
import versola.util.revocation.{Revocation, RevocationCache, RevocationKey}
import versola.util.{CoreConfig, MAC, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object RevocationServiceSpec extends UnitSpecBase:

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
    themeOverrides = None,
    applicationType = ApplicationType.web,
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
    confirmation = None,
    familyId = None,
  )

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
    val clientAuthentication =
      ClientAuthentication.Impl(oauthClientService, stub[ClientAssertionService], TestEnvConfig.coreConfig, ClientAuthFailures.noop)
    val audit = CapturingAuditEvents.shared()
    val tokenRepository = stub[SessionRepository]
    val accessTokenRevocationService = stub[AccessTokenRevocationService]
    val securityService = stub[SecurityService]
    val config = TestEnvConfig.coreConfig

    val layer = ZLayer.succeed(clientAuthentication) ++
      ZLayer.succeed(tokenRepository) ++
      ZLayer.succeed(accessTokenRevocationService) ++
      ZLayer.succeed(securityService) ++
      ZLayer.succeed(config) ++
      ZLayer.succeed[AuditEvents](audit) >>> RevocationService.live

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
          _ <- env.tokenRepository.deleteByFamily.succeedsWith(())
          _ <- env.accessTokenRevocationService.announceFamily.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials, None)
          drafts <- env.audit.recorded
        yield assertTrue(
          result == (),
          // The whole family goes, recorded with the client's own TTL as the bound, in the one
          // repository call that expires it; not just the row that was presented.
          env.tokenRepository.deleteByFamily.calls ==
            List((
              familyId1,
              RevocationTerms(
                cause = RevocationCause.Revoke,
                ttl = testClient.accessTokenTtl,
                tenantId = Some(testClient.tenantId),
                ttlByClient = Map.empty,
              ),
            )),
          // No access token was presented and none is recorded against the chain, so the push
          // names the family and bounds it by the client's own TTL from now.
          env.accessTokenRevocationService.announceFamily.calls ==
            List((testClient, familyId1, userId1.toString, now.plus(testClient.accessTokenTtl))),
          // RFC 7009: the client revoked it, and the log keeps who it was about and the prefix of the token, not the token.
          drafts.map(_.kind) == List(AuthAuditKinds.TokenRevoked),
          drafts.head.outcome == AuditOutcome.success,
          drafts.head.actor == Some((AuthActors.client, Some(clientId1: String))),
          drafts.head.userId == Some(userId1),
          drafts.head.details == AuthAuditDetails.TokenRevoked(
            "refresh_token",
            None,
            Some(Base64.urlEncode(refreshToken1).take(9)),
            "rfc7009",
            count = None,
          ),
        )).provide(env.layer)
      },
      test("fail with InvalidClient when client authentication fails") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(None)

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials, None).either
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
          result <- service.revokeRefreshToken(refreshToken1, credentials, None).either
          drafts <- env.audit.recorded
        yield assertTrue(
          result == Left(RevocationError.InvalidClient),
          drafts.isEmpty,
          // Another client's family is neither expired nor recorded as revoked.
          env.tokenRepository.deleteByFamily.calls.isEmpty,
        )).provide(env.layer)
      },
      test("succeed when token not found (idempotent)") {
        val env = Env()
        (for
          credentials = ClientIdWithSecret(clientId1, Some(clientSecret1))

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient))
          _ <- env.securityService.mac.succeedsWith(refreshTokenMac1)
          _ <- env.tokenRepository.findToken.succeedsWith(None)
          _ <- env.tokenRepository.deleteByFamily.succeedsWith(())

          service <- ZIO.service[RevocationService]
          result <- service.revokeRefreshToken(refreshToken1, credentials, None)
          drafts <- env.audit.recorded
        // nothing was revoked, so nothing is said to have been, or recorded
        yield assertTrue(result == (), drafts.isEmpty, env.tokenRepository.deleteByFamily.calls.isEmpty)).provide(env.layer)
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
          result <- service.revokeAccessToken(payload, credentials, None)
          drafts <- env.audit.recorded
        yield assertTrue(
          result == (),
          // The token was parsed here, so the service has its own `exp` to record rather than an
          // upper bound.
          env.accessTokenRevocationService.revoke.calls == List((testClient, payload)),
          drafts.map(_.kind) == List(AuthAuditKinds.TokenRevoked),
          drafts.head.userId == Some(userId1.asInstanceOf[java.util.UUID]),
          drafts.head.details == AuthAuditDetails.TokenRevoked("access_token", Some(Base64.urlEncode(accessToken1)), None, "rfc7009", count = None),
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
          result <- service.revokeAccessToken(payload, credentials, None).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
      },
      test("fail with InvalidClient when the token's client id merely contains the caller's") {
        val env = Env()
        val app = ClientId("app")
        val webApp = ClientId("web-app")
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(app, Some(clientSecret1))
          // A token of `web-app`, presented by `app`: a substring of its id, and not its owner.
          payload = accessTokenPayload(now).copy(clientId = webApp)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(testClient.copy(id = app)))

          service <- ZIO.service[RevocationService]
          result <- service.revokeAccessToken(payload, credentials, None).either
          drafts <- env.audit.recorded
        yield assertTrue(
          result == Left(RevocationError.InvalidClient),
          // Nothing was revoked, recorded or said to have been.
          env.accessTokenRevocationService.revoke.calls.isEmpty,
          drafts.isEmpty,
        )).provide(env.layer)
      },
      test("revokes a token of a client whose id contains another's, when the caller is that client") {
        val env = Env()
        val webApp = ClientId("web-app")
        (for
          now <- Clock.instant
          credentials = ClientIdWithSecret(webApp, Some(clientSecret1))
          payload = accessTokenPayload(now).copy(clientId = webApp)
          client = testClient.copy(id = webApp)

          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(client))
          _ <- env.accessTokenRevocationService.revoke.succeedsWith(())

          service <- ZIO.service[RevocationService]
          _ <- service.revokeAccessToken(payload, credentials, None)
        yield assertTrue(env.accessTokenRevocationService.revoke.calls == List((client, payload)))).provide(env.layer)
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
          result <- service.revokeAccessToken(payload, credentials, None).either
        yield assertTrue(result == Left(RevocationError.InvalidClient))).provide(env.layer)
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
          _ <- env.tokenRepository.deleteByFamily.succeedsWith(())
          _ <- env.accessTokenRevocationService.announceFamily.succeedsWith(())

          // No secret: the certificate is the credential.
          credentials = ClientIdWithSecret(clientId1, None)

          result <- ZIO.serviceWithZIO[RevocationService](
            _.revokeRefreshToken(refreshToken1, credentials, Some(TestEnvConfig.clientCertificate)),
          )
        yield assertTrue(
          result == (),
          env.tokenRepository.deleteByFamily.calls.nonEmpty,
          env.oauthClientService.verifySecret.calls.isEmpty,
        )).provide(env.layer)
      },
      test("fails with InvalidClient when the certificate's subject is not the registered one") {
        val env = Env()
        (for
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))

          credentials = ClientIdWithSecret(clientId1, None)

          result <- ZIO.serviceWithZIO[RevocationService](
            _.revokeRefreshToken(refreshToken1, credentials, Some(TestEnvConfig.otherClientCertificate)),
          ).either
        yield assertTrue(
          result == Left(RevocationError.InvalidClient),
          // Nothing was revoked on the strength of a certificate that authenticates nobody.
          env.tokenRepository.deleteByFamily.calls.isEmpty,
        )).provide(env.layer)
      },
      test("fails with InvalidClient when a certificate-authenticated client presents none") {
        val env = Env()
        (for
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))

          credentials = ClientIdWithSecret(clientId1, None)

          result <- ZIO.serviceWithZIO[RevocationService](
            _.revokeRefreshToken(refreshToken1, credentials, None),
          ).either
        yield assertTrue(
          result == Left(RevocationError.InvalidClient),
          // The registered method is the certificate, so no secret can stand in for it.
          env.oauthClientService.verifySecret.calls.isEmpty,
        )).provide(env.layer)
      },
      test("authenticates the credentials-only check RFC 7009 §2.1 requires by certificate") {
        val env = Env()
        (for
          _ <- env.oauthClientService.find.succeedsWith(Some(mtlsClient))

          credentials = ClientIdWithSecret(clientId1, None)

          // The path a token of an unrecognised shape takes: no token to check, only the
          // client to authenticate.
          result <- ZIO.serviceWithZIO[RevocationService](
            _.authenticateClient(credentials, Some(TestEnvConfig.clientCertificate)),
          )
        yield assertTrue(result.id == clientId1)).provide(env.layer)
      },
    ),
    suite("AccessTokenRevocationService")(
      /** Everything a service under test is handed, and what it did with it. */
      suite("revoke")(
        test("records the token until its own exp, and pushes a token-scoped event through the outbox") {
          val repository = stub[AuthRevocationRepository]
          val outbox = stub[BackChannelOutbox]
          val backChannelUri = URL.decode("https://rp.example/backchannel").toOption.get
          val client = testClient.copy(backChannelLogoutUri = Some(backChannelUri))
          val service = AccessTokenRevocationService.Impl(outbox, repository, stub[RevocationCache])
          for
            now <- Clock.instant
            payload = accessTokenPayload(now)
            _ <- repository.revoke.succeedsWith(())
            _ <- outbox.submit.succeedsWith(())
            _ <- service.revoke(client, payload)
            deliveries = outbox.submit.calls
          yield assertTrue(
            repository.revoke.calls == List(
              (
                Revocation(RevocationKey.Jti(accessToken1.encoded), payload.expiresAt, issuedBefore = None),
                RevocationCause.Revoke,
                // the feed serves a revoked token to the edges of its client's tenant
                Some(client.tenantId),
              ),
            ),
            deliveries.map(d => (d.audience.toList, d.tenantId, d.uri, d.subject)) ==
              List((List(client.id), client.tenantId, backChannelUri, userId1.toString)),
            // No `sid`: this must not end the SSO session the token belongs to.
            deliveries.head.customClaims == Json.Obj(
              "revoked_jti" -> Json.Arr(Json.Str(accessToken1.encoded)),
              "revoked_exp" -> Json.Num(payload.expiresAt.getEpochSecond),
              "events" -> Json.Obj("versola:event:access-token-revocation" -> Json.Obj()),
            ),
          )
        },
        test("records the token even when the client registered no back-channel URI, and pushes nothing") {
          val repository = stub[AuthRevocationRepository]
          val outbox = stub[BackChannelOutbox]
          val service = AccessTokenRevocationService.Impl(outbox, repository, stub[RevocationCache])
          for
            now <- Clock.instant
            _ <- repository.revoke.succeedsWith(())
            _ <- service.revoke(testClient, accessTokenPayload(now))
          yield assertTrue(
            // The record is what refuses the token at `/userinfo` and `/introspect`, whether or
            // not anyone can be told.
            repository.revoke.calls.size == 1,
            outbox.submit.calls.isEmpty,
          )
        },
        test("pushes nothing and fails when the record could not be written") {
          val repository = stub[AuthRevocationRepository]
          val outbox = stub[BackChannelOutbox]
          val client = testClient.copy(backChannelLogoutUri = Some(URL.decode("https://rp.example/backchannel").toOption.get))
          val service = AccessTokenRevocationService.Impl(outbox, repository, stub[RevocationCache])
          for
            now <- Clock.instant
            _ <- repository.revoke.failsWith(RuntimeException("connection refused"))
            result <- service.revoke(client, accessTokenPayload(now)).either
          yield assertTrue(
            // A revocation that was not recorded is not one the caller may be told succeeded, and
            // not one a client's back channel is told about either.
            result.isLeft,
            outbox.submit.calls.isEmpty,
          )
        },
      ),
      suite("announceFamily")(
        test("names the family, not the tokens, when a leaked chain is revoked") {
          val outbox = stub[BackChannelOutbox]
          val client = testClient.copy(backChannelLogoutUri = Some(URL.decode("https://rp.example/backchannel").toOption.get))
          val service = AccessTokenRevocationService.Impl(outbox, stub[AuthRevocationRepository], stub[RevocationCache])
          for
            now <- Clock.instant
            _ <- outbox.submit.succeedsWith(())
            _ <- service.announceFamily(client, familyId1, userId1.toString, now.plusSeconds(300))
          yield assertTrue(
            // Same event as a jti-scoped revocation, under a different claim: what changes is
            // which tokens it reaches, not what the recipient does about it.
            outbox.submit.calls.head.customClaims == Json.Obj(
              "revoked_fam" -> Json.Arr(Json.Str(familyId1)),
              "revoked_exp" -> Json.Num(now.plusSeconds(300).getEpochSecond),
              "events" -> Json.Obj("versola:event:access-token-revocation" -> Json.Obj()),
            ),
          )
        },
        test("writes no record of its own, the repository method that ended the family did") {
          val repository = stub[AuthRevocationRepository]
          val outbox = stub[BackChannelOutbox]
          val service = AccessTokenRevocationService.Impl(outbox, repository, stub[RevocationCache])
          for
            now <- Clock.instant
            _ <- service.announceFamily(testClient, familyId1, userId1.toString, now.plusSeconds(300))
          yield assertTrue(repository.revoke.calls.isEmpty, outbox.submit.calls.isEmpty)
        },
      ),
      suite("isRevoked")(
        test("answers from the replica's list, with no query") {
          val repository = stub[AuthRevocationRepository]
          val cache = stub[RevocationCache]
          val service = AccessTokenRevocationService.Impl(stub[BackChannelOutbox], repository, cache)
          for
            now <- Clock.instant
            payload = accessTokenPayload(now)
            _ <- cache.isRevoked.succeedsWith(true)
            revoked <- service.isRevoked(payload)
          yield assertTrue(
            revoked,
            cache.isRevoked.calls == List((AuthRevocationKeys.of(payload), payload.issuedAt)),
            repository.activeSince.calls.isEmpty,
          )
        },
      ),
    ),
  )
