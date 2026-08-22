package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.conversation.model.*
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, SessionCookie, State, UserAgentData}
import versola.oauth.session.model.{PublicSessionId, SessionId, UserAgentDetails, UserAgentId}
import versola.user.model.UserId
import versola.util.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object ConversationRenderServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("tenant-1")
  private val clientId = ClientId("client-1")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val testUserAgentId = UserAgentId(UUID.randomUUID())

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = Map("en" -> "Test Client"),
    redirectUris = zio.prelude.NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "custom-theme",
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

  private val theme = ThemeRecord("custom-theme", ".body { color: red; }", Some(tenantId))
  private val defaultTheme = ThemeRecord("default", ".body { color: black; }", None)

  private val locales = Locales(default = "en", locales = Vector(LocaleRecord("en", "English")))

  private val formRecord = FormRecord(
    id = "credential",
    version = 1,
    active = true,
    style = "#form { margin: 0; }",
    jsSource = None,
    jsCompiled = Some("console.log('init');"),
    localizations = Map(
      "en" -> Map("page_title" -> "Sign In Test", "login_label" -> "Email"),
      "fr" -> Map("page_title" -> "Connexion", "login_label" -> "Email")
    ),
    properties = Vector.empty
  )

  private val conversationRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("read")),
    codeChallenge = CodeChallenge("a" * 43),
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = Some(State("test-state")),
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.email), true, false),
    requestedClaims = None,
    uiLocales = Some(List("en")),
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    registrationFlow = None,
    registrationStep = None,
    userAgent = None,
    userAgentCookie = None,
        version = 1,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
    authorizationDetails = None,
    grantedScope = None,
    promptConsent = false,
  )

  private val consentScope = ScopeRecord(
    scope = ScopeToken("profile"),
    description = Map("en" -> "Profile", "fr" -> "Profil"),
    claims = Vector(
      ClaimRecord(Claim("email"), Map("en" -> "Email address", "fr" -> "Adresse e-mail")),
    ),
  )

  private val consentRecord = conversationRecord.copy(
    step = ConversationStep.Consent(Set(ScopeToken("profile")), allowPartial = false),
  )

  private val consentLocales = Locales(
    default = "en",
    locales = Vector(LocaleRecord("en", "English"), LocaleRecord("fr", "French")),
  )

  class Env:
    val configuration = stub[OAuthConfigurationService]
    val jwksService   = stub[JwksService]
    val service       = ConversationRenderService.Impl(TestEnvConfig.coreConfig, configuration, jwksService)

  def spec = suite("ConversationRenderService")(
    suite("renderStep")(
      test("renders HTML with theme and form") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderStep(conversationRecord, None)
          body <- response.body.asString
          faviconSvg = """<link rel="icon" href="data:image/svg\+xml;base64,([^"]+)">""".r
            .findFirstMatchIn(body)
            .map(matchValue => String(java.util.Base64.getDecoder.decode(matchValue.group(1)), StandardCharsets.UTF_8))
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html)) &&
          assertTrue(body.contains("Sign In Test")) &&
          assertTrue(body.contains(".body { color: red; }")) &&
          assertTrue(body.contains("#form { margin: 0; }")) &&
          assertTrue(body.contains("window.__VERSOLA_FORM__ =")) &&
          assertTrue(body.contains("console.log('init');")) &&
          assertTrue(body.contains("<link rel=\"icon\" href=\"data:image/svg+xml;base64,")) &&
          assertTrue(faviconSvg.exists(_.contains("<rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#faf9f7\"/>")))
      },
      test("renders the identity provider logo as the favicon and safely in the form config") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(Some("https://acme.test/logo.svg?a=1&b=2"))
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderStep(conversationRecord, None)
          body     <- response.body.asString
        yield assertTrue(body.contains("""<link rel="icon" href="https://acme.test/logo.svg?a=1&amp;b=2">""")) &&
          assertTrue(body.contains(""""logo":"https://acme.test/logo.svg?a=1\u0026b=2""""))
        },
        test("escapes form config JSON so a logo cannot terminate its script element") {
          val env = Env()
          val maliciousLogo = "https://acme.test/logo.svg?</script><script>alert(1)</script>"
          for
            _ <- env.configuration.find.succeedsWith(Some(clientRecord))
            _ <- env.configuration.getTheme.succeedsWith(Some(theme))
            _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
            _ <- env.configuration.getLocales.succeedsWith(locales)
            _ <- env.configuration.getIdentityProviderLogo.succeedsWith(Some(maliciousLogo))
            _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
            _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
            response <- env.service.renderStep(conversationRecord, None)
            body     <- response.body.asString
          yield assertTrue(
            !body.contains("</script><script>alert(1)</script>"),
            body.contains("\\u003c/script\\u003e\\u003cscript\\u003ealert(1)"),
          )
      },
      test("returns 304 if ETag matches") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          firstResponse <- env.service.renderStep(conversationRecord, None)
          etag = firstResponse.headers.get("ETag").getOrElse("")
          secondResponse <- env.service.renderStep(conversationRecord, Some(etag))
        yield
          assertTrue(secondResponse.status == Status.NotModified)
      },
      test("uses default theme if client theme is missing") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(None)
          _ <- env.configuration.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderStep(conversationRecord, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains(".body { color: black; }"))
      },
      test("renders 404 if form not found") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(None)
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderStep(conversationRecord, None)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.NotFound) &&
          assertTrue(body.contains("Page not found"))
      },
      test("renders Otp step with resend timer") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val sentAt = now.minusSeconds(30)
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, Some(sentAt))
        val record = conversationRecord.copy(step = otpStep)
        val otpSettings = OtpSettings(6, 60)

        for
          _ <- TestClock.setTime(now)
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord.copy(id = "otp")))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getOtpSettings.succeedsWith(otpSettings)
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("\"resendAfter\":30"))
      },
      test("renders a masked destination for the OTP recipient") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, None)
        val record = conversationRecord.copy(step = otpStep, credential = Some(Left(Email("john@example.com"))))
        val otpSettings = OtpSettings(6, 60)

        for
          _ <- TestClock.setTime(now)
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord.copy(id = "otp")))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getOtpSettings.succeedsWith(otpSettings)
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("\"destination\":\"j•••n@example.com\""))
      },
      test("omits the destination when no credential is on the conversation") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, None)
        val record = conversationRecord.copy(step = otpStep, credential = None)
        val otpSettings = OtpSettings(6, 60)

        for
          _ <- TestClock.setTime(now)
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord.copy(id = "otp")))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getOtpSettings.succeedsWith(otpSettings)
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(!body.contains("\"destination\""))
      },
      test("includes all localized scope and claim labels in the consent payload") {
        val env = Env()
        val consentForm = formRecord.copy(
          id = "consent",
          localizations = Map("en" -> Map("page_title" -> "Authorize"), "fr" -> Map("page_title" -> "Autoriser")),
        )

        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(consentForm))
          _ <- env.configuration.getLocales.succeedsWith(consentLocales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getScopes.succeedsWith(Vector(consentScope))
          response <- env.service.renderStep(consentRecord.copy(userEmail = Some(Email("john@example.com"))), None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("descriptionLocalizations")) &&
          assertTrue(body.contains("Profile")) &&
          assertTrue(body.contains("Profil")) &&
          assertTrue(body.contains("claimLocalizations")) &&
          assertTrue(body.contains("Email address")) &&
          assertTrue(body.contains("Adresse e-mail"))
      },
        test("uses the identity provider logo as consent favicon but omits it from the consent config") {
          val env = Env()
          val consentForm = formRecord.copy(id = "consent")
          for
            _ <- env.configuration.find.succeedsWith(Some(clientRecord))
            _ <- env.configuration.getTheme.succeedsWith(Some(theme))
            _ <- env.configuration.getForm.succeedsWith(Some(consentForm))
            _ <- env.configuration.getLocales.succeedsWith(locales)
            _ <- env.configuration.getIdentityProviderLogo.succeedsWith(Some("https://acme.test/logo.svg"))
            _ <- env.configuration.getScopes.succeedsWith(Vector(consentScope))
            response <- env.service.renderStep(consentRecord, None)
            body     <- response.body.asString
          yield assertTrue(
            body.contains("""<link rel="icon" href="https://acme.test/logo.svg">"""),
            !body.contains("\"logo\""),
          )
        },
      test("puts the openid scope first in the consent payload") {
        val env = Env()
        val consentForm = formRecord.copy(
          id = "consent",
          localizations = Map("en" -> Map("page_title" -> "Authorize")),
        )
        val scopeRecords = Vector(
          ScopeRecord(
            scope = ScopeToken("email"),
            description = Map("en" -> "Email access"),
            claims = Vector.empty,
          ),
          ScopeRecord(
            scope = ScopeToken("openid"),
            description = Map("en" -> "OpenID Connect authentication"),
            claims = Vector.empty,
          ),
          ScopeRecord(
            scope = ScopeToken("offline_access"),
            description = Map("en" -> "Long-term access"),
            claims = Vector.empty,
          ),
        )
        val record = consentRecord.copy(
          step = ConversationStep.Consent(
            Set(ScopeToken("email"), ScopeToken("openid"), ScopeToken("offline_access")),
            allowPartial = false,
          ),
        )

        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(consentForm))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getScopes.succeedsWith(scopeRecords)
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
          openidIndex = body.indexOf("\"scope\":\"openid\"")
          emailIndex = body.indexOf("\"scope\":\"email\"")
          offlineIndex = body.indexOf("\"scope\":\"offline_access\"")
        yield
          assertTrue(openidIndex >= 0) &&
            assertTrue(openidIndex < emailIndex) &&
            assertTrue(openidIndex < offlineIndex)
      },
      test("includes only claims that are available for the authenticated user") {
        val env = Env()
        val consentForm = formRecord.copy(
          id = "consent",
          localizations = Map("en" -> Map("page_title" -> "Authorize")),
        )
        val scope = consentScope.copy(
          claims = Vector(
            ClaimRecord(Claim("email"), Map("en" -> "Email address")),
            ClaimRecord(Claim("email_verified"), Map("en" -> "Email verification status")),
          ),
        )
        val record = consentRecord.copy(userEmail = Some(Email("john@example.com")))

        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(consentForm))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getScopes.succeedsWith(Vector(scope))
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("Email address")) &&
          assertTrue(!body.contains("Email verification status"))
      },
      test("treats localized user claims as available and ignores empty values") {
        val env = Env()
        val consentForm = formRecord.copy(
          id = "consent",
          localizations = Map("en" -> Map("page_title" -> "Authorize")),
        )
        val scope = consentScope.copy(
          claims = Vector(
            ClaimRecord(Claim("name"), Map("en" -> "Name")),
            ClaimRecord(Claim("email_verified"), Map("en" -> "Email verification status")),
          ),
        )
        val record = consentRecord.copy(
          userClaims = Some(Json.Obj("name#en" -> Json.Str("John Doe"), "email_verified" -> Json.Null)),
        )

        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(consentForm))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getScopes.succeedsWith(Vector(scope))
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("Name")) &&
          assertTrue(!body.contains("Email verification status"))
      },
    ),
    suite("renderExpired")(
      test("renders the expired page with an OAuth error return URI") {
        val env = Env()
        val expiredForm = formRecord.copy(
          id = "conversation-expired",
          localizations = Map("en" -> Map("page_title" -> "Conversation expired", "title" -> "Expired")),
        )
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(expiredForm))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(Some("https://acme.test/logo.svg"))
          response <- env.service.renderExpired(clientId, redirectUri.encode, Some("test-state"))
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(body.contains("conversation-expired")) &&
          assertTrue(body.contains("error=login_required")) &&
          assertTrue(body.contains("state=test-state")) &&
          assertTrue(body.contains("""<link rel="icon" href="https://acme.test/logo.svg">""")) &&
          assertTrue(!body.contains("\"logo\""))
      },
    ),
    suite("renderServiceUnavailable")(
      test("renders the unavailable page with an OAuth error return URI") {
        val env = Env()
        val unavailableForm = formRecord.copy(
          id = "service-unavailable",
          localizations = Map("en" -> Map("page_title" -> "Unavailable", "title" -> "Unavailable")),
        )
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(unavailableForm))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          response <- env.service.renderServiceUnavailable(clientId, redirectUri.encode, Some("test-state"))
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(body.contains("service-unavailable")) &&
          assertTrue(body.contains("error=temporarily_unavailable")) &&
          assertTrue(body.contains("state=test-state"))
      },
    ),
    suite("renderSubmit")(
      test("redirects to /challenge on RenderStep") {
        val env = Env()
        val step = ConversationStep.Password(0, None, 0, false)
        for
          response <- env.service.renderSubmit(ConversationResult.RenderStep(step), conversationRecord)
        yield
          assertTrue(response.status == Status.SeeOther) &&
          assertTrue(response.header(Header.Location).exists(_.url.path.encode == "/challenge"))
      },
      test("redirects to /challenge on BadRequest") {
        val env = Env()
        for
          response <- env.service.renderSubmit(ConversationResult.BadRequest, conversationRecord)
        yield
          assertTrue(response.status == Status.SeeOther) &&
          assertTrue(response.header(Header.Location).exists(_.url.path.encode == "/challenge"))
      },
      test("renders step with error on ServiceUnavailable") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderSubmit(ConversationResult.ServiceUnavailable, conversationRecord)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(body.contains("service_unavailable"))
      },
      test("redirects with code and session cookie on Complete") {
        val env = Env()
        val code = AuthorizationCode(Array.fill(16)(1.toByte))
        val sessionId = SessionId(Array.fill(32)(2.toByte))
        val userId = UserId(UUID.randomUUID())
        val result = ConversationResult.Complete(redirectUri, Some(State("test-state")), code, sessionId, None, testUserAgentId, UserAgentData(None, userId, UserAgentDetails.parse(None)))
        for
          _ <- env.configuration.getSessionTtl.succeedsWith(1.hour)
          _ <- env.configuration.getUserAgentTtl.succeedsWith(180.days)
          response <- env.service.renderSubmit(result, conversationRecord)
        yield
          assertTrue(response.status == Status.SeeOther) &&
          assertTrue(response.header(Header.Location).exists(_.url.encode.contains("code=" + versola.util.Base64Url.encode(code)))) &&
          assertTrue(response.headers.get(Header.SetCookie).exists(_.renderedValue.contains("SSO_SESSION"))) &&
          assertTrue(response.headers.getAll(Header.SetCookie).exists(c => c.value.name == "SSO_CONVERSATION" && c.value.maxAge.contains(Duration.Zero)))
      },
      test("includes id_token if provided in Complete") {
        val env = Env()
        val code = AuthorizationCode(Array.fill(16)(1.toByte))
        val sessionId = SessionId(Array.fill(32)(2.toByte))
        val userId = UserId(java.util.UUID.randomUUID())
        val idTokenData = ConversationResult.IdTokenData(userId, Map.empty, clientId, PublicSessionId("public-session-1"))
        val result = ConversationResult.Complete(redirectUri, Some(State("test-state")), code, sessionId, Some(idTokenData), testUserAgentId, UserAgentData(None, userId, UserAgentDetails.parse(None)))

        for
          _ <- env.configuration.getSessionTtl.succeedsWith(1.hour)
          _ <- env.configuration.getUserAgentTtl.succeedsWith(180.days)
          _ <- env.jwksService.getPublicKeys.succeedsWith(TestEnvConfig.publicKeys)
          response <- env.service.renderSubmit(result, conversationRecord)
        yield
          assertTrue(response.header(Header.Location).exists(_.url.encode.contains("id_token=")))
      }
    ),
    suite("renderLogout")(
      test("renders SignedOut view with encoded logout URIs and redirect URI including state") {
        val env = Env()
        val logoutUri1 = URL.decode("https://sp1.example/logout").toOption.get
        val logoutUri2 = URL.decode("https://sp2.example/logout").toOption.get
        for
          _ <- env.configuration.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord.copy(id = "signed-out")))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          response <- env.service.renderLogout(List(logoutUri1, logoutUri2), Some(redirectUri), Some("st-1"))
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html)) &&
          assertTrue(response.headers.get("Cache-Control").contains("no-cache, no-store")) &&
          assertTrue(response.headers.get("Referrer-Policy").contains("no-referrer")) &&
          assertTrue(body.contains("versola-step\" content=\"signed-out\"")) &&
          assertTrue(body.contains(logoutUri1.encode)) &&
          assertTrue(body.contains(logoutUri2.encode)) &&
          assertTrue(body.contains(redirectUri.addQueryParam("state", "st-1").encode)) &&
          assertTrue(body.contains(".body { color: black; }"))
      },
      test("omits redirectUri when postLogoutRedirectUri is absent") {
        val env = Env()
        for
          _ <- env.configuration.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord.copy(id = "signed-out")))
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          response <- env.service.renderLogout(Nil, None, None)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(!body.contains("\"redirectUri\"")) &&
          assertTrue(body.contains("\"logoutUris\":[]"))
      },
      test("renders 404 if signed-out form is not found") {
        val env = Env()
        for
          _ <- env.configuration.getTheme.succeedsWith(Some(defaultTheme))
          _ <- env.configuration.getForm.succeedsWith(None)
          _ <- env.configuration.getLocales.succeedsWith(locales)
          _ <- env.configuration.getIdentityProviderLogo.succeedsWith(None)
          response <- env.service.renderLogout(Nil, Some(redirectUri), None)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.NotFound) &&
          assertTrue(body.contains("Page not found"))
      },
    ),
  )
