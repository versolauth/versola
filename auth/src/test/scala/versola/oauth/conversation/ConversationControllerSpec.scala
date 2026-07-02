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
          ConversationCookie(authId, clientId),
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
    userAgent = None,
    version = 0,
    amr = Map.empty,
  )

  def successfulSubmitTestCase(
      description: String,
      request: Request,
      submission: (AuthId, Submission, Option[String], Option[String]),
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
        _ <- configuration.getPasswordRegex.succeedsWith(None)
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
        _ <- configuration.getPasswordRegex.succeedsWith(Some(passwordRegex))

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
          Form(FormField.Text("email", email, MediaType.text.plain)),
        )
      ).addHeader(conversationCookie),
      submission = (authId, EmailSubmission(email), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit phone",
      request = Request.post(
        url = URL.empty / "challenge" / "phone",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("phone" -> phone),
        )
      ).addHeader(conversationCookie),
      submission = (authId, PhoneSubmission(phone), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit otp resend",
      request = Request.post(
        url = URL.empty / "challenge" / "otp" / "resend",
        body = Body.fromURLEncodedForm(
          Form.fromStrings(),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpResendSubmission(), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit forwards ui_locale from query param",
      request = Request.post(
        url = (URL.empty / "challenge" / "otp").addQueryParam("ui_locale", "ru"),
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode), Some("ru"), None),
    ),
    successfulSubmitTestCase(
      description = "submit forwards the X-Real-IP header as the throttle ip",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie).addHeader("X-Real-IP", "9.9.9.9"),
      submission = (authId, OtpSubmission(otpCode), None, Some("9.9.9.9")),
    ),
    successfulSubmitTestCase(
      description = "submit falls back to X-Forwarded-For when X-Real-IP is absent",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie).addHeader("X-Forwarded-For", "7.7.7.7, 10.0.0.1"),
      submission = (authId, OtpSubmission(otpCode), None, Some("7.7.7.7")),
    ),
    successfulSubmitTestCase(
      description = "submit passes no ip when neither X-Real-IP nor X-Forwarded-For is present",
      request = Request.post(
        url = URL.empty / "challenge" / "otp",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("code" -> otpCode.toString),
        )
      ).addHeader(conversationCookie),
      submission = (authId, OtpSubmission(otpCode), None, None),
    ),
    successfulSubmitTestCase(
      description = "submit login-password",
      request = Request.post(
        url = URL.empty / "challenge" / "login-password",
        body = Body.fromURLEncodedForm(
          Form.fromStrings("login" -> "user", "password" -> "s3cret"),
        )
      ).addHeader(conversationCookie),
      submission = (authId, LoginPasswordSubmission(Login("user"), Password("s3cret")), None, None),
    ),
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
