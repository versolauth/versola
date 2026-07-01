package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFactor, AuthFactorType, AuthFlow, AuthMethodRef, ClientId, PassedAuthFactor, PassedFactorRecord, PrimaryAuthFlow, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import zio.Exit
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, State}
import versola.oauth.session.model.SessionId
import versola.user.model.Login
import versola.util.{Email, Phone, SecureRandom, UnitSpecBase}
import zio.http.URL
import zio.test.*

import java.time.Instant
import java.util.UUID

object ConversationRouterSpec extends UnitSpecBase:

  val authId = AuthId(UUID.randomUUID())
  val email = Email("test@example.com")
  val phone = Phone("+1234567890")
  val otpCode = OtpCode("123456")

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  val otp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 1,
    timesSubmitted = 0,
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = None,
  )

  val conversationResult = ConversationResult.RenderStep(otp)

  val otpAuthFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.phone),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
    ),
    passkey = None,
    equivalents = Map.empty,
  )

  val initialRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = Some(State("test-state")),
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = otpAuthFlow,
    userAgent = None,
    version = 0,
    amr = Map.empty,
  )

  val otpRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = Some(State("test-state")),
    userId = None,
    credential = Some(Left(email)),
    step = otp,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = otpAuthFlow,
    userAgent = None,
    version = 0,
    amr = Map.empty,
  )

  val login = Login("testuser")
  val password = Password("password123")

  val loginFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.login),
      inlinePassword = true,
      factors = List.empty,
    ),
    passkey = None,
    equivalents = Map.empty,
  )

  val loginRecord = initialRecord.copy(authFlow = loginFlow)

  class Env:
    val conversationRepository = stub[ConversationRepository]
    val otpConversationService = stub[ConversationService]
    val configService = stub[OAuthConfigurationService]
    val secureRandom = stub[SecureRandom]
    val router = ConversationRouter.Impl(
      conversationRepository,
      otpConversationService,
      configService,
      secureRandom,
    )

  val spec = suite("ConversationRouter")(
    suite("getConversation")(
      test("return conversation record when it exists") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          result <- env.router.getConversation(authId)
        yield assertTrue(result.contains(otpRecord))
      },
      test("return None when record doesn't exist") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(None)
          result <- env.router.getConversation(authId)
        yield assertTrue(result.isEmpty)
      },
    ),
    suite("submit")(
      test("fail with BadRequest when conversation does not exist") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(None)
          exit <- env.router.submit(authId, EmailSubmission(email), None).exit
        yield assertTrue(exit == Exit.fail(Error.BadRequest))
      },
      test("handle email submission") {
        val env = Env()
        val submission = EmailSubmission(email)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, record) <- env.router.submit(authId, submission, None)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          record == initialRecord,
          prepareTimes == 1,
        )
      },
      test("handle phone submission") {
        val env = Env()
        val submission = PhoneSubmission(phone)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, record) <- env.router.submit(authId, submission, None)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          record == initialRecord,
          prepareTimes == 1,
        )
      },
      test("propagate infrastructure failures from the conversation lookup") {
        val env = Env()
        val boom = new RuntimeException("db down")
        for
          _ <- env.otpConversationService.find.failsWith(boom)
          exit <- env.router.submit(authId, EmailSubmission(email), None).exit
        yield assertTrue(exit == Exit.fail(boom))
      },
      test("handle OTP submission and complete conversation on success") {
        val env = Env()
        val submission = OtpSubmission(otpCode)
        val successResult = ConversationResult.StepPassed(otpRecord)
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId: SessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          _ <- env.otpConversationService.checkOtp.succeedsWith(successResult)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          (result, _) <- env.router.submit(authId, submission, None)
          checkOtpTimes = env.otpConversationService.checkOtp.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          checkOtpTimes == 1,
          finishTimes == 1,
        )
      },
      test("return NotFound when the submission does not match the current step") {
        val env = Env()
        val submission = OtpSubmission(otpCode)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          (result, _) <- env.router.submit(authId, submission, None)
        yield assertTrue(result == ConversationResult.NotFound)
      },
      test("skip OTP factor and finish when passkey satisfies it via equivalents") {
        val env = Env()
        val now = Instant.now()
        val flowWithEquivalents = AuthFlow(
          primary = PrimaryAuthFlow(
            credentials = List(PrimaryCredential.phone),
            inlinePassword = false,
            factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
          ),
          passkey = None,
          equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp)),
        )
        val recordWithPasskeyAmr = initialRecord.copy(
          authFlow = flowWithEquivalents,
          amr = Map(PassedAuthFactor.passkey -> PassedFactorRecord(now, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa))),
        )
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(recordWithPasskeyAmr))
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          (result, _) <- env.router.submit(authId, EmailSubmission(email), None)
          finishTimes = env.otpConversationService.finish.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == completeResult,
          finishTimes == 1,
          prepareOtpTimes == 0,
        )
      },
      test("handle login-password submission and finish when no further factors remain") {
        val env = Env()
        val submission = LoginPasswordSubmission(login, password)
        val successResult = ConversationResult.StepPassed(loginRecord)
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId: SessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(loginRecord))
          _ <- env.otpConversationService.checkLoginPassword.succeedsWith(successResult)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          (result, _) <- env.router.submit(authId, submission, None)
          checkTimes = env.otpConversationService.checkLoginPassword.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          checkTimes == 1,
          finishTimes == 1,
        )
      },
      test("return the render result directly when login-password does not pass") {
        val env = Env()
        val submission = LoginPasswordSubmission(login, password)
        val renderResult = ConversationResult.RenderStep(loginRecord.step)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(loginRecord))
          _ <- env.otpConversationService.checkLoginPassword.succeedsWith(renderResult)
          (result, _) <- env.router.submit(authId, submission, None)
          checkTimes = env.otpConversationService.checkLoginPassword.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == renderResult,
          checkTimes == 1,
          finishTimes == 0,
        )
      },
    ),
  )
