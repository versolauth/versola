package versola.account

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.TestEnvConfig
import versola.auth.model.{CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, FormRecord, Locales, LocaleRecord, PasskeySettings, ScopeToken, ThemeRecord}
import versola.oauth.jwks.JwksService
import versola.oauth.session.SessionRepository
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.{Base64, UnitSpecBase}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

import java.time.Instant
import java.util.{Date, UUID}

object AuthSettingsControllerSpec extends UnitSpecBase:

  private val config      = TestEnvConfig.coreConfig
  private val jwksSvc     = TestEnvConfig.jwksService
  private val cookieSecret = config.security.conversationCookieSecret

  private val testUserId   = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  private val testClientId = ClientId("test-client")

  private def createAccessToken(
      userId: UserId,
      clientId: ClientId,
      scope: Set[ScopeToken],
  ): String =
    val now = Instant.now()
    val claims = new JWTClaimsSet.Builder()
      .subject(userId.toString)
      .claim("client_id", clientId.toString)
      .claim("scope", scope.map(_.toString).mkString(" "))
      .claim("jti", "test-access-token-id")
      .audience(clientId.toString)
      .issuer(config.jwt.issuer)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(3600)))
      .build()
    val header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID("test-key-id")
      .`type`(new JOSEObjectType("at+jwt"))
      .build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(config.jwt.privateKey))
    jwt.serialize()

  private def validToken: String =
    createAccessToken(testUserId, testClientId, Set(ScopeToken.AccountSettings))

  /** Build the Cookie request header for SSO_ACCOUNT. */
  private def accountCookieHeader: Header.Cookie =
    Header.Cookie(
      NonEmptyChunk(
        Cookie.Request(
          AuthSettingsCookie.name,
          AuthSettingsCookie.responseCookie(AuthSettingsCookie(testUserId, testClientId), cookieSecret).content,
        ),
      ),
    )

  private val minimalForm = FormRecord(
    id            = "auth-settings",
    version       = 1,
    active        = true,
    style         = ".a{}",
    jsSource      = None,
    jsCompiled    = Some("/* js */"),
    localizations = Map("en" -> Map("sessions_title" -> "Active Sessions")),
    properties    = Vector.empty,
  )

  private val minimalLocales = Locales(
    locales = Vector(LocaleRecord("en", "English")),
    default = "en",
  )

  // Non-empty CSS so the controller doesn't issue a second getTheme call.
  private val minimalTheme = ThemeRecord("default", "body{}", None)

  private def buildRoutes(
      sessionRepo: SessionRepository,
      passkeyRepo: PasskeyRepository,
      webAuthn: WebAuthnService,
      oauthConfig: OAuthConfigurationService,
      userRepo: UserRepository,
      tracing: ZEnvironment[zio.telemetry.opentelemetry.tracing.Tracing],
  ) =
    Observability.handleErrors(
      AuthSettingsController.routes.provideEnvironment(
        ZEnvironment(config) ++
          ZEnvironment(jwksSvc) ++
          ZEnvironment(sessionRepo) ++
          ZEnvironment(passkeyRepo) ++
          ZEnvironment(webAuthn) ++
          ZEnvironment(oauthConfig) ++
          ZEnvironment(userRepo) ++
          tracing,
      )
    )

  def spec = suite("AuthSettingsController")(

    // -------------------------------------------------------------------------
    // GET /auth-settings
    // -------------------------------------------------------------------------
    suite("GET /auth-settings")(

      test("without Authorization returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(Request.get(URL.empty / "auth-settings"))
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with an invalid Bearer token returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer("not.a.jwt")),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with Bearer token missing account_settings scope returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          token        = createAccessToken(testUserId, testClientId, Set(ScopeToken.OpenId))
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(token)),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid Bearer token returns 200 HTML page") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- sessionRepo.findByUserIdWithId.succeedsWith(Nil)
          _           <- passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _           <- userRepo.find.succeedsWith(None)
          _           <- oauthConfig.getPasskeySettings.succeedsWith(None)
          _           <- oauthConfig.getForm.succeedsWith(Some(minimalForm))
          _           <- oauthConfig.find.succeedsWith(None)
          _           <- oauthConfig.getTheme.succeedsWith(Some(minimalTheme))
          _           <- oauthConfig.getLocales.succeedsWith(minimalLocales)
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
          body        <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("versola-form-root"),
        )
      },

      test("with valid Bearer token but missing form returns 404") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- sessionRepo.findByUserIdWithId.succeedsWith(Nil)
          _           <- passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _           <- userRepo.find.succeedsWith(None)
          _           <- oauthConfig.getPasskeySettings.succeedsWith(None)
          _           <- oauthConfig.getForm.succeedsWith(None)
          _           <- oauthConfig.find.succeedsWith(None)
          _           <- oauthConfig.getTheme.succeedsWith(Some(minimalTheme))
          _           <- oauthConfig.getLocales.succeedsWith(minimalLocales)
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.get(URL.empty / "auth-settings")
              .addHeader(Header.Authorization.Bearer(validToken)),
          )
        yield assertTrue(resp.status == Status.NotFound)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/sessions/logout
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/sessions/logout")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(URL.empty / "auth-settings" / "sessions" / "logout", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid SSO_ACCOUNT cookie invalidates session and redirects") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- sessionRepo.invalidate.succeedsWith(())
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          sessionIdB64 = Base64.urlEncode(Array.fill(32)(0.toByte))
          resp        <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "sessions" / "logout",
              Body.fromURLEncodedForm(Form.fromStrings("id" -> sessionIdB64)),
            ).addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.SeeOther)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/passkeys/delete
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/passkeys/delete")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "delete", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid SSO_ACCOUNT cookie deletes passkey and redirects") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- passkeyRepo.deleteByUser.succeedsWith(())
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          credB64      = Base64.urlEncode(Array.fill(32)(0.toByte))
          resp        <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "passkeys" / "delete",
              Body.fromURLEncodedForm(Form.fromStrings("id" -> credB64)),
            ).addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.SeeOther)
      },
    ),

    // -------------------------------------------------------------------------
    // POST /auth-settings/passkeys/register
    // -------------------------------------------------------------------------
    suite("POST /auth-settings/passkeys/register")(

      test("without SSO_ACCOUNT cookie returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with SSO_ACCOUNT but missing SSO_PASSKEY_REG returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty)
              .addHeader(accountCookieHeader),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with invalid SSO_PASSKEY_REG content returns 401") {
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(URL.empty / "auth-settings" / "passkeys" / "register", Body.empty)
              .addHeader(accountCookieHeader)
              .addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(PasskeyRegistrationCookie.name, "invalid.content")))),
          )
        yield assertTrue(resp.status == Status.Unauthorized)
      },

      test("with valid ceremony cookie and response redirects to auth-settings") {
        val ceremony       = PasskeyRegistrationCookie("ceremony-request-json")
        val regCookieContent = PasskeyRegistrationCookie.responseCookie(ceremony, cookieSecret).content
        val minimalPasskeySettings = PasskeySettings(
          rpId             = "localhost",
          rpName           = "Test",
          origins          = List("http://localhost:9005"),
          userVerification = "preferred",
        )
        val stubRecord = PasskeyRecord(
          id               = CredentialId(Array.fill(32)(0.toByte)),
          userId           = testUserId,
          publicKey        = Array.emptyByteArray,
          signatureCounter = 0L,
          deviceType       = CredentialDeviceType.SingleDevice,
          backedUp         = false,
          backupEligible   = false,
          transports       = Nil,
          attestationObject = None,
          clientDataJson   = None,
          aaguid           = None,
          name             = None,
          lastUsedAt       = None,
          createdAt        = java.time.Instant.EPOCH,
          updatedAt        = java.time.Instant.EPOCH,
        )
        for
          client      <- ZIO.service[Client]
          tracing     <- NoopTracing.layer.build
          sessionRepo  = stub[SessionRepository]
          passkeyRepo  = stub[PasskeyRepository]
          webAuthn     = stub[WebAuthnService]
          oauthConfig  = stub[OAuthConfigurationService]
          userRepo     = stub[UserRepository]
          _           <- oauthConfig.getPasskeySettings.succeedsWith(Some(minimalPasskeySettings))
          _           <- webAuthn.finishRegistration.succeedsWith(stubRecord)
          _           <- TestClient.addRoutes(buildRoutes(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo, tracing))
          resp        <- client.batched(
            Request.post(
              URL.empty / "auth-settings" / "passkeys" / "register",
              Body.fromURLEncodedForm(Form.fromStrings("response" -> "{}")),
            ).addHeader(accountCookieHeader)
              .addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(PasskeyRegistrationCookie.name, regCookieContent)))),
          )
        yield assertTrue(resp.status == Status.SeeOther)
      },
    ),

  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
