package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.conversation.model.*
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, SessionCookie, State}
import versola.oauth.session.model.SessionId
import versola.user.model.UserId
import versola.util.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object ConversationRenderServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("tenant-1")
  private val clientId = ClientId("client-1")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Test Client",
    redirectUris = zio.prelude.NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "custom-theme",
    authFlow = None,
    otpTemplateId = "default",
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
    userAgent = None,
    version = 1,
    amr = Map.empty,
    needsPasswordChange = false,
    expectedUserId = None,
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
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getAllowedPhonePrefixes.succeedsWith(List("+1"))
          response <- env.service.renderStep(conversationRecord, None)
          body <- response.body.asString
        yield
          assertTrue(response.status == Status.Ok) &&
          assertTrue(response.header(Header.ContentType).exists(_.mediaType == MediaType.text.html)) &&
          assertTrue(body.contains("Sign In Test")) &&
          assertTrue(body.contains(".body { color: red; }")) &&
          assertTrue(body.contains("#form { margin: 0; }")) &&
          assertTrue(body.contains("window.__VERSOLA_FORM__ =")) &&
          assertTrue(body.contains("console.log('init');"))
      },
      test("returns 304 if ETag matches") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
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
          _ <- env.configuration.getOtpSettings.succeedsWith(otpSettings)
          response <- env.service.renderStep(record, None)
          body <- response.body.asString
        yield
          assertTrue(body.contains("\"resendAfter\":30"))
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
      test("returns 400 on IllegalState") {
        val env = Env()
        for
          response <- env.service.renderSubmit(ConversationResult.IllegalState, conversationRecord)
        yield
          assertTrue(response.status == Status.BadRequest)
      },
      test("returns 404 on NotFound") {
        val env = Env()
        for
          response <- env.service.renderSubmit(ConversationResult.NotFound, conversationRecord)
        yield
          assertTrue(response.status == Status.NotFound)
      },
      test("renders step with error on ServiceUnavailable") {
        val env = Env()
        for
          _ <- env.configuration.find.succeedsWith(Some(clientRecord))
          _ <- env.configuration.getTheme.succeedsWith(Some(theme))
          _ <- env.configuration.getForm.succeedsWith(Some(formRecord))
          _ <- env.configuration.getLocales.succeedsWith(locales)
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
        val result = ConversationResult.Complete(redirectUri, Some(State("test-state")), code, sessionId, None)
        for
          _ <- env.configuration.getSessionTtl.succeedsWith(1.hour)
          response <- env.service.renderSubmit(result, conversationRecord)
        yield
          assertTrue(response.status == Status.SeeOther) &&
          assertTrue(response.header(Header.Location).exists(_.url.encode.contains("code=" + versola.util.Base64Url.encode(code)))) &&
          assertTrue(response.headers.get(Header.SetCookie).exists(_.renderedValue.contains("SSO_SESSION")))
      },
      test("includes id_token if provided in Complete") {
        val env = Env()
        val code = AuthorizationCode(Array.fill(16)(1.toByte))
        val sessionId = SessionId(Array.fill(32)(2.toByte))
        val userId = UserId(java.util.UUID.randomUUID())
        val idTokenData = ConversationResult.IdTokenData(userId, Map.empty, clientId)
        val result = ConversationResult.Complete(redirectUri, Some(State("test-state")), code, sessionId, Some(idTokenData))

        for
          _ <- env.configuration.getSessionTtl.succeedsWith(1.hour)
          _ <- env.jwksService.getPublicKeys.succeedsWith(TestEnvConfig.publicKeys)
          response <- env.service.renderSubmit(result, conversationRecord)
        yield
          assertTrue(response.header(Header.Location).exists(_.url.encode.contains("id_token=")))
      }
    )
  )
