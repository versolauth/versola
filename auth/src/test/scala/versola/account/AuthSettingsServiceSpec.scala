package versola.account

import org.scalamock.stubs.ZIOStubs
import versola.auth.model.{CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.{PasskeyCeremony, PasskeyRepository, WebAuthnError, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, FormRecord, Locales, LocaleRecord, PasskeySettings, ThemeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionRecord, UserAgentInfo}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.UnitSpecBase
import versola.util.http.Unauthorized
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AuthSettingsServiceSpec extends UnitSpecBase, ZIOStubs:

  val testUserId   = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001"))
  val testClientId = ClientId("test-client")

  val testPasskeySettings = PasskeySettings(
    rpId             = "localhost",
    rpName           = "Test",
    origins          = List("https://localhost"),
    userVerification = "preferred",
  )

  val baseForm = FormRecord(
    id            = "auth-settings",
    version       = 1,
    active        = true,
    style         = ".form{}",
    jsSource      = None,
    jsCompiled    = Some("/* js */"),
    localizations = Map(
      "en" -> Map("page_title" -> "Account Settings", "sessions_title" -> "Active Sessions"),
      "de" -> Map("page_title" -> "Kontoeinstellungen", "sessions_title" -> "Aktive Sitzungen"),
    ),
    properties    = Vector.empty,
  )

  val defaultLocales = Locales(
    locales = Vector(LocaleRecord("en", "English"), LocaleRecord("de", "Deutsch")),
    default = "en",
  )

  val themeWithCss = ThemeRecord(id = "primary", css = "body{color:red}", tenantId = None)
  val defaultTheme = ThemeRecord(id = "default", css = "body{color:black}", tenantId = None)

  val testSessionRecord = SessionRecord(
    userId    = testUserId,
    clientId  = testClientId,
    userAgent = UserAgentInfo("desktop", None, None, None),
    createdAt = java.time.Instant.EPOCH,
    amr       = Map.empty,
  )

  class Env:
    val sessionRepo = stub[SessionRepository]
    val passkeyRepo = stub[PasskeyRepository]
    val webAuthn    = stub[WebAuthnService]
    val oauthConfig = stub[OAuthConfigurationService]
    val userRepo    = stub[UserRepository]

    val service: AuthSettingsService =
      AuthSettingsService.Impl(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo)

  val spec = suite("AuthSettingsService")(

    // -------------------------------------------------------------------------
    // getPageData
    // -------------------------------------------------------------------------

    suite("getPageData")(

      test("returns None when form not found") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(None)
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(result.isEmpty)
      },

      test("uses default locale when it exists in localizations") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.locale == "en",
          result.get.pageTitle == "Account Settings",
          result.get.translations.contains("sessions_title"),
        )
      },

      test("falls back to first sorted locale when default not in localizations") {
        val env = Env()
        val localesWithMissingDefault = Locales(
          locales = Vector(LocaleRecord("de", "Deutsch"), LocaleRecord("fr", "Français")),
          default = "es", // "es" not present in form localizations
        )
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.oauthConfig.getLocales.succeedsWith(localesWithMissingDefault)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.locale == "de", // only "de" survives the intersection of available locales and form localizations
        )
      },

      test("uses CSS from the resolved theme") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None) // themeId falls back to "default"
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.css == themeWithCss.css,
        )
      },

      test("uses empty CSS when theme is missing") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          // client is None → themeId = "default"; both lookups (primary pass + fallback) return None
          _ <- env.oauthConfig.getTheme.succeedsWith(None)
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.css == "",
        )
      },

      test("passes sessions and passkeys through to the result") {
        val env = Env()
        val publicId = UUID.randomUUID()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List((publicId, testSessionRecord)))
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector(dummyPasskeyRecord))
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.sessions == List((publicId, testSessionRecord)),
          result.get.passkeys == Vector(dummyPasskeyRecord),
        )
      },

      test("includes passkey ceremony when settings present and startRegistration succeeds") {
        val env = Env()
        val ceremony = PasskeyCeremony("reg-request-json", """{"challenge":"xyz"}""")
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(Some(testPasskeySettings))
          _ <- env.userRepo.find.succeedsWith(None)
          _ <- env.webAuthn.startRegistration.succeedsWith(ceremony)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.passkeyResult.contains(ceremony),
        )
      },

      test("sets passkeyResult to None when startRegistration fails (graceful degradation)") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(Some(testPasskeySettings))
          _ <- env.userRepo.find.succeedsWith(None)
          _ <- env.webAuthn.startRegistration.failsWith(WebAuthnError.CeremonyFailed("RP not configured"))
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.passkeyResult.isEmpty,
        )
      },

      test("sets passkeyResult to None when no passkey settings configured") {
        val env = Env()
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          _ <- env.userRepo.find.succeedsWith(None)
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.passkeyResult.isEmpty,
        )
      },

      test("succeeds when user record is found (passkey displayName path)") {
        val env = Env()
        import versola.user.model.Login
        val userWithLogin = UserRecord.empty(testUserId).copy(login = Some(Login("alice")))
        for
          _ <- env.sessionRepo.findByUserIdWithId.succeedsWith(List.empty)
          _ <- env.passkeyRepo.listByUser.succeedsWith(Vector.empty)
          _ <- env.oauthConfig.getForm.succeedsWith(Some(baseForm))
          _ <- env.oauthConfig.find.succeedsWith(None)
          _ <- env.oauthConfig.getTheme.succeedsWith(Some(themeWithCss))
          _ <- env.oauthConfig.getLocales.succeedsWith(defaultLocales)
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(Some(testPasskeySettings))
          _ <- env.userRepo.find.succeedsWith(Some(userWithLogin))
          _ <- env.webAuthn.startRegistration.succeedsWith(PasskeyCeremony("req", "{}"))
          result <- env.service.getPageData(testUserId, testClientId)
        yield assertTrue(
          result.isDefined,
          result.get.passkeyResult.isDefined,
        )
      },
    ),

    // -------------------------------------------------------------------------
    // finishPasskeyRegistration
    // -------------------------------------------------------------------------

    suite("finishPasskeyRegistration")(

      test("fails with Unauthorized when no passkey settings") {
        val env = Env()
        for
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(None)
          result <- env.service.finishPasskeyRegistration(testUserId, testClientId, "req", "{}", None).either
        yield assertTrue(result == Left(Unauthorized))
      },

      test("succeeds when settings found and ceremony finishes") {
        val env = Env()
        for
          _ <- env.oauthConfig.getPasskeySettings.succeedsWith(Some(testPasskeySettings))
          _ <- env.webAuthn.finishRegistration.succeedsWith(dummyPasskeyRecord)
          result <- env.service.finishPasskeyRegistration(testUserId, testClientId, "req", "{}", Some("My Key")).either
        yield assertTrue(result.isRight)
      },
    ),

    // -------------------------------------------------------------------------
    // invalidateSession / deletePasskey — thin delegation checks
    // -------------------------------------------------------------------------

    test("invalidateSession delegates to sessionRepo.invalidate") {
      val env = Env()
      val publicId = UUID.randomUUID()
      for
        _ <- env.sessionRepo.invalidate.succeedsWith(())
        _ <- env.service.invalidateSession(publicId)
      yield assertCompletes
    },

    test("deletePasskey delegates to passkeyRepo.deleteByUser") {
      val env = Env()
      val credId = CredentialId(Array.fill(32)(0.toByte))
      for
        _ <- env.passkeyRepo.deleteByUser.succeedsWith(())
        _ <- env.service.deletePasskey(credId, testUserId)
      yield assertCompletes
    },
  )

  private val dummyPasskeyRecord = PasskeyRecord(
    id              = CredentialId(Array.fill(32)(0.toByte)),
    userId          = UserId(UUID.fromString("a1000000-0000-0000-0000-000000000001")),
    publicKey       = Array.emptyByteArray,
    signatureCounter = 0L,
    deviceType      = CredentialDeviceType.SingleDevice,
    backedUp        = false,
    backupEligible  = false,
    transports      = Nil,
    attestationObject = None,
    clientDataJson  = None,
    aaguid          = None,
    name            = None,
    lastUsedAt      = None,
    createdAt       = Instant.EPOCH,
    updatedAt       = Instant.EPOCH,
  )
