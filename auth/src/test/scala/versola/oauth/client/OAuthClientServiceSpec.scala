package versola.oauth.client

import versola.oauth.client.model.{Acr, ChallengeSettingsRecord, Claim, ClaimRecord, ClientId, FormRecord, Locales, OAuthClientRecord, OtpTemplateRecord, PassedAuthFactor, PasskeySettings, RateLimit, ScopeRecord, ScopeToken, SubmissionLimits, SystemSettingsRecord, TenantId, ThemeRecord}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.metadata.MetadataSyncClient
import versola.util.*
import zio.*
import zio.durationInt
import zio.json.ast.Json
import zio.prelude.{EqualOps, NonEmptyList, NonEmptySet}
import zio.test.*
import zio.test.Assertion.*

object OAuthClientServiceSpec extends UnitSpecBase:
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val publicClientId = ClientId("public-client-1")
  val testSecret = Secret(Array.fill(32)(5.toByte))
  val previousClientSecret = Secret(Array.fill(32)(6.toByte))
  val wrongClientSecret = Secret(Array.fill(32)(99.toByte))

  val privateClient1 = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = "Private 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read"), ScopeToken("write")),
    externalAudience = Nil,
    secret = Some(testSecret),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
  )
  val privateClient2 = OAuthClientRecord(
    id = clientId2,
    tenantId = TenantId("default"),
    clientName = "Private 2",
    redirectUris = NonEmptySet("https://example2.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = Some(testSecret),
    previousSecret = Some(previousClientSecret),
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
  )
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    tenantId = TenantId("default"),
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
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
  )
  val testClients = Map(clientId1 -> privateClient1, clientId2 -> privateClient2, publicClientId -> publicClient)
  val testScopes = Vector(
    ScopeRecord(
      scope = ScopeToken("read"),
      claims = Vector(ClaimRecord(claim = Claim("sub")), ClaimRecord(claim = Claim("name"))),
    ),
    ScopeRecord(
      scope = ScopeToken("write"),
      claims = Vector(ClaimRecord(claim = Claim("email"))),
    ),
  )

  final class Env(
      val clientCache: ReloadingCache[Map[ClientId, OAuthClientRecord]],
      val scopeCache: ReloadingCache[Vector[ScopeRecord]],
      val formCache: ReloadingCache[Vector[FormRecord]],
      val themeCache: ReloadingCache[Vector[ThemeRecord]],
      val localeCache: ReloadingCache[Locales],
      val otpTemplateCache: ReloadingCache[Vector[OtpTemplateRecord]],
      val challengeSettingsCache: ReloadingCache[Vector[ChallengeSettingsRecord]],
      val systemSettingsCache: ReloadingCache[SystemSettingsRecord],
      val metadataCache: ReloadingCache[Json.Obj],
  ):
    val clientSync = stub[OAuthClientSyncClient]
    val scopeSync = stub[OAuthScopeSyncClient]
    val formSync = stub[FormSyncClient]
    val themeSync = stub[ThemeSyncClient]
    val localeSync = stub[LocaleSyncClient]
    val otpTemplateSync = stub[OtpTemplateSyncClient]
    val challengeSettingsSync = stub[ChallengeSettingsSyncClient]
    val systemSettingsSync = stub[SystemSettingsSyncClient]
    val metadataSync = stub[MetadataSyncClient]
    val service: OAuthConfigurationService =
      OAuthConfigurationService.Impl(
        clientCache,
        clientSync,
        scopeCache,
        scopeSync,
        formCache,
        formSync,
        themeCache,
        themeSync,
        localeCache,
        localeSync,
        otpTemplateCache,
        otpTemplateSync,
        challengeSettingsCache,
        challengeSettingsSync,
        systemSettingsCache,
        systemSettingsSync,
        metadataCache,
        metadataSync,
      )

  private def makeEnv(
      clients: Map[ClientId, OAuthClientRecord] = testClients,
      scopes: Vector[ScopeRecord] = testScopes,
      forms: Vector[FormRecord] = Vector.empty,
      themes: Vector[ThemeRecord] = Vector.empty,
      locales: Locales = Locales(Vector.empty, "en"),
      otpTemplates: Vector[OtpTemplateRecord] = Vector.empty,
      challengeSettings: Vector[ChallengeSettingsRecord] = Vector.empty,
      systemSettings: SystemSettingsRecord = SystemSettingsRecord.default,
  ) =
    for
      clientRef <- Ref.make(clients)
      scopeRef <- Ref.make(scopes)
      formRef <- Ref.make(forms)
      themeRef <- Ref.make(themes)
      localeRef <- Ref.make(locales)
      otpTemplateRef <- Ref.make(otpTemplates)
      challengeSettingsRef <- Ref.make(challengeSettings)
      systemSettingsRef <- Ref.make(systemSettings)
      metadataRef <- Ref.make(Json.Obj())
    yield Env(
      clientCache = ReloadingCache(clientRef),
      scopeCache = ReloadingCache(scopeRef),
      formCache = ReloadingCache(formRef),
      themeCache = ReloadingCache(themeRef),
      localeCache = ReloadingCache(localeRef),
      otpTemplateCache = ReloadingCache(otpTemplateRef),
      challengeSettingsCache = ReloadingCache(challengeSettingsRef),
      systemSettingsCache = ReloadingCache(systemSettingsRef),
      metadataCache = ReloadingCache(metadataRef),
    )

  val spec = suite("OAuthConfigurationService")(
    test("find returns existing client") {
      for
        env <- makeEnv()
        result <- env.service.find(clientId1)
      yield assertTrue(result === Some(privateClient1))
    },
    test("find returns None for missing client") {
      for
        env <- makeEnv()
        result <- env.service.find(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("verifySecret accepts public client only without secret") {
      for
        env <- makeEnv()
        ok <- env.service.verifySecret(publicClientId, None)
        bad <- env.service.verifySecret(publicClientId, Some(testSecret))
      yield assertTrue(ok === Some(publicClient), bad.isEmpty)
    },
    test("verifySecret accepts current and previous private secrets") {
      for
        env <- makeEnv()
        current <- env.service.verifySecret(clientId1, Some(testSecret))
        previous <- env.service.verifySecret(clientId2, Some(previousClientSecret))
      yield assertTrue(current === Some(privateClient1), previous === Some(privateClient2))
    },
    test("verifySecret rejects wrong or missing private secret") {
      for
        env <- makeEnv()
        wrong <- env.service.verifySecret(clientId1, Some(wrongClientSecret))
        missing <- env.service.verifySecret(clientId1, None)
      yield assertTrue(wrong.isEmpty, missing.isEmpty)
    },
    test("getScopes returns cached scope records") {
      for
        env <- makeEnv()
        result <- env.service.getScopes
      yield assertTrue(result == testScopes)
    },
    suite("getClientTemplate")(
      test("returns template body for preferred locale") {
        val template = OtpTemplateRecord(
          "default",
          TenantId("default"),
          Map("en" -> "Your code is {{code}}", "ru" -> "Ваш код {{code}}"),
          purpose = "otp",
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, Some(List("ru")))
        yield assertTrue(result == OtpTemplate("Ваш код {{code}}"))
      },
      test("falls back to default locale when preferred locale is not in template") {
        val template = OtpTemplateRecord(
          "default",
          TenantId("default"),
          Map("en" -> "Your code is {{code}}"),
          purpose = "otp",
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, Some(List("ru")))
        yield assertTrue(result == OtpTemplate("Your code is {{code}}"))
      },
      test("falls back to first available locale when no preferred or default matches") {
        val template = OtpTemplateRecord(
          "default",
          TenantId("default"),
          Map("fr" -> "Votre code {{code}}"),
          purpose = "otp",
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, None)
        yield assertTrue(result == OtpTemplate("Votre code {{code}}"))
      },
      test("returns illegal state template when client is not found") {
        for
          env <- makeEnv(clients = Map.empty)
          result <- env.service.getClientTemplate(ClientId("missing"), None)
        yield assertTrue(result == OtpTemplate("{{code}}"))
      },
      test("returns illegal state template when no template found for client") {
        for
          env <- makeEnv(otpTemplates = Vector.empty)
          result <- env.service.getClientTemplate(clientId1, None)
        yield assertTrue(result == OtpTemplate("{{code}}"))
      },
    ),
    suite("getPasswordTemplate")(
      test("returns template body for preferred locale") {
        val template = OtpTemplateRecord(
          "password-template",
          TenantId("default"),
          Map("en" -> "Password reset: {{code}}", "ru" -> "Сброс пароля: {{code}}"),
          purpose = "password",
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getPasswordTemplate(Some(List("ru")))
        yield assertTrue(result == OtpTemplate("Сброс пароля: {{code}}"))
      },
      test("fails when no password template found") {
        for
          env <- makeEnv(otpTemplates = Vector.empty)
          result <- env.service.getPasswordTemplate(None).exit
        yield assert(result)(fails(isSubtype[RuntimeException](hasMessage(equalTo("No global password template configured")))))
      }
    ),
    test("getSubmissionLimits returns limits from challenge settings") {
      val limits = SubmissionLimits(otpRequest = List(RateLimit(5, 60)))
      val settings = ChallengeSettingsRecord(
        tenantId = TenantId("default"),
        allowedPrefixes = Nil,
        submissionLimits = limits,
        otpLength = 6,
        otpResendAfter = 60,
        passkeySettings = PasskeySettings("rp", "RP", Nil, "required"),
        authConversationTtlSeconds = 900,
        sessionTtlSeconds = 86400,
        sessionIdleTtlSeconds = None,
        ipHeader = "X-Forwarded-For",
        acrVocabulary = None,
      )
      for
        env <- makeEnv(challengeSettings = Vector(settings))
        result <- env.service.getSubmissionLimits(clientId1)
      yield assertTrue(result == limits)
    },
    test("getIpHeader returns header from challenge settings") {
      val settings = ChallengeSettingsRecord(
        tenantId = TenantId("default"),
        allowedPrefixes = Nil,
        submissionLimits = SubmissionLimits.empty,
        otpLength = 6,
        otpResendAfter = 60,
        passkeySettings = PasskeySettings("rp", "RP", Nil, "required"),
        authConversationTtlSeconds = 900,
        sessionTtlSeconds = 86400,
        sessionIdleTtlSeconds = None,
        ipHeader = "X-Custom-IP",
        acrVocabulary = None,
      )
      for
        env <- makeEnv(challengeSettings = Vector(settings))
        result <- env.service.getIpHeader(clientId1)
      yield assertTrue(result == "X-Custom-IP")
    },
    test("getPasskeySettings returns settings from challenge settings") {
      val passkey = PasskeySettings("rp.com", "RP", List("https://rp.com"), "preferred")
      val settings = ChallengeSettingsRecord(
        tenantId = TenantId("default"),
        allowedPrefixes = Nil,
        submissionLimits = SubmissionLimits.empty,
        otpLength = 6,
        otpResendAfter = 60,
        passkeySettings = passkey,
        authConversationTtlSeconds = 900,
        sessionTtlSeconds = 86400,
        sessionIdleTtlSeconds = None,
        ipHeader = "X-Real-IP",
        acrVocabulary = None,
      )
      for
        env <- makeEnv(challengeSettings = Vector(settings))
        result <- env.service.getPasskeySettings(clientId1)
      yield assertTrue(result == Some(passkey))
    },
    test("getPasswordHistorySettings returns from system settings") {
      val settings = SystemSettingsRecord.default.copy(passwordHistorySize = 10, passwordNumDifferent = 3)
      for
        env <- makeEnv(systemSettings = settings)
        result <- env.service.getPasswordHistorySettings
      yield assertTrue(result.historySize == 10, result.numDifferent == 3)
    },
    test("getMetadata returns cached metadata") {
      val metadata = Json.Obj("issuer" -> Json.Str("https://issuer.com"))
      for
        env <- makeEnv()
        _ <- env.metadataCache.set(metadata)
        result <- env.service.getMetadata
      yield assertTrue(result == metadata)
    },
    test("syncConfiguration fetches and updates all caches") {
      for
        env <- makeEnv(clients = Map.empty, scopes = Vector.empty)
        _ <- env.clientSync.getAll.succeedsWith(testClients)
        _ <- env.scopeSync.getAll.succeedsWith(testScopes)
        _ <- env.formSync.getAll.succeedsWith(Vector.empty)
        _ <- env.themeSync.getAll.succeedsWith(Vector.empty)
        _ <- env.localeSync.getAll.succeedsWith(Locales(Vector.empty, "en"))
        _ <- env.otpTemplateSync.getAll.succeedsWith(Vector.empty)
        _ <- env.challengeSettingsSync.getAll.succeedsWith(Vector.empty)
        _ <- env.systemSettingsSync.getAll.succeedsWith(SystemSettingsRecord.default)
        _ <- env.metadataSync.getAll.succeedsWith(Json.Obj("a" -> Json.Num(1)))

        _ <- env.service.syncConfiguration

        clients <- env.service.find(clientId1)
        scopes <- env.service.getScopes
        metadata <- env.service.getMetadata
      yield assertTrue(
        clients.isDefined,
        scopes == testScopes,
        metadata == Json.Obj("a" -> Json.Num(1))
      )
    },
    test("getAcrVocabulary returns vocabulary from challenge settings") {
      val vocabulary = Map("factor1" -> List(PassedAuthFactor.password))
      val settings = ChallengeSettingsRecord(
        tenantId = TenantId("default"),
        allowedPrefixes = Nil,
        submissionLimits = SubmissionLimits.empty,
        otpLength = 6,
        otpResendAfter = 60,
        passkeySettings = PasskeySettings("rp", "RP", Nil, "required"),
        authConversationTtlSeconds = 900,
        sessionTtlSeconds = 86400,
        sessionIdleTtlSeconds = None,
        ipHeader = "X-Real-IP",
        acrVocabulary = Some(vocabulary),
      )
      for
        env <- makeEnv(challengeSettings = Vector(settings))
        result <- env.service.getAcrVocabulary(clientId1)
      yield assertTrue(result == Map(Acr("factor1") -> NonEmptyList(PassedAuthFactor.password)))
    },


  )
