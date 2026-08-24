package versola.edge

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.ZIOStubs
import versola.edge.EdgeServiceSpec.Fixtures
import versola.edge.login.{LoginRecord, LoginRepository}
import versola.edge.model.*
import versola.edge.revocation.{Revocation, RevocationKey, TokenRevocationService}
import versola.edge.session.EdgeSessionRecord
import versola.util.cel.CelEvaluator
import versola.util.{EnvName, JWT, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.{Collections, Date, UUID}
import javax.crypto.spec.SecretKeySpec

object EdgeServiceSpec extends ZIOSpecDefault, ZIOStubs:

  object Fixtures:
    val presetId = PresetId("preset-default")
    val otherPresetId = PresetId("preset-missing")
    val clientId = ClientId("web-app")
    val missingClientId = ClientId("ghost")
    val authorizeUrl = URL.decode("https://idp.example/authorize?client_id=web-app").toOption.get
    val redirectUri = RedirectUri("https://app.example/complete")
    val postLoginUri = RedirectUri("https://app.example/home")

    val preset = AuthorizationPreset(
      id = presetId,
      clientId = clientId,
      description = "default",
      redirectUri = redirectUri,
      postLoginRedirectUri = postLoginUri,
      postLogoutRedirectUri = None,
      scope = Set("openid"),
      responseType = "code",
      uiLocales = None,
      customParameters = Map.empty,
      cookieDomain = Some("app.example"),
      cookiePath = Some("/"),
    )

    val orphanPreset = preset.copy(id = otherPresetId, clientId = missingClientId)

    val client = OAuthClient(id = clientId, secret = Secret(Array.fill(48)(1.toByte)), permissions = Set.empty, accessTokenTtl = 15.minutes)

    val codeVerifierBytes = Array.fill[Byte](32)(7)
    val stateBytes = Array.fill[Byte](16)(9)

    val state = State.fromBytes(stateBytes)

    val tokens = TokenResponse(
      accessToken = AccessToken("header.payload.sig"),
      tokenType = "Bearer",
      expiresIn = 3600L,
      refreshToken = None,
      refreshTokenExpiresIn = None,
      scope = Some("openid"),
      idToken = None,
    )

  class Env:
    val secureRandom = stub[SecureRandom]
    val loginRepository = stub[LoginRepository]
    val ssoClient = stub[SSOClient]
    val jwksService = stub[JwksService]
    val sessionRepository = stub[session.EdgeSessionRepository]
    val revocationService = stub[TokenRevocationService]
    val permissionService = stub[PermissionService]

    val presetCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset])))
    val clientCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ClientId, OAuthClient])))
    val resourceCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ResourceId, Resource])))

    val clientService = OAuthClientService.Impl(presetCache, clientCache)
    val resourceService = ResourceService.Impl(resourceCache)
    val celEvaluator = CelEvaluator.Impl(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty)))

    private val keyPair =
      val gen = KeyPairGenerator.getInstance("RSA").nn
      gen.initialize(2048)
      gen.generateKeyPair().nn

    val edgeConfig = EdgeConfig(
      id = EdgeId("edge-1"),
      keyId = "kid-1",
      privateKey = keyPair.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(
        url = URL.decode("https://central.example").toOption.get,
      ),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
      configurationCacheRefreshInterval = 5.minutes,
    )

    val publicKeys: JWT.PublicKeys =
      val rsaKey = RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(edgeConfig.keyId).build()
      JWT.PublicKeys(JWKSet(rsaKey))

    def signToken(jti: String = UUID.randomUUID().toString, ttlSeconds: Long = 600, sid: String = "sso-session-1"): Task[AccessToken] =
      Clock.instant.flatMap { now =>
        ZIO.attemptBlocking {
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(edgeConfig.keyId)
            .`type`(JOSEObjectType("at+jwt"))
            .build()
          val claims = JWTClaimsSet.Builder()
            .issuer("test").subject("user-1")
            .audience(Collections.singletonList("test"))
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("client_id", "user-1-client")
            .claim("sid", sid)
            .build()
          val jwt = SignedJWT(header, claims)
          jwt.sign(RSASSASigner(edgeConfig.privateKey))
          AccessToken(jwt.serialize())
        }
      }

    def signLogoutToken(
        issuer: String = "https://idp.example",
        audience: String = "web-app",
        sid: Option[String] = Some("sso-session-1"),
        subject: Option[String] = None,
        nonce: Option[String] = None,
        timeOfEvent: Option[Long] = None,
        events: java.util.Map[String, ?] =
          Collections.singletonMap(backChannelLogoutEvent, Collections.emptyMap()),
        ttlSeconds: Long = 120,
    ): Task[String] =
      Clock.instant.flatMap { now =>
        ZIO.attemptBlocking {
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(edgeConfig.keyId)
            .`type`(JOSEObjectType.JWT)
            .build()
          val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(Collections.singletonList(audience))
            .jwtID(UUID.randomUUID().toString)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("events", events)
          sid.foreach(builder.claim("sid", _))
          subject.foreach(builder.subject)
          nonce.foreach(builder.claim("nonce", _))
          timeOfEvent.foreach(toe => builder.claim("toe", toe))
          val jwt = SignedJWT(header, builder.build())
          jwt.sign(RSASSASigner(edgeConfig.privateKey))
          jwt.serialize()
        }
      }

    /** An access token revocation event: same signing and transport as a logout token, but it
      * names one token (`revoked_jti`/`revoked_exp`) instead of a session.
      */
    def signRevocationToken(
        revokedJti: Option[String],
        revokedExpiresAt: Instant,
        events: java.util.Map[String, ?],
        audience: String = "web-app",
    ): Task[String] =
      Clock.instant.flatMap { now =>
        ZIO.attemptBlocking {
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(edgeConfig.keyId)
            .`type`(JOSEObjectType.JWT)
            .build()
          val builder = JWTClaimsSet.Builder()
            .issuer("https://idp.example")
            .audience(Collections.singletonList(audience))
            .jwtID(UUID.randomUUID().toString)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .claim("events", events)
            .claim("revoked_exp", revokedExpiresAt.getEpochSecond)
          revokedJti.foreach(builder.claim("revoked_jti", _))
          val jwt = SignedJWT(header, builder.build())
          jwt.sign(RSASSASigner(edgeConfig.privateKey))
          jwt.serialize()
        }
      }

    /** How long an entry is kept is the revocation service's own decision now, so what edge
      * asserts is which revocation it asked for, not what expiry it worked out.
      */
    def stubRevocations: UIO[Unit] =
      revocationService.revokeSession.succeedsWith(()) *>
        revocationService.revokeUser.succeedsWith(()) *>
        revocationService.revokeToken.succeedsWith(())

    def withPresets(values: AuthorizationPreset*): UIO[Unit] =
      presetCache.set(values.map(p => p.id -> p).toMap)

    def withClients(values: OAuthClient*): UIO[Unit] =
      clientCache.set(values.map(c => c.id -> c).toMap)

    def withResources(values: Resource*): UIO[Unit] =
      resourceCache.set(values.map(r => r.resourceId -> r).toMap)

    def buildService(httpClient: Client, security: SecurityService, env: EnvName = EnvName.Test("dev")): EdgeService =
      EdgeService.Impl(
        clientService,
        resourceService,
        celEvaluator,
        secureRandom,
        loginRepository,
        ssoClient,
        security,
        httpClient,
        edgeConfig,
        sessionRepository,
        revocationService,
        jwksService,
        permissionService,
        env,
      )

  def spec = suite("EdgeService")(
    authorizeSuite,
    completeSuite,
    frontChannelLogoutSuite,
    backChannelLogoutSuite,
    getMyPermissionsSuite,
  ).provideSomeLayer[Client](
    SecureRandom.live >>> SecurityService.live,
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val authorizeSuite = suite("authorize")(
    test("returns SSO authorize URL and persists login record on known preset") {
      val env = new Env
      for
        _ <- env.withPresets(Fixtures.preset)
        _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
          case 1 => ZIO.succeed(Fixtures.codeVerifierBytes)
          case _ => ZIO.succeed(Fixtures.stateBytes)
        _ <- env.loginRepository.create.succeedsWith(())
        _ <- env.ssoClient.authorizeUri.succeedsWith(Fixtures.authorizeUrl)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        url <- service.authorize(Fixtures.presetId)
        createCalls = env.loginRepository.create.calls
        authorizeCalls = env.ssoClient.authorizeUri.calls
      yield assertTrue(
        url == Fixtures.authorizeUrl,
        createCalls.size == 1,
        createCalls.head._1 == LoginRecord(
          codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
          presetId = Fixtures.presetId,
          state = Fixtures.state,
        ),
        createCalls.head._2 == 10.minutes,
        authorizeCalls.size == 1,
        authorizeCalls.head._1 == Fixtures.preset,
        authorizeCalls.head._3 == Fixtures.state,
        authorizeCalls.head._4 == Map.empty[String, String],
      )
    },
    test("fails with PresetNotFound when preset is missing from cache") {
      val env = new Env
      for
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        result <- service.authorize(Fixtures.presetId).either
      yield assertTrue(
        result == Left(PresetNotFound()),
        env.loginRepository.create.calls.isEmpty,
        env.ssoClient.authorizeUri.calls.isEmpty,
      )
    },
  )

  private val completeSuite = suite("complete") {
    val code = Code("auth-code-123")

    Vector(
      test("succeeds and returns login completion with access token") {
        val env = new Env
        for
          _ <- env.withPresets(Fixtures.preset)
          _ <- env.withClients(Fixtures.client)
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.presetId,
            state = Fixtures.state,
          )))
          _ <- env.loginRepository.deleteByState.succeedsWith(())
          _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(7.toByte))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          accessToken <- env.signToken()
          tokens = Fixtures.tokens.copy(accessToken = accessToken)
          _ <- env.ssoClient.exchangeAuthorizationCode.succeedsWith(tokens)
          service = env.buildService(client, security)
          completion <- service.complete(code, Fixtures.state)
        yield assertTrue(
          completion.accessToken == accessToken,
          completion.cookieTtl == zio.Duration.fromSeconds(tokens.expiresIn),
          completion.postLoginRedirectUri == Fixtures.postLoginUri,
          completion.cookieDomain == Fixtures.preset.cookieDomain,
          completion.cookiePath == Fixtures.preset.cookiePath,
          env.loginRepository.deleteByState.calls == List(Fixtures.state),
          env.ssoClient.exchangeAuthorizationCode.calls.head._1 == code,
        )
      },
      test("fails with AuthConversationNotFound when state lookup is empty") {
        val env = new Env
        for
          _ <- env.loginRepository.findByState.succeedsWith(None)
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          result <- service.complete(code, Fixtures.state).either
        yield assertTrue(result == Left(AuthConversationNotFound()))
      },
      test("completes an OAuth error and returns the post-login URL") {
        val env = new Env
        for
          _ <- env.withPresets(Fixtures.preset)
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.presetId,
            state = Fixtures.state,
          )))
          _ <- env.loginRepository.deleteByState.succeedsWith(())
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          redirectUrl <- service.completeError(
            Fixtures.state,
            "access_denied",
            Some("User cancelled"),
            None,
          )
        yield assertTrue(
          redirectUrl == URL.decode(Fixtures.postLoginUri).toOption.get.addQueryParams(
            List("error" -> "access_denied", "error_description" -> "User cancelled"),
          ),
          env.loginRepository.deleteByState.calls == List(Fixtures.state),
        )
      },
      test("fails with AuthConversationNotFound when preset has been removed") {
        val env = new Env
        for
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.presetId,
            state = Fixtures.state,
          )))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          result <- service.complete(code, Fixtures.state).either
        yield assertTrue(result == Left(AuthConversationNotFound()))
      },
      test("fails with ClientNotFound when oauth client has been removed") {
        val env = new Env
        for
          _ <- env.withPresets(Fixtures.orphanPreset)
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.otherPresetId,
            state = Fixtures.state,
          )))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          result <- service.complete(code, Fixtures.state).either
        yield assertTrue(
          result == Left(EdgeService.ClientNotFound(Fixtures.missingClientId)),
        )
      },
      test("stores encrypted refresh token keyed by access token jti when refresh_token is returned") {
        val env = new Env
        val refreshTokenValue = "refresh-token-1"
        val jti = "jti-123"
        for
          _ <- env.withPresets(Fixtures.preset)
          _ <- env.withClients(Fixtures.client)
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.presetId,
            state = Fixtures.state,
          )))
          _ <- env.loginRepository.deleteByState.succeedsWith(())
          _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(7.toByte))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          accessToken <- env.signToken(jti = jti, ttlSeconds = 3600L)
          tokens = TokenResponse(
            accessToken = accessToken,
            tokenType = "Bearer",
            expiresIn = 3600L,
            refreshToken = Some(RefreshToken(refreshTokenValue)),
            refreshTokenExpiresIn = Some(7200L),
            scope = None,
            idToken = None,
          )
          _ <- env.ssoClient.exchangeAuthorizationCode.succeedsWith(tokens)
          service = env.buildService(client, security)
          completion <- service.complete(code, Fixtures.state)
          createCalls = env.sessionRepository.create.calls
          encryptionKey = SecretKeySpec(env.edgeConfig.security.tokenEncryption.key, "AES")
          decryptedRefresh <- security.decryptAes256(createCalls.head.encryptedRefreshToken.get, encryptionKey)
          now <- Clock.instant
        yield assertTrue(
          completion.accessToken == accessToken,
          completion.cookieTtl == zio.Duration.fromSeconds(tokens.refreshTokenExpiresIn.get),
          createCalls.size == 1,
          createCalls.head.accessTokenId == AccessTokenId(jti),
          createCalls.head.presetId == Fixtures.presetId,
          new String(decryptedRefresh, "UTF-8") == refreshTokenValue,
          createCalls.head.expiresAt.isAfter(now.plusSeconds(7200L + 10 - 5)),
          createCalls.head.expiresAt.isBefore(now.plusSeconds(7200L + 10 + 5)),
        )
      },
      test("still records session participation when no refresh token is issued") {
        val env = new Env
        for
          _ <- env.withPresets(Fixtures.preset)
          _ <- env.withClients(Fixtures.client)
          _ <- env.loginRepository.findByState.succeedsWith(Some(LoginRecord(
            codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
            presetId = Fixtures.presetId,
            state = Fixtures.state,
          )))
          _ <- env.loginRepository.deleteByState.succeedsWith(())
          _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(7.toByte))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          accessToken <- env.signToken(jti = "jti-no-refresh")
          _ <- env.ssoClient.exchangeAuthorizationCode.succeedsWith(
            Fixtures.tokens.copy(accessToken = accessToken),
          )
          service = env.buildService(client, security)
          _ <- service.complete(code, Fixtures.state)
          createCalls = env.sessionRepository.create.calls
        yield assertTrue(
          createCalls.size == 1,
          createCalls.head.accessTokenId == AccessTokenId("jti-no-refresh"),
          createCalls.head.presetId == Fixtures.presetId,
          createCalls.head.encryptedRefreshToken.isEmpty,
        )
      },
    )
  }

  private val frontChannelLogoutSuite = suite("frontChannelLogout")(
    test("deletes session rows and clears EDGE_SESSION cookie for the session's preset") {
      val env = new Env
      val sid = SessionId("sso-session-1")
      val record = EdgeSessionRecord(
        publicSessionId = sid,
        presetId = Fixtures.presetId,
        accessTokenId = AccessTokenId("jti-logout-1"),
        encryptedRefreshToken = Some(Secret(Array.fill(16)(1.toByte))),
        expiresAt = Instant.parse("2024-01-01T00:00:00Z"),
      )
      for
        _ <- env.withPresets(Fixtures.preset)
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(List(record))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(
        cookies == List(EdgeSessionCookie.clear(Fixtures.preset.cookieDomain, Fixtures.preset.cookiePath)),
        env.sessionRepository.findBySessionId.calls == List(sid),
      )
    },
    test("clears the cookie of a preset that never received a refresh token") {
      val env = new Env
      val sid = SessionId("sso-session-no-refresh")
      val record = EdgeSessionRecord(
        publicSessionId = sid,
        presetId = Fixtures.presetId,
        accessTokenId = AccessTokenId("jti-logout-2"),
        encryptedRefreshToken = None,
        expiresAt = Instant.parse("2024-01-01T00:00:00Z"),
      )
      for
        _ <- env.withPresets(Fixtures.preset)
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(List(record))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(
        cookies == List(EdgeSessionCookie.clear(Fixtures.preset.cookieDomain, Fixtures.preset.cookiePath)),
      )
    },
    test("deduplicates presets shared by multiple session records") {
      val env = new Env
      val sid = SessionId("sso-session-multi")
      def record(accessTokenId: String) = EdgeSessionRecord(
        publicSessionId = sid,
        presetId = Fixtures.presetId,
        accessTokenId = AccessTokenId(accessTokenId),
        encryptedRefreshToken = Some(Secret(Array.fill(16)(2.toByte))),
        expiresAt = Instant.parse("2024-01-01T00:00:00Z"),
      )
      for
        _ <- env.withPresets(Fixtures.preset)
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(
          List(record("jti-1"), record("jti-2")),
        )
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(cookies.size == 1)
    },
    test("returns no cookies and skips presets no longer present when session has no known preset") {
      val env = new Env
      val sid = SessionId("sso-session-empty")
      for
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(List.empty)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(
        cookies.isEmpty,
        env.sessionRepository.findBySessionId.calls == List(sid),
      )
    },
    test("revokes the whole session with one entry, however many rows it matched") {
      val env = new Env
      val sid = SessionId("sso-session-revoked")
      val record = EdgeSessionRecord(
        publicSessionId = sid,
        presetId = Fixtures.presetId,
        accessTokenId = AccessTokenId("jti-logout-3"),
        encryptedRefreshToken = None,
        expiresAt = Instant.parse("2024-01-01T00:00:00Z"),
      )
      for
        _ <- env.withPresets(Fixtures.preset)
        _ <- env.withClients(Fixtures.client)
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(List(record, record.copy(accessTokenId = AccessTokenId("jti-logout-4"))))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        _ <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(
        // One entry for the session, not one per row: `sid` already covers them all.
        env.revocationService.revokeSession.calls == List(sid),
      )
    },
    test("revokes a session that matched no rows, clearing no cookies") {
      val env = new Env
      val sid = SessionId("sso-session-bearer-only")
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.stubRevocations
        _ <- env.sessionRepository.findBySessionId.succeedsWith(List.empty)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout(env.edgeConfig.versolaUrl.encode, sid)
      yield assertTrue(
        // No rows means no cookie to clear, but a token of that session can still be in
        // flight as a bearer token, so the revocation is recorded regardless.
        cookies.isEmpty,
        env.revocationService.revokeSession.calls == List(sid),
      )
    },
    test("returns no cookies and does not touch session rows when issuer is unknown") {
      val env = new Env
      val sid = SessionId("sso-session-untrusted")
      for
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        cookies <- service.frontChannelLogout("https://untrusted.example", sid)
      yield assertTrue(
        cookies.isEmpty,
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("accepts issuer with a trailing slash, an explicit default port, or different host casing") {
      checkAll(Gen.fromIterable(List(
        "https://idp.example/",
        "https://idp.example:443",
        "https://IDP.example",
      ))) { iss =>
        val env = new Env
        val sid = SessionId("sso-session-equivalent")
        val record = EdgeSessionRecord(
          publicSessionId = sid,
          presetId = Fixtures.presetId,
          accessTokenId = AccessTokenId("jti-logout-equivalent"),
          encryptedRefreshToken = None,
          expiresAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        for
          _ <- env.withPresets(Fixtures.preset)
          _ <- env.stubRevocations
          _ <- env.sessionRepository.findBySessionId.succeedsWith(List(record))
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          cookies <- service.frontChannelLogout(iss, sid)
        yield assertTrue(
          cookies == List(EdgeSessionCookie.clear(Fixtures.preset.cookieDomain, Fixtures.preset.cookiePath)),
        )
      }
    },
    test("rejects issuer with a mismatched scheme or non-default port") {
      checkAll(Gen.fromIterable(List(
        "http://idp.example",
        "https://idp.example:8443",
      ))) { iss =>
        val env = new Env
        val sid = SessionId("sso-session-mismatched")
        for
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          cookies <- service.frontChannelLogout(iss, sid)
        yield assertTrue(
          cookies.isEmpty,
          env.sessionRepository.findBySessionId.calls.isEmpty,
        )
      }
    },
  )

  private val backChannelLogoutEvent = "http://schemas.openid.net/event/backchannel-logout"
  private val accessTokenRevocationEvent = "versola:event:access-token-revocation"

  private def rejection(result: Either[Throwable | InvalidLogoutToken, Unit]): Option[String] =
    result.swap.toOption.collect { case invalid: InvalidLogoutToken => invalid.reason }

  private val backChannelLogoutSuite = suite("backChannelLogout")(
    test("revokes the session named by a valid logout token without reading a single row") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.stubRevocations
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken()
        _ <- service.backChannelLogout(token)
      yield assertTrue(
        env.revocationService.revokeSession.calls == List(SessionId("sso-session-1")),
        // Nothing about the session has to be looked up to revoke it: the back-channel half
        // of a logout has no cookies to clear, so the `sid` is the whole of what it needs.
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("revokes every token of a user on a logout token naming only a subject") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.stubRevocations
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        now <- Clock.instant
        token <- env.signLogoutToken(sid = None, subject = Some("user-1"))
        _ <- service.backChannelLogout(token)
      yield assertTrue(
        env.sessionRepository.findBySessionId.calls.isEmpty,
        // No `toe`, so `iat` is the closest thing to the event's time on offer.
        env.revocationService.revokeUser.calls == List(("user-1", now)),
      )
    },
    test("bounds a user-wide revocation at the event's own time, not at the token's signing time") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.stubRevocations
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        now <- Clock.instant
        occurredAt = now.minusSeconds(45)
        // A delivery that took a moment to be signed. Bounding at `iat` would put the
        // boundary after a login the user made in between and lock them out of it for an
        // access token's lifetime, so `toe` — when the administrator acted — wins.
        token <- env.signLogoutToken(sid = None, subject = Some("user-1"), timeOfEvent = Some(occurredAt.getEpochSecond))
        _ <- service.backChannelLogout(token)
      yield assertTrue(env.revocationService.revokeUser.calls == List(("user-1", occurredAt)))
    },
    test("revokes only the named token on an access token revocation event, leaving the session alone") {
      val env = new Env
      val revocationEvent = Collections.singletonMap(accessTokenRevocationEvent, Collections.emptyMap())
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        _ <- env.stubRevocations
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        now <- Clock.instant
        token <- env.signRevocationToken(revokedJti = Some("revoked-token"), revokedExpiresAt = now.plusSeconds(300), events = revocationEvent)
        _ <- service.backChannelLogout(token)
      yield assertTrue(
        // A client revoking one of its own tokens must not log every other client of that
        // SSO session out, which revoking the session would do.
        env.revocationService.revokeSession.calls.isEmpty,
        env.revocationService.revokeToken.calls == List((AccessTokenId("revoked-token"), now.plusSeconds(300))),
      )
    },
    test("rejects an access token revocation event that names no token") {
      val env = new Env
      val revocationEvent = Collections.singletonMap(accessTokenRevocationEvent, Collections.emptyMap())
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        now <- Clock.instant
        token <- env.signRevocationToken(revokedJti = None, revokedExpiresAt = now.plusSeconds(300), events = revocationEvent)
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("access token revocation carries no revoked_jti claim"),
        env.revocationService.revokeToken.calls.isEmpty,
      )
    },
    test("rejects a token that is not a valid JWT") {
      val env = new Env
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        result <- service.backChannelLogout("not-a-jwt").either
      yield assertTrue(
        rejection(result).exists(_.contains("not a valid JWT")),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects a token issued by an unknown issuer") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(issuer = "https://untrusted.example")
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("logout token was issued by an unknown issuer"),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects a token addressed to a client this edge does not know") {
      val env = new Env
      for
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(audience = "ghost")
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("logout token is not addressed to a known client"),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects a token without the back-channel logout event") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(events = Collections.emptyMap())
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("logout token does not carry the back-channel logout event"),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects a token carrying a nonce") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(nonce = Some("n-1"))
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("logout token must not carry a nonce"),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects a token naming neither a session nor a subject") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(sid = None)
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).contains("logout token carries neither a sid nor a sub claim"),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
    test("rejects an expired token") {
      val env = new Env
      for
        _ <- env.withClients(Fixtures.client)
        _ <- env.jwksService.getPublicKeys.succeedsWith(env.publicKeys)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        token <- env.signLogoutToken(ttlSeconds = -60)
        result <- service.backChannelLogout(token).either
      yield assertTrue(
        rejection(result).exists(_.contains("not a valid JWT")),
        env.sessionRepository.findBySessionId.calls.isEmpty,
      )
    },
  )

  private def simpleResource(resourceId: String, endpointId: ResourceEndpointId): Resource =
    Resource(
      resourceId = ResourceId(resourceId),
      resource = URL.decode(s"https://$resourceId.example").toOption.get,
      endpoints = Vector(ResourceEndpoint(
        id = endpointId,
        method = "GET",
        path = s"/$resourceId",
        fetchUserInfo = false,
        allow = Some("true"),
        inject = Vector.empty,
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      )),
      secret = None,
    )

  private val centralEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-9000-000000000000"))
  private val ordersEndpointId  = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-9000-000000000001"))
  private val billingEndpointId = ResourceEndpointId(java.util.UUID.fromString("018f0f2a-1c7b-7000-9000-000000000002"))
  private val centralResource   = simpleResource("central", centralEndpointId)
  private val ordersResource    = simpleResource("orders", ordersEndpointId)
  private val billingResource   = simpleResource("billing", billingEndpointId)

  private val getMyPermissionsSuite = suite("getMyPermissions")(
    test("reports the environment so the console can hide non-prod affordances") {
      val env = new Env
      for
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("oauth-admin"))),
        )
        prod <- env.buildService(client, security, EnvName.Prod).getMyPermissions(claims, Nil)
        nonProd <- env.buildService(client, security).getMyPermissions(claims, Nil)
      yield assertTrue(prod.isProd, !nonProd.isProd)
    },
    test("central oauth-admin derives permissions from permission service") {
      val env = new Env
      val perm = PermissionId("users:read")
      for
        _ <- env.withResources(centralResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set(perm))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("oauth-admin"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central")))
      yield assertTrue(
        response.resources == Map("central" -> EdgeService.ResourcePermissions(Set(perm))),
        env.permissionService.getPermissionsForRoles.calls ==
          List((Map(TenantId.default -> List(RoleId("oauth-admin"))), Set(centralEndpointId))),
      )
    },
    test("central admin derives permissions from default-tenant roles") {
      val env = new Env
      val perm = PermissionId("users:read")
      for
        _ <- env.withResources(centralResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set(perm))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("operator"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central")))
      yield assertTrue(
        response.resources == Map("central" -> EdgeService.ResourcePermissions(Set(perm))),
        env.permissionService.getPermissionsForRoles.calls ==
          List((Map(TenantId.default -> List(RoleId("operator"))), Set(centralEndpointId))),
      )
    },
    test("central is NOT omitted when requested by a non-central client") {
      val env = new Env
      val perm = PermissionId("users:read")
      for
        _ <- env.withResources(centralResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set(perm))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("web-app")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("oauth-admin"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central")))
      yield assertTrue(
        response.resources == Map("central" -> EdgeService.ResourcePermissions(Set(perm))),
        env.permissionService.getPermissionsForRoles.calls ==
          List((Map(TenantId.default -> List(RoleId("oauth-admin"))), Set(centralEndpointId))),
      )
    },
    test("resource aliases map only to permissions whose endpoints belong to that resource") {
      val env = new Env
      val ordersPerm  = PermissionId("orders:read")
      val billingPerm = PermissionId("billing:read")
      for
        _ <- env.withResources(ordersResource, billingResource)
        _ <- env.permissionService.getPermissionsForRoles.returnsZIOOnCall:
          case 1 => ZIO.succeed(Set(ordersPerm))   // first call: orders
          case _ => ZIO.succeed(Set(billingPerm))  // second call: billing
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("web-app")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("member"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("orders"), ResourceId("billing")))
      yield assertTrue(
        response.resources == Map(
          "orders"  -> EdgeService.ResourcePermissions(Set(ordersPerm)),
          "billing" -> EdgeService.ResourcePermissions(Set(billingPerm)),
        ),
        env.permissionService.getPermissionsForRoles.calls.size == 2,
      )
    },
    test("combines central and resource aliases using their respective roles") {
      val env = new Env
      val centralPerm  = PermissionId("users:read")
      val resourcePerm = PermissionId("orders:read")
      for
        _ <- env.withResources(centralResource, ordersResource)
        _ <- env.permissionService.getPermissionsForRoles.returnsZIOOnCall:
          case 1 => ZIO.succeed(Set(centralPerm))
          case _ => ZIO.succeed(Set(resourcePerm))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("operator"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central"), ResourceId("orders")))
      yield assertTrue(
        response.resources == Map(
          "central" -> EdgeService.ResourcePermissions(Set(centralPerm)),
          "orders"  -> EdgeService.ResourcePermissions(Set(resourcePerm)),
        ),
        env.permissionService.getPermissionsForRoles.calls.size == 2,
      )
    },
    test("empty alias list yields neither central nor resources") {
      val env = new Env
      for
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("oauth-admin"))),
        )
        response <- service.getMyPermissions(claims, Nil)
      yield assertTrue(
        response.resources.isEmpty,
        env.permissionService.getPermissionsForRoles.calls.isEmpty,
      )
    },
    test("clientId=None means non-central client, central alias is NOT omitted") {
      val env = new Env
      for
        _ <- env.withResources(centralResource, ordersResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set.empty)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = None,
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("member"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central"), ResourceId("orders")))
      yield assertTrue(
        response.resources.contains(ResourceId("central")),
        response.resources.contains(ResourceId("orders")),
        env.permissionService.getPermissionsForRoles.calls.size == 2,
      )
    },
    test("any client role is resolved via permission service for central") {
      val env = new Env
      val perm = PermissionId("users:read")
      for
        _ <- env.withResources(centralResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set(perm))
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("central-admin")),
          tenantId = Some(TenantId.default),
          roles = Some(List(RoleId("editor"))),
        )
        response <- service.getMyPermissions(claims, List(ResourceId("central")))
      yield assertTrue(
        env.permissionService.getPermissionsForRoles.calls ==
          List((Map(TenantId.default -> List(RoleId("editor"))), Set(centralEndpointId))),
        response.resources == Map("central" -> EdgeService.ResourcePermissions(Set(perm))),
      )
    },
    test("tenantId=None yields empty roles map, resource aliases return empty permissions") {
      val env = new Env
      for
        _ <- env.withResources(ordersResource)
        _ <- env.permissionService.getPermissionsForRoles.succeedsWith(Set.empty)
        security <- ZIO.service[SecurityService]
        client <- ZIO.service[Client]
        service = env.buildService(client, security)
        claims = PermissionsClaims(
          jti = AccessTokenId("token-1"),
          subject = "user-1",
          issuedAt = 0,
          clientId = Some(ClientId("web-app")),
          tenantId = None,
          roles = None,
        )
        response <- service.getMyPermissions(claims, List(ResourceId("orders")))
      yield assertTrue(
        response.resources == Map("orders" -> EdgeService.ResourcePermissions(Set.empty)),
        env.permissionService.getPermissionsForRoles.calls.map(_._1) == List(Map.empty),
        env.permissionService.getPermissionsForRoles.calls.map(_._2) ==
          List(Set(ordersEndpointId)),
      )
    },
  )
end EdgeServiceSpec
