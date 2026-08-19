package versola.oauth.conversation

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.auth.model.{OtpCode, Password}
import versola.user.model.Login
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.jwks.JwksService
import versola.oauth.client.model.{AuthFlow, ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, ConversationCookie}
import versola.util.http.{ControllerSpec, NoopTracing, Observability}
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.http.*
import zio.internal.stacktracer.SourceLocation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object ConversationControllerSpec extends UnitSpecBase:
  type Service = ConversationRouter

  val authId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val clientId = ClientId("test-client")
  val email = Email("test@example.com")
  val phone = Phone("+12025551234")
  val otpCode = OtpCode("123456")
  val conversationCookie = Header.Cookie(
    NonEmptyChunk(
      Cookie.Request(
        ConversationCookie.name,
        ConversationCookie.responseCookie(
          ConversationCookie(
            authId,
            clientId,
            redirectUri = "https://example.com/callback",
            state = Some("test-state"),
          ),
          Duration.Zero,
          TestEnvConfig.coreConfig.security.conversationCookieSecret,
        ).content,
      ),
    ),
  )

  val conversationResult = ConversationResult.RenderStep(
    ConversationStep.Otp(
      real = Some(ConversationStep.Otp.Real(otpCode)),
      timesRequested = 1,
      timesSubmitted = 0,
      factorIndex = 0,
      rateLimitExceeded = false,
      lockedSeconds = 0,
      lastSentAt = None,
    ),
  )

  val record = ConversationRecord(
    clientId = ClientId("test-client"),
    redirectUri = URL.decode("https://example.com/callback").toOption.get,
    scope = Set(ScopeToken("openid")),
    codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = None,
    userId = None,
    credential = None,
    step = ConversationStep.Otp(real = None, timesRequested = 1, timesSubmitted = 0, factorIndex = 0, rateLimitExceeded = false, lockedSeconds = 0, lastSentAt = None),
    requestedClaims = None,
    uiLocales = None,
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
    version = 0,
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

  def successfulSubmitTestCase(
      description: String,
      request: Request,
      submission: (AuthId, Submission, Option[String], Option[String]),
      ipHeader: String = "X-Real-IP",
  )(using
      loc: SourceLocation,
      trace: Trace,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        formService <- ConversationRenderService.live
          .build
          .provideSome[zio.Scope](
            ZLayer.succeed(TestEnvConfig.coreConfig),
            ZLayer.succeed(configuration),
            ZLayer.succeed(TestEnvConfig.jwksService),
          )

        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes
              .provideEnvironment(
                ZEnvironment(router)
                  ++ ZEnvironment(TestEnvConfig.coreConfig)
                  ++ ZEnvironment(configuration)
                  ++ formService
                  ++ tracing,
              )
          )
        )
        _ <- configuration.getAllowedPhonePrefixes.succeedsWith(List.empty)
        _ <- configuration.getPasswordRegex.succeedsWith(".*")
        _ <- configuration.getIpHeader.succeedsWith(ipHeader)
        _ <- router.submit.succeedsWith((conversationResult, record))

        response <- client.batched(request)

        submitCalls = router.submit.calls
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode.contains("challenge")),
      ) && assertTrue(submitCalls == List(submission))
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def rejectedSubmitTestCase(
      description: String,
      request: Request,
      passwordRegex: String = "^[0-9]+$",
  )(using
      loc: SourceLocation,
      trace: Trace,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        formService <- ConversationRenderService.live
          .build
          .provideSome[zio.Scope](
            ZLayer.succeed(TestEnvConfig.coreConfig),
            ZLayer.succeed(configuration),
            ZLayer.succeed(TestEnvConfig.jwksService),
          )

        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes
              .provideEnvironment(
                ZEnvironment(router)
                  ++ ZEnvironment(TestEnvConfig.coreConfig)
                  ++ ZEnvironment(configuration)
                  ++ formService
                  ++ tracing,
              )
          )
        )
        _ <- configuration.getPasswordRegex.succeedsWith(passwordRegex)
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")

        response <- client.batched(request)
        submitCalls = router.submit.calls
      yield assertTrue(
        response.status == Status.BadRequest,
        submitCalls.isEmpty,
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("ConversationController")(
    successfulSubmitTestCase(
      description = "submit email",
      request = Request.post(
        url = URL.empty / "challenge" / "email",
        body = Body.fromURLEncodedForm(
          Form(FormField.Text("email", email, MediaType.text.plain), FormField.Text("csrf", "", MediaType.text.plain)),
        )
      ).addHeader(conversationCookie),
      submission = (authId, EmailSubmission(email, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit phone",
      request = Request.post(
        url = URL.empty / "challenge" / "phone",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("phone" -> phone, "csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp resend",
      request = Request.post(
        url = URL.empty / "challenge" / "otp" / "resend",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpResendSubmission(""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit forwards ui_locale from query param",
      request = Request.post(
        url = (URL.empty / "challenge" / "otp").addQueryParam("ui_locale", "ru"),
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), Some("ru"), None),
    ),
    successfulSubmitTestCase(
      description = "submit reads the tenant-configured header (X-Real-IP) as the throttle ip",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie).addHeader("X-Real-IP", "9.9.9.9"),
      submission = (authId, OtpSubmission(otpCode, ""), None, Some("9.9.9.9")),
    ),
    successfulSubmitTestCase(
      description = "submit reads the tenant-configured header (X-Forwarded-For), taking the first value",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie).addHeader("X-Forwarded-For", "7.7.7.7, 10.0.0.1"),
      submission = (authId, OtpSubmission(otpCode, ""), None, Some("7.7.7.7")),
      ipHeader = "X-Forwarded-For",
    ),
    successfulSubmitTestCase(
      description = "submit passes no ip when the configured header is absent from the request",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit passes no ip when the configured header does not match any request header",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        )
      ).addHeader(conversationCookie).addHeader("X-Real-IP", "9.9.9.9"),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
      ipHeader = "X-Forwarded-For",
    ),
    successfulSubmitTestCase(
      description = "submit login-password",
      request = Request.post(
        url = URL.empty / "challenge" / "login-password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("login" -> "user", "password" -> "s3cret", "csrf" -> ""),
        )
      ).addHeader(conversationCookie),
      submission = (authId, LoginPasswordSubmission(Login("user"), Password("s3cret"), ""), None, None),
    ),
    test("POST /challenge renders service unavailable when conversation lookup fails") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing
            )
          )
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.failsWith(Error.ServiceUnavailable)
        _ <- renderService.renderServiceUnavailable.succeedsWith(Response.text("<html>Unavailable</html>"))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "email",
            body = Body.fromURLEncodedForm(Form.fromStrings("email" -> email, "csrf" -> "test-csrf")),
          ).addHeader(conversationCookie)
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Unavailable</html>",
        renderService.renderServiceUnavailable.calls == List((clientId, "https://example.com/callback", Some("test-state"))),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge renders step") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing
            )
          )
        )
        _ <- router.getConversation.succeedsWith(Some(record))
        _ <- renderService.renderStep.succeedsWith(Response.text("<html>Step</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.ContentType).exists(_.mediaType == MediaType.text.plain) // text() defaults to text/plain in tests
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,

    test("GET /challenge renders conversation expired when the record is missing") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing
            )
          )
        )
        _ <- router.getConversation.succeedsWith(None)
        _ <- renderService.renderExpired.succeedsWith(Response.text("<html>Expired</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Expired</html>",
        renderService.renderExpired.calls == List((clientId, "https://example.com/callback", Some("test-state"))),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,

    test("GET /challenge renders service unavailable when conversation lookup fails") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing
            )
          )
        )
        _ <- router.getConversation.failsWith(Error.ServiceUnavailable)
        _ <- renderService.renderServiceUnavailable.succeedsWith(Response.text("<html>Unavailable</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Unavailable</html>",
        renderService.renderServiceUnavailable.calls == List((clientId, "https://example.com/callback", Some("test-state"))),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,

    test("GET /challenge/passkey/options returns options") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing
            )
          )
        )
        _ <- router.startPasskeyOptions.succeedsWith(Some("{\"opt\":1}"))

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.ContentType).exists(_.mediaType == MediaType.application.json)
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    rejectedSubmitTestCase(
      description = "reject login-password violating configured password regex",
      request = Request.post(
        url = URL.empty / "challenge" / "login-password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("login" -> "user", "password" -> "abc"),
        )
      ).addHeader(conversationCookie),
    ),
    rejectedSubmitTestCase(
      description = "reject password violating configured password regex",
      request = Request.post(
        url = URL.empty / "challenge" / "password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("password" -> "abc"),
        )
      ).addHeader(conversationCookie),
    ),
  )
