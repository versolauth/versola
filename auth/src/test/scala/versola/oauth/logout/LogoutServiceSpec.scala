package versola.oauth.logout

import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken, TenantId}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionId, SessionInfo, SessionRecord, UserAgentId}
import versola.user.model.UserId
import versola.util.{MAC, UnitSpecBase}
import zio.*
import zio.http.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID

object LogoutServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val tenantId = TenantId("tenant-1")
  private val clientId1 = ClientId("client-1")
  private val publicSessionId1 = PublicSessionId("public-session-1")
  private val rawSessionId = SessionId(Array.fill(32)(9.toByte))
  private val mac = MAC(Array.fill(32)(1.toByte))
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val testUserAgentId = UserAgentId(UUID.randomUUID())

  private val record1 = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(clientId1, Instant.EPOCH)),
    userAgentId = testUserAgentId,
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicSessionId1,
  )
  private val sessionInfo1 = SessionInfo(mac, record1)

  private val baseClient = OAuthClientRecord(
    id = clientId1,
    tenantId = tenantId,
    clientName = Map("en" -> "Client 1"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    secret = None,
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

  /** A Client whose driver dies if invoked; no test in this spec exercises a
    * back-channel delivery (none of the fixture clients set backChannelLogoutUri). */
  private val unusedHttpClient: Client = ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
    override def request(
        version: Version,
        method: Method,
        url: URL,
        headers: Headers,
        body: Body,
        sslConfig: Option[ClientSSLConfig],
        proxy: Option[Proxy],
    )(using trace: Trace): ZIO[Scope, Throwable, Response] =
      ZIO.dieMessage("HTTP client should not be used in LogoutService tests")

    override def socket[Env1 <: Any](
        version: Version,
        url: URL,
        headers: Headers,
        app: WebSocketApp[Env1],
    )(using trace: Trace, ev: Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
      ZIO.dieMessage("WebSocket not used in LogoutService tests"),
  )

  private val clientNoLogoutUri = baseClient
  private val clientA = baseClient.copy(
    id = ClientId("client-a"),
    frontChannelLogoutUri = Some(URL.decode("https://rp-a.example/logout").toOption.get),
    frontChannelLogoutSessionRequired = false,
  )
  private val clientB = baseClient.copy(
    id = ClientId("client-b"),
    frontChannelLogoutUri = Some(URL.decode("https://rp-b.example/logout").toOption.get),
    frontChannelLogoutSessionRequired = true,
  )

  private val logoutUriA = URL.decode("https://rp-a.example/logout").toOption.get
    .addQueryParams(List("iss" -> TestEnvConfig.coreConfig.jwt.issuer, "sid" -> publicSessionId1))
  private val logoutUriB = URL.decode("https://rp-b.example/logout").toOption.get
    .addQueryParams(List("iss" -> TestEnvConfig.coreConfig.jwt.issuer, "sid" -> publicSessionId1))

  class Env:
    val sessionService = stub[SessionService]
    val configuration = stub[OAuthConfigurationService]
    val service: LogoutService = LogoutService.Impl(
      sessionService,
      configuration,
      TestEnvConfig.coreConfig,
      TestEnvConfig.jwksService,
      unusedHttpClient,
    )

  def spec = suite("LogoutService")(
    suite("logout")(
      test("returns empty logoutUris and drops the redirect when no session is found") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(None)
          result <- env.service.logout(Right(rawSessionId), Some(redirectUri), Some("st-1"))
        yield assertTrue(
          result == LogoutService.LogoutResult(Nil, None, Some("st-1")),
          env.configuration.find.calls.isEmpty,
        )
      },
      test("returns empty logoutUris when the session's client is not found") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(None)
          result <- env.service.logout(Right(rawSessionId), None, None)
        yield assertTrue(result.logoutUris.isEmpty)
      },
      test("returns empty logoutUris when the session's client has no frontChannelLogoutUri") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientNoLogoutUri))
          result <- env.service.logout(Right(rawSessionId), None, None)
        yield assertTrue(result.logoutUris.isEmpty)
      },
      test("adds iss and sid query params even when frontChannelLogoutSessionRequired is false") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientA))
          result <- env.service.logout(Right(rawSessionId), None, None)
        yield assertTrue(result.logoutUris == List(logoutUriA))
      },
      test("adds iss and sid query params when frontChannelLogoutSessionRequired is true") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientB))
          result <- env.service.logout(Right(rawSessionId), None, None)
        yield assertTrue(result.logoutUris == List(logoutUriB))
      },
      test("aggregates and deduplicates logout URIs across all session participants") {
        val env = Env()
        val clientADuplicate = clientA.copy(id = ClientId("client-a-2"))
        val participants = Map(clientA.id -> clientA, clientADuplicate.id -> clientADuplicate, clientB.id -> clientB)
        val record = record1.copy(clients = List(clientA.id, clientADuplicate.id, clientB.id).map(ClientEntry(_, Instant.EPOCH)))
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(SessionInfo(mac, record)))
          _      <- env.configuration.find.returnsZIO(id => ZIO.succeed(participants.get(id)))
          result <- env.service.logout(Right(rawSessionId), None, None)
        yield assertTrue(result.logoutUris == List(logoutUriA, logoutUriB))
      },
      test("preserves the postLogoutRedirectUri and state when it exactly matches a registered URI") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientNoLogoutUri))
          _      <- env.configuration.getPostLogoutRedirectUris.succeedsWith(List(redirectUri))
          result <- env.service.logout(Left(publicSessionId1), Some(redirectUri), Some("st-2"))
        yield assertTrue(
          result.postLogoutRedirectUri == Some(redirectUri),
          result.state == Some("st-2"),
        )
      },
      test("drops the postLogoutRedirectUri when no session is found, without checking the registry") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(None)
          result <- env.service.logout(Right(rawSessionId), Some(redirectUri), Some("st-3"))
        yield assertTrue(result.postLogoutRedirectUri == None, env.configuration.getPostLogoutRedirectUris.calls.isEmpty)
      },
      test("drops the postLogoutRedirectUri when it is not registered for the tenant") {
        val env = Env()
        val disallowedRedirect = URL.decode("https://evil.example/steal").toOption.get
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientNoLogoutUri))
          _      <- env.configuration.getPostLogoutRedirectUris.succeedsWith(List(redirectUri))
          result <- env.service.logout(Right(rawSessionId), Some(disallowedRedirect), Some("st-4"))
        yield assertTrue(result.postLogoutRedirectUri == None)
      },
      test("drops the postLogoutRedirectUri when only the scheme differs from the registered redirect URI") {
        val env = Env()
        val downgradedRedirect = URL.decode("http://example.com/callback").toOption.get
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientNoLogoutUri))
          _      <- env.configuration.getPostLogoutRedirectUris.succeedsWith(List(redirectUri))
          result <- env.service.logout(Right(rawSessionId), Some(downgradedRedirect), Some("st-5"))
        yield assertTrue(result.postLogoutRedirectUri == None)
      },
      test("drops the postLogoutRedirectUri when nothing is registered for the tenant") {
        val env = Env()
        for
          _      <- env.sessionService.invalidate.succeedsWith(Some(sessionInfo1))
          _      <- env.configuration.find.succeedsWith(Some(clientNoLogoutUri))
          _      <- env.configuration.getPostLogoutRedirectUris.succeedsWith(Nil)
          result <- env.service.logout(Right(rawSessionId), Some(redirectUri), Some("st-6"))
        yield assertTrue(result.postLogoutRedirectUri == None)
      },
    ),
  )
