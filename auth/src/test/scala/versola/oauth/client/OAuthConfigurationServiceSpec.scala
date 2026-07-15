package versola.oauth.client

import versola.oauth.client.model.*
import versola.util.*
import zio.*
import zio.durationInt
import zio.prelude.NonEmptySet
import zio.test.*

object OAuthConfigurationServiceSpec extends UnitSpecBase:
  val clientId1 = ClientId("client-1")
  val publicClientId = ClientId("public-client")
  val testSecret = Secret(Array.fill(32)(5.toByte))
  val wrongSecret = Secret(Array.fill(32)(99.toByte))
  val tenantId = TenantId("default")

  val privateClient = OAuthClientRecord(
    id = clientId1,
    tenantId = tenantId,
    clientName = "Private",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = Some(testSecret),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
  )
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    tenantId = tenantId,
    clientName = "Public",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
  )

  val testScopes = Vector(ScopeRecord(ScopeToken("read"), Vector.empty))
  val testForm = FormRecord("form-1", 1, true, "body{}", None, None, Map.empty, Vector.empty)
  val testTheme = ThemeRecord("default", "body{}", None)
  val testLocales = Locales(Vector(LocaleRecord("en", "English")), "en")
  val challengeSettings = ChallengeSettingsRecord(
    tenantId = tenantId,
    allowedPrefixes = List("+1"),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("rp.example.com", "RP", List("https://rp.example.com"), "required"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = Some(3600),
    ipHeader = "X-Real-IP",
  )
  val systemSettings = SystemSettingsRecord.default

  def makeEnv(
      clients: Map[ClientId, OAuthClientRecord] = Map(clientId1 -> privateClient, publicClientId -> publicClient),
      scopes: Vector[ScopeRecord] = testScopes,
      forms: Vector[FormRecord] = Vector(testForm),
      themes: Vector[ThemeRecord] = Vector(testTheme),
      locales: Locales = testLocales,
      otpTemplates: Vector[OtpTemplateRecord] = Vector.empty,
      challengeSettingsVec: Vector[ChallengeSettingsRecord] = Vector(challengeSettings),
      sysSettings: SystemSettingsRecord = systemSettings,
  ) =
    for
      clientRef         <- Ref.make(clients)
      scopeRef          <- Ref.make(scopes)
      formRef           <- Ref.make(forms)
      themeRef          <- Ref.make(themes)
      localeRef         <- Ref.make(locales)
      otpRef            <- Ref.make(otpTemplates)
      challengeRef      <- Ref.make(challengeSettingsVec)
      sysRef            <- Ref.make(sysSettings)
    yield OAuthConfigurationService.Impl(
      clientCache = ReloadingCache(clientRef),
      clientRepository = stub[OAuthClientSyncClient],
      scopeCache = ReloadingCache(scopeRef),
      scopeRepository = stub[OAuthScopeSyncClient],
      formCache = ReloadingCache(formRef),
      formRepository = stub[FormSyncClient],
      themeCache = ReloadingCache(themeRef),
      themeRepository = stub[ThemeSyncClient],
      localeCache = ReloadingCache(localeRef),
      localeRepository = stub[LocaleSyncClient],
      otpTemplateCache = ReloadingCache(otpRef),
      otpTemplateRepository = stub[OtpTemplateSyncClient],
      challengeSettingsCache = ReloadingCache(challengeRef),
      challengeSettingsRepository = stub[ChallengeSettingsSyncClient],
      systemSettingsCache = ReloadingCache(sysRef),
      systemSettingsRepository = stub[SystemSettingsSyncClient],
    )

  val spec = suite("OAuthConfigurationService")(
    test("find returns existing client") {
      for
        env <- makeEnv()
        result <- env.find(clientId1)
      yield assertTrue(result.contains(privateClient))
    },
    test("find returns None for missing client") {
      for
        env <- makeEnv()
        result <- env.find(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("verifySecret accepts confidential client with correct secret") {
      for
        env <- makeEnv()
        result <- env.verifySecret(clientId1, Some(testSecret))
      yield assertTrue(result.contains(privateClient))
    },
    test("verifySecret rejects confidential client with wrong secret") {
      for
        env <- makeEnv()
        result <- env.verifySecret(clientId1, Some(wrongSecret))
      yield assertTrue(result.isEmpty)
    },
    test("verifySecret accepts public client without secret") {
      for
        env <- makeEnv()
        result <- env.verifySecret(publicClientId, None)
      yield assertTrue(result.contains(publicClient))
    },
    test("verifySecret rejects public client with secret") {
      for
        env <- makeEnv()
        result <- env.verifySecret(publicClientId, Some(testSecret))
      yield assertTrue(result.isEmpty)
    },
    test("getScopes returns scopes") {
      for
        env <- makeEnv()
        result <- env.getScopes
      yield assertTrue(result == testScopes)
    },
    test("getForm returns form by id") {
      for
        env <- makeEnv()
        found <- env.getForm("form-1")
        notFound <- env.getForm("missing")
      yield assertTrue(found.contains(testForm), notFound.isEmpty)
    },
    test("getTheme returns theme by id") {
      for
        env <- makeEnv()
        found <- env.getTheme("default")
        notFound <- env.getTheme("missing")
      yield assertTrue(found.contains(testTheme), notFound.isEmpty)
    },
    test("getLocales returns locales") {
      for
        env <- makeEnv()
        result <- env.getLocales
      yield assertTrue(result == testLocales)
    },
    test("getAllowedPhonePrefixes returns prefixes for known client") {
      for
        env <- makeEnv()
        result <- env.getAllowedPhonePrefixes(clientId1)
      yield assertTrue(result == List("+1"))
    },
    test("getAllowedPhonePrefixes returns Nil for unknown client") {
      for
        env <- makeEnv()
        result <- env.getAllowedPhonePrefixes(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("getPasswordRegex returns from system settings") {
      for
        env <- makeEnv()
        result <- env.getPasswordRegex
      yield assertTrue(result == SystemSettingsRecord.DefaultPasswordRegex)
    },
    test("getOtpSettings returns correct settings") {
      for
        env <- makeEnv()
        result <- env.getOtpSettings(clientId1)
      yield assertTrue(result.length == 6, result.resendAfter == 60)
    },
    test("getAuthConversationTtl returns duration from challenge settings") {
      for
        env <- makeEnv()
        result <- env.getAuthConversationTtl(clientId1)
      yield assertTrue(result == Duration.fromSeconds(900))
    },
    test("getSessionTtl returns duration from challenge settings") {
      for
        env <- makeEnv()
        result <- env.getSessionTtl(clientId1)
      yield assertTrue(result == Duration.fromSeconds(86400))
    },
    test("getSessionIdleTtl returns Some when set") {
      for
        env <- makeEnv()
        result <- env.getSessionIdleTtl(clientId1)
      yield assertTrue(result.contains(Duration.fromSeconds(3600)))
    },
  )
