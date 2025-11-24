package versola.oauth.conversation

import versola.auth.model.OtpCode
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.model.SessionId
import versola.security.SecureRandom
import versola.util.{Email, Phone, UnitSpecBase}
import zio.http.URL
import zio.test.*

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
  )

  val conversationResult = ConversationResult.RenderStep(otp)

  val initialRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    userId = None,
    credential = None,
    step = ConversationStep.Empty(PrimaryCredential.Phone, passkey = false)
  )


  val otpRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    userId = None,
    credential = Some(Left(email)),
    step = otp,
  )

  class Env:
    val conversationRepository = stub[ConversationRepository]
    val otpConversationService = stub[ConversationService]
    val secureRandom = stub[SecureRandom]
    val router = ConversationRouter.Impl(
      conversationRepository,
      otpConversationService,
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
      test("handle email submission") {
        val env = Env()
        val submission = EmailSubmission(email)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          result <- env.router.submit(authId, submission)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          prepareTimes == 1,
        )
      },
      test("handle phone submission") {
        val env = Env()
        val submission = PhoneSubmission(phone)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          result <- env.router.submit(authId, submission)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          prepareTimes == 1,
        )
      },
      test("handle OTP submission and complete conversation on success") {
        val env = Env()
        val submission = OtpSubmission(otpCode)
        val successResult = ConversationResult.StepPassed(otp)
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(testCode, testSessionId)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          _ <- env.otpConversationService.checkOtp.succeedsWith(successResult)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          result <- env.router.submit(authId, submission)
          checkOtpTimes = env.otpConversationService.checkOtp.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          checkOtpTimes == 1,
          finishTimes == 1,
        )
      },
      test("return NotFound when conversation doesn't exist") {
        val env = Env()
        val submission = EmailSubmission(email)
        for
          _ <- env.otpConversationService.find.succeedsWith(None)
          result <- env.router.submit(authId, submission)
        yield assertTrue(result == ConversationResult.NotFound)
      },
    ),
  )
