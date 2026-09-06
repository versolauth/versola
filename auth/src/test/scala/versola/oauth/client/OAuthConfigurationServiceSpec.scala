package versola.oauth.client

import versola.oauth.client.model.*
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.metadata.MetadataSyncClient
import versola.util.*
import zio.*
import zio.durationInt
import zio.json.ast.Json
import zio.prelude.{NonEmptyList, NonEmptySet}
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
    clientName = Map("en" -> "Private"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    secret = Some(testSecret),
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
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    tenantId = tenantId,
    clientName = Map("en" -> "Public"),
    redirectUris = NonEmptySet("https://public.example.com/callback"),
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

  val testScopes = Vector(ScopeRecord(ScopeToken("read"), Map("en" -> "Read access"), Vector.empty))
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
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
    postLogoutRedirectUris = List.empty,
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
      metadata: Json.Obj = Json.Obj(),
      resources: Vector[ResourceRecord] = Vector.empty,
      authResourceSecrets: List[Secret] = Nil,
      authorizationDetailTypes: Vector[AuthorizationDetailTypeRecord] = Vector.empty,
  ) =
    for
      clientRef <- Ref.make(clients)
      scopeRef <- Ref.make(scopes)
      formRef <- Ref.make(forms)
      themeRef <- Ref.make(themes)
      localeRef <- Ref.make(locales)
      otpRef <- Ref.make(otpTemplates)
      challengeRef <- Ref.make(challengeSettingsVec)
      sysRef <- Ref.make(sysSettings)
      metadataRef <- Ref.make(metadata)
      resourceRef <- Ref.make(ResourceSyncClient.SyncResult(resources, authResourceSecrets))
      authDetailTypeRef <- Ref.make(authorizationDetailTypes)
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
      metadataCache = ReloadingCache(metadataRef),
      metadataRepository = stub[MetadataSyncClient],
      resourceCache = ReloadingCache(resourceRef),
      resourceRepository = stub[ResourceSyncClient],
      authorizationDetailTypeCache = ReloadingCache(authDetailTypeRef),
      authorizationDetailTypeRepository = stub[AuthorizationDetailTypeSyncClient],
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
    test("getOtpSettings returns default settings for unknown client") {
      for
        env <- makeEnv()
        result <- env.getOtpSettings(ClientId("missing"))
      yield assertTrue(result == OtpSettings.default)
    },
    test("getSubmissionLimits returns limits from challenge settings for known client") {
      val limits = SubmissionLimits(banDurationSeconds = 30)
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(submissionLimits = limits)))
        result <- env.getSubmissionLimits(clientId1)
      yield assertTrue(result == limits)
    },
    test("getSubmissionLimits returns empty for unknown client") {
      for
        env <- makeEnv()
        result <- env.getSubmissionLimits(ClientId("missing"))
      yield assertTrue(result == SubmissionLimits.empty)
    },
    test("getIpHeader returns header from challenge settings for known client") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(ipHeader = "X-Custom-IP")))
        result <- env.getIpHeader(clientId1)
      yield assertTrue(result == "X-Custom-IP")
    },
    test("getIpHeader returns default header for unknown client") {
      for
        env <- makeEnv()
        result <- env.getIpHeader(ClientId("missing"))
      yield assertTrue(result == "X-Real-IP")
    },
    test("getPasskeySettings returns settings for known client") {
      for
        env <- makeEnv()
        result <- env.getPasskeySettings(clientId1)
      yield assertTrue(result.contains(challengeSettings.passkeySettings))
    },
    test("getPasskeySettings returns None for unknown client") {
      for
        env <- makeEnv()
        result <- env.getPasskeySettings(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("getAuthConversationTtl returns duration from challenge settings") {
      for
        env <- makeEnv()
        result <- env.getAuthConversationTtl(clientId1)
      yield assertTrue(result == Duration.fromSeconds(900))
    },
    test("getAuthConversationTtl returns default when client not found") {
      for
        env <- makeEnv()
        result <- env.getAuthConversationTtl(ClientId("missing"))
      yield assertTrue(result == Duration.fromSeconds(900))
    },
    test("getSessionTtl returns duration from challenge settings") {
      for
        env <- makeEnv()
        result <- env.getSessionTtl(clientId1)
      yield assertTrue(result == Duration.fromSeconds(86400))
    },
    test("getSessionTtl returns default when client not found") {
      for
        env <- makeEnv()
        result <- env.getSessionTtl(ClientId("missing"))
      yield assertTrue(result == Duration.fromSeconds(86400))
    },
    test("getSessionIdleTtl returns Some when set") {
      for
        env <- makeEnv()
        result <- env.getSessionIdleTtl(clientId1)
      yield assertTrue(result.contains(Duration.fromSeconds(3600)))
    },
    test("getSessionIdleTtl returns None when client not found") {
      for
        env <- makeEnv()
        result <- env.getSessionIdleTtl(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("getUserAgentTtl returns duration from challenge settings") {
      for
        env <- makeEnv()
        result <- env.getUserAgentTtl(clientId1)
      yield assertTrue(result == Duration.fromSeconds(15552000))
    },
    test("getUserAgentTtl returns default when client not found") {
      for
        env <- makeEnv()
        result <- env.getUserAgentTtl(ClientId("unknown"))
      yield assertTrue(result == OAuthConfigurationService.DefaultUserAgentTtl)
    },
    test("getMetadata returns stored metadata without deriving authorization detail types") {
      val stored = Json.Obj(
        "authorization_details_types_supported" -> Json.Arr(Json.Str("stored")),
      )
      val registered = AuthorizationDetailTypeRecord(
        tenantId,
        AuthorizationDetailType("payment"),
        Json.Obj(),
      )

      for
        env <- makeEnv(metadata = stored, authorizationDetailTypes = Vector(registered))
        result <- env.getMetadata
      yield assertTrue(result == stored)
    },
    test("findByTenant returns only the clients of that tenant") {
      val otherTenant = privateClient.copy(id = ClientId("other"), tenantId = TenantId("other"))
      for
        env <- makeEnv(clients = Map(clientId1 -> privateClient, ClientId("other") -> otherTenant))
        mine <- env.findByTenant(tenantId)
        theirs <- env.findByTenant(TenantId("other"))
        unknown <- env.findByTenant(TenantId("nobody"))
      yield assertTrue(
        mine == Vector(privateClient),
        theirs == Vector(otherTenant),
        unknown.isEmpty,
      )
    },
    test("getIdentityProviderLogo reads the system settings value") {
      for
        withLogo <- makeEnv(sysSettings = systemSettings.copy(identityProviderLogo = Some("logo.svg")))
        withoutLogo <- makeEnv()
        set <- withLogo.getIdentityProviderLogo
        unset <- withoutLogo.getIdentityProviderLogo
      yield assertTrue(set == Some("logo.svg"), unset == systemSettings.identityProviderLogo)
    },
    test("getPostLogoutRedirectUris decodes the tenant's configured URLs") {
      val settings = challengeSettings.copy(
        postLogoutRedirectUris = List("https://app.example/bye", "not a url"),
      )
      for
        env <- makeEnv(challengeSettingsVec = Vector(settings))
        uris <- env.getPostLogoutRedirectUris(tenantId)
        unknown <- env.getPostLogoutRedirectUris(TenantId("nobody"))
      yield assertTrue(
        uris.map(_.encode) == List("https://app.example/bye"),
        unknown.isEmpty,
      )
    },
    suite("getAcrVocabulary")(
      test("returns empty map for unknown client") {
        for
          env <- makeEnv()
          result <- env.getAcrVocabulary(ClientId("missing"))
        yield assertTrue(result.isEmpty)
      },
      test("returns empty map when challenge settings carry no vocabulary") {
        for
          env <- makeEnv()
          result <- env.getAcrVocabulary(clientId1)
        yield assertTrue(result.isEmpty)
      },
      test("converts the configured vocabulary to non-empty factor lists") {
        val vocabulary = Map("urn:mfa" -> List(PassedAuthFactor.otp, PassedAuthFactor.password))
        for
          env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(acrVocabulary = Some(vocabulary))))
          result <- env.getAcrVocabulary(clientId1)
        yield assertTrue(result == Map(Acr("urn:mfa") -> NonEmptyList(PassedAuthFactor.otp, PassedAuthFactor.password)))
      },
    ),
    suite("getPasswordTemplate")(
      test("fails when no global template matches the purpose and channel") {
        for
          env <- makeEnv()
          result <- env.getPasswordTemplate(OtpTemplateChannel.email, None).either
        yield assertTrue(result.left.map(_.getMessage) == Left("No global password template configured"))
      },
      test("falls back to any localization when uiLocales and default locale don't match") {
        val template = OtpTemplateRecord(
          id = "password-template",
          tenantId = tenantId,
          localizations = Map("fr" -> "Corps FR"),
          purpose = OtpTemplatePurpose.password,
          channel = OtpTemplateChannel.email,
        )
        for
          env <- makeEnv(otpTemplates = Vector(template))
          result <- env.getPasswordTemplate(OtpTemplateChannel.email, Some(List("de")))
        yield assertTrue(result == OtpTemplate("Corps FR"))
      },
      test("fails when the matching template has no localizations") {
        val template = OtpTemplateRecord(
          id = "password-template",
          tenantId = tenantId,
          localizations = Map.empty,
          purpose = OtpTemplatePurpose.password,
          channel = OtpTemplateChannel.sms,
        )
        for
          env <- makeEnv(otpTemplates = Vector(template))
          result <- env.getPasswordTemplate(OtpTemplateChannel.sms, None).either
        yield assertTrue(result.left.map(_.getMessage) == Left("Password template has no localizations"))
      },
    ),
    suite("resources")(
      test("findResource matches on tenant and resource URI") {
        for
          env <- makeEnv(resources = Vector(resourceRecord))
          found <- env.findResource(tenantId, ResourceUri("https://api.example"))
          otherTenant <- env.findResource(TenantId("other"), ResourceUri("https://api.example"))
          unknown <- env.findResource(tenantId, ResourceUri("https://other.example"))
        yield assertTrue(
          found == Some(resourceRecord),
          otherTenant.isEmpty,
          unknown.isEmpty,
        )
      },
      test("findResourceById matches on tenant and resource id") {
        for
          env <- makeEnv(resources = Vector(resourceRecord))
          found <- env.findResourceById(tenantId, ResourceId("api"))
          otherTenant <- env.findResourceById(TenantId("other"), ResourceId("api"))
          unknown <- env.findResourceById(tenantId, ResourceId("missing"))
        yield assertTrue(
          found == Some(resourceRecord),
          otherTenant.isEmpty,
          unknown.isEmpty,
        )
      },
      test("getResourcesForClient returns only resources the client is an audience of") {
        val unrelated = resourceRecord.copy(
          resourceId = ResourceId("other"),
          resource = ResourceUri("https://other.example"),
          audience = List(ClientId("someone-else")),
        )
        for
          env <- makeEnv(resources = Vector(resourceRecord, unrelated))
          mine <- env.getResourcesForClient(tenantId, clientId1)
          none <- env.getResourcesForClient(tenantId, ClientId("nobody"))
        yield assertTrue(mine == List(resourceRecord), none.isEmpty)
      },
      test("accountResourceSecrets exposes the secrets carried on the sync result") {
        val secrets = List(Secret(Array.fill(16)(7.toByte)))
        for
          env <- makeEnv(authResourceSecrets = secrets)
          empty <- makeEnv()
          loaded <- env.accountResourceSecrets
          none <- empty.accountResourceSecrets
        yield assertTrue(loaded.map(_.toSeq) == secrets.map(_.toSeq), none.isEmpty)
      },
    ),
    suite("syncConfiguration")(
      test("refreshes every cache from its repository") {
        val newClients = Map(clientId1 -> privateClient)
        val newScopes = Vector(ScopeRecord(ScopeToken("write"), Map("en" -> "Write access"), Vector.empty))
        val newForms = Vector(testForm.copy(id = "form-2"))
        val newThemes = Vector(testTheme.copy(id = "dark"))
        val newLocales = Locales(Vector(LocaleRecord("fr", "Français")), "fr")
        val newOtpTemplates = Vector(
          OtpTemplateRecord("tmpl-2", tenantId, Map("en" -> "code"), OtpTemplatePurpose.otp, OtpTemplateChannel.sms),
        )
        val newChallengeSettings = Vector(challengeSettings.copy(ipHeader = "X-Forwarded-For"))
        val newSystemSettings = systemSettings.copy(identityProviderLogo = Some("new-logo.svg"))
        val newMetadata = Json.Obj("issuer" -> Json.Str("https://idp.example"))
        val newResources = Vector(resourceRecord)
        val newSecrets = List(Secret(Array.fill(16)(3.toByte)))
        val newAuthorizationDetailTypes = Vector(
          AuthorizationDetailTypeRecord(tenantId, AuthorizationDetailType("payment"), Json.Obj()),
        )

        // `makeEnv` builds its stubs inline as constructor args, typed away to their trait
        // (Impl's field types), so `.succeedsWith` isn't reachable through `env.xRepository`
        // -- keep typed handles to the stubs here instead and build Impl directly.
        val clientRepository = stub[OAuthClientSyncClient]
        val scopeRepository = stub[OAuthScopeSyncClient]
        val formRepository = stub[FormSyncClient]
        val themeRepository = stub[ThemeSyncClient]
        val localeRepository = stub[LocaleSyncClient]
        val otpTemplateRepository = stub[OtpTemplateSyncClient]
        val challengeSettingsRepository = stub[ChallengeSettingsSyncClient]
        val systemSettingsRepository = stub[SystemSettingsSyncClient]
        val metadataRepository = stub[MetadataSyncClient]
        val resourceRepository = stub[ResourceSyncClient]
        val authorizationDetailTypeRepository = stub[AuthorizationDetailTypeSyncClient]

        for
          clientRef <- Ref.make(Map(clientId1 -> privateClient, publicClientId -> publicClient))
          scopeRef <- Ref.make(testScopes)
          formRef <- Ref.make(Vector(testForm))
          themeRef <- Ref.make(Vector(testTheme))
          localeRef <- Ref.make(testLocales)
          otpRef <- Ref.make(Vector.empty[OtpTemplateRecord])
          challengeRef <- Ref.make(Vector(challengeSettings))
          sysRef <- Ref.make(systemSettings)
          metadataRef <- Ref.make(Json.Obj())
          resourceRef <- Ref.make(ResourceSyncClient.SyncResult(Vector.empty, Nil))
          authDetailTypeRef <- Ref.make(Vector.empty[AuthorizationDetailTypeRecord])
          env = OAuthConfigurationService.Impl(
            clientCache = ReloadingCache(clientRef),
            clientRepository = clientRepository,
            scopeCache = ReloadingCache(scopeRef),
            scopeRepository = scopeRepository,
            formCache = ReloadingCache(formRef),
            formRepository = formRepository,
            themeCache = ReloadingCache(themeRef),
            themeRepository = themeRepository,
            localeCache = ReloadingCache(localeRef),
            localeRepository = localeRepository,
            otpTemplateCache = ReloadingCache(otpRef),
            otpTemplateRepository = otpTemplateRepository,
            challengeSettingsCache = ReloadingCache(challengeRef),
            challengeSettingsRepository = challengeSettingsRepository,
            systemSettingsCache = ReloadingCache(sysRef),
            systemSettingsRepository = systemSettingsRepository,
            metadataCache = ReloadingCache(metadataRef),
            metadataRepository = metadataRepository,
            resourceCache = ReloadingCache(resourceRef),
            resourceRepository = resourceRepository,
            authorizationDetailTypeCache = ReloadingCache(authDetailTypeRef),
            authorizationDetailTypeRepository = authorizationDetailTypeRepository,
          )
          _ <- clientRepository.getAll.succeedsWith(newClients)
          _ <- scopeRepository.getAll.succeedsWith(newScopes)
          _ <- formRepository.getAll.succeedsWith(newForms)
          _ <- themeRepository.getAll.succeedsWith(newThemes)
          _ <- localeRepository.getAll.succeedsWith(newLocales)
          _ <- otpTemplateRepository.getAll.succeedsWith(newOtpTemplates)
          _ <- challengeSettingsRepository.getAll.succeedsWith(newChallengeSettings)
          _ <- systemSettingsRepository.getAll.succeedsWith(newSystemSettings)
          _ <- metadataRepository.getAll.succeedsWith(newMetadata)
          _ <- resourceRepository.getAll.succeedsWith(ResourceSyncClient.SyncResult(newResources, newSecrets))
          _ <- authorizationDetailTypeRepository.getAll.succeedsWith(newAuthorizationDetailTypes)
          _ <- env.syncConfiguration
          clients <- env.clientCache.get
          scopes <- env.scopeCache.get
          forms <- env.formCache.get
          themes <- env.themeCache.get
          locales <- env.localeCache.get
          otpTemplates <- env.otpTemplateCache.get
          challengeSettingsResult <- env.challengeSettingsCache.get
          sysSettings <- env.systemSettingsCache.get
          metadata <- env.metadataCache.get
          resources <- env.resourceCache.get
          authorizationDetailTypes <- env.authorizationDetailTypeCache.get
        yield assertTrue(
          clients == newClients,
          scopes == newScopes,
          forms == newForms,
          themes == newThemes,
          locales == newLocales,
          otpTemplates == newOtpTemplates,
          challengeSettingsResult == newChallengeSettings,
          sysSettings == newSystemSettings,
          metadata == newMetadata,
          resources == ResourceSyncClient.SyncResult(newResources, newSecrets),
          authorizationDetailTypes == newAuthorizationDetailTypes,
        )
      },
    ),
  )

  private val resourceRecord = ResourceRecord(
    resourceId = ResourceId("api"),
    tenantId = tenantId,
    resource = ResourceUri("https://api.example"),
    audience = List(clientId1),
    internal = false,
  )
