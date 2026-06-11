package versola.edge

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.ZIOStubs
import versola.edge.EdgeServiceSpec.Fixtures
import versola.edge.login.{LoginRecord, LoginRepository}
import versola.edge.model.*
import versola.util.cel.CelEvaluator
import versola.util.{Base64, JWT, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
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
      scope = Set("openid"),
      responseType = "code",
      uiLocales = None,
      customParameters = Map.empty,
      cookieDomain = Some("app.example"),
      cookiePath = Some("/"),
    )

    val orphanPreset = preset.copy(id = otherPresetId, clientId = missingClientId)

    val client = OAuthClient(id = clientId, secret = Secret(Array.fill(48)(1.toByte)))

    val codeVerifierBytes = Array.fill[Byte](32)(7)
    val stateBytes = Array.fill[Byte](16)(9)
    val loginIdBytes = Array.fill[Byte](32)(11)

    val state = State.fromBytes(stateBytes)
    val loginId = Base64.urlEncode(loginIdBytes)

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
    val refreshTokenRepository = stub[session.EdgeRefreshTokenRepository]
    val permissionService = stub[PermissionService]

    val presetCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[PresetId, AuthorizationPreset])))
    val clientCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[ClientId, OAuthClient])))
    val resourceCache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Map.empty[String, Resource])))

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
        secretKey = SecretKeySpec(Array.fill(32)(2.toByte), "AES"),
      ),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
    )

    val publicKeys: JWT.PublicKeys =
      val rsaKey = RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(edgeConfig.keyId).build()
      JWT.PublicKeys(JWKSet(rsaKey))

    def signToken(jti: String = UUID.randomUUID().toString, ttlSeconds: Long = 600): Task[AccessToken] =
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
            .build()
          val jwt = SignedJWT(header, claims)
          jwt.sign(RSASSASigner(edgeConfig.privateKey))
          AccessToken(jwt.serialize())
        }
      }

    def withPresets(values: AuthorizationPreset*): UIO[Unit] =
      presetCache.set(values.map(p => p.id -> p).toMap)

    def withClients(values: OAuthClient*): UIO[Unit] =
      clientCache.set(values.map(c => c.id -> c).toMap)

    def withResources(values: Resource*): UIO[Unit] =
      resourceCache.set(values.map(r => r.alias -> r).toMap)

    def buildService(httpClient: Client, security: SecurityService): EdgeService =
      EdgeService.Impl(
        clientService, resourceService, celEvaluator, secureRandom,
        loginRepository, ssoClient, security, httpClient, edgeConfig,
        refreshTokenRepository, jwksService, permissionService,
      )

  def spec = suite("EdgeService")(
    authorizeSuite,
    completeSuite,
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
          case 2 => ZIO.succeed(Fixtures.stateBytes)
          case _ => ZIO.succeed(Fixtures.loginIdBytes)
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
        createCalls.head._1 == Fixtures.loginId,
        createCalls.head._2 == LoginRecord(
          codeVerifier = CodeVerifier.fromBytes(Fixtures.codeVerifierBytes),
          presetId = Fixtures.presetId,
          state = Fixtures.state,
        ),
        createCalls.head._3 == 10.minutes,
        authorizeCalls.size == 1,
        authorizeCalls.head._1 == Fixtures.preset,
        authorizeCalls.head._3 == Fixtures.state,
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
          _ <- env.ssoClient.exchangeAuthorizationCode.succeedsWith(Fixtures.tokens)
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          service = env.buildService(client, security)
          completion <- service.complete(code, Fixtures.state)
        yield assertTrue(
          completion.accessToken == Fixtures.tokens.accessToken,
          completion.cookieTtl == zio.Duration.fromSeconds(Fixtures.tokens.expiresIn),
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
          _ <- env.refreshTokenRepository.create.succeedsWith(())
          security <- ZIO.service[SecurityService]
          client <- ZIO.service[Client]
          accessToken <- env.signToken(jti = jti, ttlSeconds = 3600L)
          tokens = TokenResponse(
            accessToken = accessToken, tokenType = "Bearer", expiresIn = 3600L,
            refreshToken = Some(RefreshToken(refreshTokenValue)),
            refreshTokenExpiresIn = Some(7200L),
            scope = None, idToken = None,
          )
          _ <- env.ssoClient.exchangeAuthorizationCode.succeedsWith(tokens)
          service = env.buildService(client, security)
          completion <- service.complete(code, Fixtures.state)
          createCalls = env.refreshTokenRepository.create.calls
          encryptionKey = SecretKeySpec(env.edgeConfig.security.tokenEncryption.key, "AES")
          decryptedRefresh <- security.decryptAes256(createCalls.head._2.encryptedRefreshToken, encryptionKey)
          now <- Clock.instant
        yield assertTrue(
          completion.accessToken == accessToken,
          completion.cookieTtl == zio.Duration.fromSeconds(tokens.refreshTokenExpiresIn.get),
          createCalls.size == 1,
          createCalls.head._1 == AccessTokenId(jti),
          createCalls.head._2.presetId == Fixtures.presetId,
          new String(decryptedRefresh, "UTF-8") == refreshTokenValue,
          createCalls.head._2.expiresAt.isAfter(now.plusSeconds(7200L - 5)),
        )
      },
    )
  }
end EdgeServiceSpec
