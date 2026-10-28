package versola.oauth.conversation

import org.scalamock.stubs.Stub
import versola.antifraud.Challenge
import versola.auth.TestEnvConfig
import versola.auth.model.{OtpCode, PasskeyName, Password}
import versola.oauth.authorize.AuthorizationResponseService
import versola.oauth.authorize.model.ResponseMode
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, ClientId, PhoneRegion, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.oauth.jwks.JwksService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, ConversationCookie}
import versola.user.model.Login
import versola.util.http.{ControllerSpec, NoopTracing, Observability}
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.http.*
import zio.internal.stacktracer.SourceLocation
import zio.json.*
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
            responseMode = Some(ResponseMode.Query),
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
    codeChallenge = Some(CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")),
    codeChallengeMethod = Some(CodeChallengeMethod.S256),
    state = None,
    userId = None,
    credential = None,
    step = ConversationStep.Otp(
      real = None,
      timesRequested = 1,
      timesSubmitted = 0,
      factorIndex = 0,
      rateLimitExceeded = false,
      lockedSeconds = 0,
      lastSentAt = None,
    ),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    responseMode = versola.oauth.authorize.model.ResponseMode.Query,
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
    dpopJkt = None,
  )

  def successfulSubmitTestCase(
      description: String,
      request: Request,
      submission: (AuthId, Submission, Option[String], Option[String]),
      ipHeader: String = "X-Real-IP",
      expectedLocation: String = "/challenge",
      scripted: Boolean = false,
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
            AuthorizationResponseService.live,
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
              ),
          ),
        )
        _ <- configuration.getPhoneRegions.succeedsWith(List.empty)
        _ <- configuration.getPasswordRegex.succeedsWith(".*")
        _ <- configuration.getIpHeader.succeedsWith(ipHeader)
        _ <- router.submit.succeedsWith((conversationResult, record))

        response <- client.batched(request)

        submitCalls = router.submit.calls
        body <- response.body.asString
      yield
        // A script gets the redirect as data, a plain form the redirect itself.
        (if scripted then
           assertTrue(response.status == Status.Ok, body.fromJson[InlineStep] == Right(InlineStep(Some(expectedLocation), None, otp = true)))
         else assertTrue(response.status == Status.SeeOther, response.header(Header.Location).map(_.url.encode).contains(expectedLocation))) &&
          assertTrue(submitCalls == List(submission))
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  /** A submit a page's script sent (`proof_inline`): the redirect comes back as data, or the challenge to answer. */
  def scriptSubmitTestCase(description: String, result: ConversationResult.Render, expected: InlineStep, proofFields: (String, String)*)(using
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
            AuthorizationResponseService.live,
          )
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes
              .provideEnvironment(
                ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ formService ++ tracing,
              ),
          ),
        )
        _ <- configuration.getPhoneRegions.succeedsWith(List.empty)
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.succeedsWith((result, record))
        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "phone",
            body = Body.fromURLEncodedForm(Form.fromStrings(Seq("phone" -> phone, "csrf" -> "") ++ proofFields*)),
          ).addHeader(conversationCookie),
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.Location).isEmpty,
        body.fromJson[InlineStep] == Right(expected),
      )
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
            AuthorizationResponseService.live,
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
              ),
          ),
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
    scriptSubmitTestCase(
      description = "a script's submit gets the redirect as data instead of a 303, and is told that a code was sent",
      result = conversationResult,
      expected = InlineStep(next = Some("/challenge"), challenge = None, otp = true),
      ProofInput.InlineField -> "1",
    ),
    scriptSubmitTestCase(
      description = "a script's submit that left the code step pending is not told that a code was sent",
      result = ConversationResult.RenderStep(
        ConversationStep.Otp(
          real = None,
          timesRequested = 0,
          timesSubmitted = 0,
          factorIndex = 0,
          rateLimitExceeded = false,
          lockedSeconds = 0,
          lastSentAt = None,
        ),
      ),
      expected = InlineStep(next = Some("/challenge"), challenge = None, otp = false),
      ProofInput.InlineField -> "1",
    ),
    scriptSubmitTestCase(
      description = "a script's submit that was asked for a proof gets the challenge to answer, and nothing else",
      result = ConversationResult.Challenged(Challenge("pow", "tok.en", 16, Map("seed" -> "abc", "difficulty" -> "16"))),
      expected = InlineStep(next = None, challenge = Some(InlineChallenge("pow", "tok.en", Map("seed" -> "abc", "difficulty" -> "16"))), otp = false),
      ProofInput.InlineField -> "1",
    ),
    successfulSubmitTestCase(
      description = "a plain submit is redirected as ever, and carries no proof",
      request = Request.post(
        url = URL.empty / "challenge" / "phone",
        body = Body.fromURLEncodedForm(Form.fromStrings("phone" -> phone, "csrf" -> "")),
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone, ProofInput.NoScript, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "a submit sent from script says so",
      request = Request.post(
        url = URL.empty / "challenge" / "phone",
        body = Body.fromURLEncodedForm(Form.fromStrings("phone" -> phone, "csrf" -> "", ProofInput.InlineField -> "1")),
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone, ProofInput.Asked, ""), None, None),
      scripted = true,
    ),
    successfulSubmitTestCase(
      description = "a submit made again with the token and its solution carries both, for a resend as for a phone",
      request = Request.post(
        url = URL.empty / "challenge" / "otp" / "resend",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("csrf" -> "", ProofInput.InlineField -> "1", ProofInput.TokenField -> "payload.mac", ProofInput.SolutionField -> "4711"),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpResendSubmission(ProofInput.Answered("payload.mac", "4711"), ""), None, None),
      scripted = true,
    ),
    successfulSubmitTestCase(
      description = "submit email",
      request = Request.post(
        url = URL.empty / "challenge" / "email",
        body = Body.fromURLEncodedForm(
          Form(FormField.Text("email", email, MediaType.text.plain), FormField.Text("csrf", "", MediaType.text.plain)),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, EmailSubmission(email, ProofInput.NoScript, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit phone",
      request = Request.post(
        url = URL.empty / "challenge" / "phone",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("phone" -> phone, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone, ProofInput.NoScript, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp resend",
      request = Request.post(
        url = URL.empty / "challenge" / "otp" / "resend",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpResendSubmission(ProofInput.NoScript, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit forwards ui_locale from query param, and the redirect to the next step carries it",
      request = Request.post(
        url = (URL.empty / "challenge" / "otp").addQueryParam("ui_locale", "ru"),
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), Some("ru"), None),
      expectedLocation = "/challenge?ui_locale=ru",
    ),
    successfulSubmitTestCase(
      description = "submit ignores a malformed ui_locale",
      request = Request.post(
        url = (URL.empty / "challenge" / "otp").addQueryParam("ui_locale", "ru;Domain=evil.test"),
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit reads the tenant-configured header (X-Real-IP) as the throttle ip",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie).addHeader("X-Real-IP", "9.9.9.9"),
      submission = (authId, OtpSubmission(otpCode, ""), None, Some("9.9.9.9")),
    ),
    successfulSubmitTestCase(
      description = "submit reads the tenant-configured header (X-Forwarded-For), taking the rightmost value, not one the client wrote on the left",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie).addHeader("X-Forwarded-For", "7.7.7.7, 10.0.0.1"),
      submission = (authId, OtpSubmission(otpCode, ""), None, Some("10.0.0.1")),
      ipHeader = "X-Forwarded-For",
    ),
    successfulSubmitTestCase(
      description = "submit passes no ip when the configured header is absent from the request",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit passes no ip when the configured header does not match any request header",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString, "csrf" -> ""),
        ),
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
        ),
      ).addHeader(conversationCookie),
      submission = (authId, LoginPasswordSubmission(Login("user"), Password("s3cret"), ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit passkey assertion",
      request = Request.post(
        url = URL.empty / "challenge" / "passkey",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("response" -> "{\"id\":\"cred-1\"}", "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, PasskeyAssertionSubmission("{\"id\":\"cred-1\"}", ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit passkey enroll",
      request = Request.post(
        url = URL.empty / "challenge" / "passkey" / "enroll",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("response" -> "{\"id\":\"cred-1\"}", "name" -> "My Phone", "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, PasskeyEnrollSubmission("{\"id\":\"cred-1\"}", PasskeyName("My Phone"), ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit passkey skip",
      request = Request.post(
        url = URL.empty / "challenge" / "passkey" / "skip",
        body = Body.fromURLEncodedForm(Form.fromStrings("csrf" -> "")),
      ).addHeader(conversationCookie),
      submission = (authId, PasskeySkipSubmission(""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit set-password",
      request = Request.post(
        url = URL.empty / "challenge" / "set-password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("password" -> "s3cret!", "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, SetPasswordSubmission(Password("s3cret!"), ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit consent allow with the selected scope",
      request = Request.post(
        url = URL.empty / "challenge" / "consent",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("scope" -> "profile email", "csrf" -> ""),
        ),
      ).addHeader(conversationCookie),
      submission = (authId, ConsentAllowSubmission(Set(ScopeToken("profile"), ScopeToken("email")), ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit consent allow with no scope selected",
      request = Request.post(
        url = URL.empty / "challenge" / "consent",
        body = Body.fromURLEncodedForm(Form.fromStrings("csrf" -> "")),
      ).addHeader(conversationCookie),
      submission = (authId, ConsentAllowSubmission(Set.empty, ""), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit consent deny",
      request = Request.post(
        url = URL.empty / "challenge" / "consent" / "deny",
        body = Body.fromURLEncodedForm(Form.fromStrings("csrf" -> "")),
      ).addHeader(conversationCookie),
      submission = (authId, ConsentDenySubmission(""), None, None),
    ),
    test("GET /challenge sends the render service's ETag when If-None-Match is present") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.succeedsWith(Some(record))
        _ <- renderService.renderStep.succeedsWith(Response.text("<html>Step</html>"))

        response <- client.batched(
          Request.get(URL.empty / "challenge")
            .addHeader(conversationCookie)
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk("\"abc\""))),
        )
        calls = renderService.renderStep.calls
      yield assertTrue(
        response.status == Status.Ok,
        calls == List((record, Some("\"abc\""), None)),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge renders conversation expired when the lookup fails with ConversationExpired") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.failsWith(Error.ConversationExpired)
        _ <- renderService.renderExpired.succeedsWith(Response.text("<html>Expired</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
        body <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body == "<html>Expired</html>")
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge returns 400 when the lookup fails with a plain Error") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.failsWith(Error.BadRequest)

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
      yield assertTrue(response.status == Status.BadRequest)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge surfaces an unexpected failure as 500") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.failsWith(new RuntimeException("boom"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
      yield assertTrue(response.status == Status.InternalServerError)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("POST /challenge renders conversation expired when submit fails with ConversationExpired") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.failsWith(Error.ConversationExpired)
        _ <- renderService.renderExpired.succeedsWith(Response.text("<html>Expired</html>"))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "email",
            body = Body.fromURLEncodedForm(Form.fromStrings("email" -> email, "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
        body <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body == "<html>Expired</html>")
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("POST /challenge sends an expired submit on to /challenge in the language it was submitted in") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.failsWith(Error.ConversationExpired)

        response <- client.batched(
          Request.post(
            url = (URL.empty / "challenge" / "email").addQueryParam("ui_locale", "ru"),
            body = Body.fromURLEncodedForm(Form.fromStrings("email" -> email, "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).map(_.url.encode).contains("/challenge?ui_locale=ru"),
        renderService.renderExpired.calls.isEmpty,
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("POST /challenge surfaces an unexpected submit failure as 500") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.failsWith(new RuntimeException("boom"))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "email",
            body = Body.fromURLEncodedForm(Form.fromStrings("email" -> email, "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
      yield assertTrue(response.status == Status.InternalServerError)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("requests without a conversation cookie are rejected with 400") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        response <- client.batched(Request.get(URL.empty / "challenge"))
      yield assertTrue(response.status == Status.BadRequest)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge/passkey/options returns 400 when the router yields no options") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.startPasskeyOptions.succeedsWith(None)

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.BadRequest,
        response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge/passkey/options returns 500 with no-store when the lookup fails with ServiceUnavailable") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.startPasskeyOptions.failsWith(Error.ServiceUnavailable)

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.InternalServerError,
        response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
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
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- router.submit.failsWith(Error.ServiceUnavailable)
        _ <- renderService.renderServiceUnavailable.succeedsWith(Response.text("<html>Unavailable</html>"))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "email",
            body = Body.fromURLEncodedForm(Form.fromStrings("email" -> email, "csrf" -> "test-csrf")),
          ).addHeader(conversationCookie),
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Unavailable</html>",
        renderService.renderServiceUnavailable.calls == List((clientId, "https://example.com/callback", Some("test-state"), ResponseMode.Query, None)),
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
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.succeedsWith(Some(record))
        _ <- renderService.renderStep.succeedsWith(Response.text("<html>Step</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.ContentType).exists(_.mediaType == MediaType.text.plain), // text() defaults to text/plain in tests
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
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.succeedsWith(None)
        _ <- renderService.renderExpired.succeedsWith(Response.text("<html>Expired</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Expired</html>",
        renderService.renderExpired.calls == List((clientId, "https://example.com/callback", Some("test-state"), ResponseMode.Query, None)),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge renders conversation expired in the language of the request") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.succeedsWith(None)
        _ <- renderService.renderExpired.succeedsWith(Response.text("<html>Expired</html>"))

        response <- client.batched(
          Request.get((URL.empty / "challenge").addQueryParam("ui_locale", "ru"))
            .addHeader(conversationCookie)
            .addHeader(Header.AcceptLanguage.parse("de, fr;q=0.8").toOption.get),
        )
      yield assertTrue(
        renderService.renderExpired.calls == List(
          (clientId, "https://example.com/callback", Some("test-state"), ResponseMode.Query, Some(List("ru", "de", "fr"))),
        ),
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
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.getConversation.failsWith(Error.ServiceUnavailable)
        _ <- renderService.renderServiceUnavailable.succeedsWith(Response.text("<html>Unavailable</html>"))

        response <- client.batched(Request.get(URL.empty / "challenge").addHeader(conversationCookie))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body == "<html>Unavailable</html>",
        renderService.renderServiceUnavailable.calls == List((clientId, "https://example.com/callback", Some("test-state"), ResponseMode.Query, None)),
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
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.startPasskeyOptions.succeedsWith(Some("{\"opt\":1}"))

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.ContentType).exists(_.mediaType == MediaType.application.json),
        response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge/passkey/options returns 410 with no-store when conversation expired") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.startPasskeyOptions.failsWith(Error.ConversationExpired)

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.Gone,
        response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge/passkey/options returns 500 with no-store on unexpected failure") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- router.startPasskeyOptions.failsWith(new RuntimeException("boom"))

        response <- client.batched(Request.get(URL.empty / "challenge" / "passkey" / "options").addHeader(conversationCookie))
      yield assertTrue(
        response.status == Status.InternalServerError,
        response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("reject password that fails the configured regex, once the request itself is well-formed") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- configuration.getPasswordRegex.succeedsWith("^[0-9]+$")

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "password",
            body = Body.fromURLEncodedForm(Form.fromStrings("password" -> "abc", "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
      yield assertTrue(response.status == Status.BadRequest, router.submit.calls.isEmpty)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("reject a phone number that doesn't match any configured prefix, once the request itself is well-formed") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- configuration.getPhoneRegions.succeedsWith(List(PhoneRegion("GB", "+44", 10, "7\\d{9}", true)))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "phone",
            body = Body.fromURLEncodedForm(Form.fromStrings("phone" -> phone, "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
      yield assertTrue(response.status == Status.BadRequest, router.submit.calls.isEmpty)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("accepts a password when the configured regex is invalid, failing open") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        _ <- configuration.getIpHeader.succeedsWith("X-Real-IP")
        _ <- configuration.getPasswordRegex.succeedsWith("[") // invalid regex syntax
        _ <- router.submit.succeedsWith((conversationResult, record))
        _ <- renderService.renderSubmit.succeedsWith(Response.seeOther(URL.decode("/challenge").toOption.get))

        response <- client.batched(
          Request.post(
            url = URL.empty / "challenge" / "password",
            body = Body.fromURLEncodedForm(Form.fromStrings("password" -> "abc", "csrf" -> "")),
          ).addHeader(conversationCookie),
        )
      yield assertTrue(response.status == Status.SeeOther, router.submit.calls.nonEmpty)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    test("GET /challenge with a malformed conversation cookie is rejected with 400") {
      for
        client <- ZIO.service[Client]
        router = stub[ConversationRouter]
        configuration = stub[OAuthConfigurationService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ConversationController.routes.provideEnvironment(
              ZEnvironment(router) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ ZEnvironment(configuration) ++ ZEnvironment(renderService) ++ tracing,
            ),
          ),
        )
        response <- client.batched(
          Request.get(URL.empty / "challenge").addHeader(
            Header.Cookie(NonEmptyChunk(Cookie.Request(ConversationCookie.name, "not-a-valid-cookie"))),
          ),
        )
      yield assertTrue(response.status == Status.BadRequest)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    rejectedSubmitTestCase(
      description = "reject login-password violating configured password regex",
      request = Request.post(
        url = URL.empty / "challenge" / "login-password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("login" -> "user", "password" -> "abc"),
        ),
      ).addHeader(conversationCookie),
    ),
    rejectedSubmitTestCase(
      description = "reject password violating configured password regex",
      request = Request.post(
        url = URL.empty / "challenge" / "password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("password" -> "abc"),
        ),
      ).addHeader(conversationCookie),
    ),
  )
