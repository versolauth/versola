package versola.oauth.client

import versola.oauth.client.model.*
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.jwks.JwksSyncClient
import versola.oauth.metadata.{MetadataSyncClient, ServedMetadata}
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
    dpopBoundAccessTokens = false,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
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
    dpopBoundAccessTokens = false,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
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
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
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
      metadataRef <- Ref.make(ServedMetadata.derive(metadata))
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
      jwksRepository = stub[JwksSyncClient],
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
    test("getSubmissionLimits falls back to the recommended default when the client's tenant has no challenge settings row") {
      for
        env <- makeEnv(challengeSettingsVec = Vector.empty)
        result <- env.getSubmissionLimits(clientId1)
      yield assertTrue(result == SubmissionLimits.recommended)
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
    // RFC 9449 §8. The two negative cases matter more than the positive one: both are states
    // the server can be in before it knows anything, and answering "nonce required" from
    // either would challenge clients that have no nonce to give and no reason to expect one.
    test("requireDpopNonce returns the setting of the client's tenant") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(requireDpopNonce = true)))
        result <- env.requireDpopNonce(clientId1)
      yield assertTrue(result)
    },
    test("requireDpopNonce is false for an unknown client") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(requireDpopNonce = true)))
        result <- env.requireDpopNonce(ClientId("missing"))
      yield assertTrue(!result)
    },
    test("requireDpopNonce is false when the client's tenant has no challenge settings row") {
      for
        env <- makeEnv(challengeSettingsVec = Vector.empty)
        result <- env.requireDpopNonce(clientId1)
      yield assertTrue(!result)
    },
    test("getMtlsCertificateSource returns the header and encoding the tenant configured") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(
          mtlsCertificateHeader = Some("ssl-client-cert"),
          mtlsCertificateEncoding = Some(MtlsCertificateEncoding.urlEncodedPem),
        )))
        result <- env.getMtlsCertificateSource(clientId1)
      yield assertTrue(
        result.contains(MtlsCertificateSource("ssl-client-cert", MtlsCertificateEncoding.urlEncodedPem)),
      )
    },
    test("getMtlsCertificateSource returns None when the tenant terminates no mutual TLS") {
      for
        env <- makeEnv()
        result <- env.getMtlsCertificateSource(clientId1)
      yield assertTrue(result.isEmpty)
    },
    test("getMtlsCertificateSource returns None for a header stored without an encoding") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(
          mtlsCertificateHeader = Some("ssl-client-cert"),
          mtlsCertificateEncoding = None,
        )))
        result <- env.getMtlsCertificateSource(clientId1)
      yield assertTrue(result.isEmpty)
    },
    test("getMtlsCertificateSource returns None for unknown client") {
      for
        env <- makeEnv(challengeSettingsVec = Vector(challengeSettings.copy(
          mtlsCertificateHeader = Some("ssl-client-cert"),
          mtlsCertificateEncoding = Some(MtlsCertificateEncoding.urlEncodedPem),
        )))
        result <- env.getMtlsCertificateSource(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("getClientAssertionMaxLifetime returns the window the client's tenant configured") {
      for
        env <- makeEnv(challengeSettingsVec =
          Vector(challengeSettings.copy(clientAssertionMaxLifetimeSeconds = 120)))
        result <- env.getClientAssertionMaxLifetime(clientId1)
      yield assertTrue(result == 120.seconds)
    },
    test("getClientAssertionMaxLifetime falls back to the default without a settings row") {
      for
        env <- makeEnv(challengeSettingsVec = Vector.empty)
        tenantless <- env.getClientAssertionMaxLifetime(clientId1)
        unknown <- env.getClientAssertionMaxLifetime(ClientId("missing"))
      yield assertTrue(
        tenantless == ChallengeSettingsRecord.DefaultClientAssertionMaxLifetime,
        unknown == ChallengeSettingsRecord.DefaultClientAssertionMaxLifetime,
      )
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
      yield assertTrue(
        result.get("authorization_details_types_supported") ==
          stored.get("authorization_details_types_supported"),
      )
    },
    // JARM §4. Unlike the algorithm fields below, this one has no stored value to narrow --
    // it is entirely a fact about the synced JWKS, so `derive` is exercised directly with the
    // algorithms a caller (`OAuthConfigurationService.live`'s `metadataCacheSource`, in
    // production) read off it, rather than through `makeEnv`'s `metadata` document.
    test("getMetadata advertises the algorithms the synced JWKS actually publishes") {
      for
        env <- makeEnv()
        _ <- env.metadataCache.set(
          ServedMetadata.derive(Json.Obj(), Set(JWT.Algorithm.PS256, JWT.Algorithm.ES256)),
        )
        served <- env.getMetadata
      yield assertTrue(
        served.get("authorization_signing_alg_values_supported")
          .contains(Json.Arr(Json.Str("ES256"), Json.Str("PS256"))),
      )
    },
    test("getMetadata advertises no signing algorithm when the synced JWKS publishes none") {
      for
        env <- makeEnv()
        served <- env.getMetadata
      yield assertTrue(
        served.get("authorization_signing_alg_values_supported").contains(Json.Arr()),
      )
    },
    // RFC 9449 §5.1. The set is served and enforced off the same field, so a document that
    // never mentioned it still has to advertise what a proof will actually be held to --
    // otherwise a client has no way to discover the set short of guessing.
    test("getMetadata advertises the default DPoP algorithms when the document omits the field") {
      for
        env <- makeEnv(metadata = Json.Obj("issuer" -> Json.Str("https://idp.example")))
        served <- env.getMetadata
        enforced <- env.getDpopSigningAlgorithms
      yield assertTrue(
        served.get(Dpop.Algorithm.MetadataField)
          .contains(Json.Arr(Json.Str("ES256"), Json.Str("PS256"))),
        enforced == Dpop.Algorithm.Default,
        served.get("issuer").contains(Json.Str("https://idp.example")),
      )
    },
    test("getDpopSigningAlgorithms narrows to what the document names") {
      for
        env <- makeEnv(metadata = Json.Obj(
          Dpop.Algorithm.MetadataField -> Json.Arr(Json.Str("RS256")),
        ))
        served <- env.getMetadata
        enforced <- env.getDpopSigningAlgorithms
      yield assertTrue(
        enforced == Set(Dpop.Algorithm.RS256),
        served.get(Dpop.Algorithm.MetadataField).contains(Json.Arr(Json.Str("RS256"))),
      )
    },
    // An algorithm no verifier here implements would otherwise be advertised and then refused
    // on arrival, which is worse than not offering it: the client picks a key it cannot use.
    test("getMetadata drops an algorithm it has no verifier for instead of advertising it") {
      for
        env <- makeEnv(metadata = Json.Obj(
          Dpop.Algorithm.MetadataField -> Json.Arr(Json.Str("ES256"), Json.Str("EdDSA")),
        ))
        served <- env.getMetadata
        enforced <- env.getDpopSigningAlgorithms
      yield assertTrue(
        enforced == Set(Dpop.Algorithm.ES256),
        served.get(Dpop.Algorithm.MetadataField).contains(Json.Arr(Json.Str("ES256"))),
      )
    },
    // Nothing recognizable is not the same as nothing said: the operator excluded every
    // algorithm this build has, and substituting the defaults would re-admit the keys they
    // went out of their way to exclude. DPoP goes unusable and says so in the document.
    test("getDpopSigningAlgorithms leaves the set empty when nothing the document names exists here") {
      for
        env <- makeEnv(metadata = Json.Obj(
          Dpop.Algorithm.MetadataField -> Json.Arr(Json.Str("EdDSA")),
        ))
        served <- env.getMetadata
        enforced <- env.getDpopSigningAlgorithms
      yield assertTrue(
        enforced.isEmpty,
        served.get(Dpop.Algorithm.MetadataField).contains(Json.Arr()),
      )
    },
    // A field too malformed to read an intent off is not an exclusion -- it is an operator
    // error, and guessing at it either way is a guess. The default is the one that keeps the
    // advertised and enforced sets equal.
    test("getDpopSigningAlgorithms falls back to the default when the field is malformed") {
      for
        env <- makeEnv(metadata = Json.Obj(Dpop.Algorithm.MetadataField -> Json.Str("ES256")))
        enforced <- env.getDpopSigningAlgorithms
      yield assertTrue(enforced == Dpop.Algorithm.Default)
    },
    // RFC 8414 §2, and the same argument as the DPoP field above: an assertion is held to the
    // set the document advertises, so a document that never named it still has to say what
    // `private_key_jwt` will accept.
    test("getMetadata advertises the default assertion algorithms when the document omits the field") {
      for
        env <- makeEnv(metadata = Json.Obj("issuer" -> Json.Str("https://idp.example")))
        served <- env.getMetadata
        enforced <- env.getClientAssertionSigningAlgorithms
      yield assertTrue(
        served.get(ClientAssertion.Algorithm.MetadataField)
          .contains(Json.Arr(Json.Str("ES256"), Json.Str("PS256"))),
        enforced == ClientAssertion.Algorithm.Default,
      )
    },
    test("getClientAssertionSigningAlgorithms narrows to what the document names") {
      for
        env <- makeEnv(metadata = Json.Obj(
          ClientAssertion.Algorithm.MetadataField -> Json.Arr(Json.Str("RS256")),
        ))
        served <- env.getMetadata
        enforced <- env.getClientAssertionSigningAlgorithms
      yield assertTrue(
        enforced == Set(ClientAssertion.Algorithm.RS256),
        served.get(ClientAssertion.Algorithm.MetadataField).contains(Json.Arr(Json.Str("RS256"))),
      )
    },
    // RFC 9101 §4: a request object is held to the set the document advertises, on the same
    // argument as the DPoP and assertion fields above.
    test("getMetadata advertises the default request object algorithms when the document omits the field") {
      for
        env <- makeEnv(metadata = Json.Obj("issuer" -> Json.Str("https://idp.example")))
        served <- env.getMetadata
        enforced <- env.getRequestObjectSigningAlgorithms
      yield assertTrue(
        served.get(RequestObject.Algorithm.MetadataField)
          .contains(Json.Arr(Json.Str("ES256"), Json.Str("PS256"))),
        enforced == RequestObject.Algorithm.Default,
      )
    },
    test("getRequestObjectSigningAlgorithms narrows to what the document names") {
      for
        env <- makeEnv(metadata = Json.Obj(
          RequestObject.Algorithm.MetadataField -> Json.Arr(Json.Str("RS256")),
        ))
        served <- env.getMetadata
        enforced <- env.getRequestObjectSigningAlgorithms
      yield assertTrue(
        enforced == Set(ClientAssertion.Algorithm.RS256),
        served.get(RequestObject.Algorithm.MetadataField).contains(Json.Arr(Json.Str("RS256"))),
      )
    },
    // The `request` parameter is accepted because AuthorizeRequestParser resolves one, and a
    // `request_uri` here is always a pushed request rather than a document to fetch. Neither
    // is a claim a stored document gets to contradict.
    test("getMetadata states the request parameter support this build actually has") {
      for
        env <- makeEnv(metadata = Json.Obj(
          "request_parameter_supported" -> Json.Bool(false),
          "request_uri_parameter_supported" -> Json.Bool(true),
        ))
        served <- env.getMetadata
      yield assertTrue(
        served.get("request_parameter_supported").contains(Json.Bool(true)),
        served.get("request_uri_parameter_supported").contains(Json.Bool(false)),
      )
    },
    // private_key_jwt is accepted unconditionally -- see ClientAuthentication -- so a stored
    // document that never named it, or a custom one that dropped it, must not leave this
    // server advertising no way to use a method it actually accepts.
    test("getMetadata advertises private_key_jwt even when the stored document omits it") {
      for
        env <- makeEnv(metadata = Json.Obj(
          "token_endpoint_auth_methods_supported" -> Json.Arr(Json.Str("client_secret_basic")),
        ))
        served <- env.getMetadata
      yield assertTrue(
        served.get("token_endpoint_auth_methods_supported").contains(
          Json.Arr(Json.Str("client_secret_basic"), Json.Str("private_key_jwt")),
        ),
      )
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
        requireDpopNonce = false,
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
        val jwksRepository = stub[JwksSyncClient]

        for
          clientRef <- Ref.make(Map(clientId1 -> privateClient, publicClientId -> publicClient))
          scopeRef <- Ref.make(testScopes)
          formRef <- Ref.make(Vector(testForm))
          themeRef <- Ref.make(Vector(testTheme))
          localeRef <- Ref.make(testLocales)
          otpRef <- Ref.make(Vector.empty[OtpTemplateRecord])
          challengeRef <- Ref.make(Vector(challengeSettings))
          sysRef <- Ref.make(systemSettings)
          metadataRef <- Ref.make(ServedMetadata.derive(Json.Obj()))
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
            jwksRepository = jwksRepository,
          )
          _ <- jwksRepository.getPublicKeys.succeedsWith(JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr())))
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
          metadata <- env.metadataCache.get.map(_.document)
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
          // `syncConfiguration` derives `ServedMetadata` the same way the cache source does
          // (see `OAuthConfigurationService.live`'s `metadataCacheSource`), so the served
          // document carries the normalized DPoP field rather than `newMetadata` verbatim.
          metadata == ServedMetadata.derive(newMetadata).document,
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
