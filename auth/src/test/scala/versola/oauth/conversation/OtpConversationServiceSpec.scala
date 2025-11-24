package versola.oauth.conversation

import versola.auth.model.OtpCode
import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.SessionRepository
import versola.oauth.token.AuthorizationCodeRepository
import versola.security.{Secret, SecureRandom, SecurityService}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, CoreConfig, Email, EnvName, UnitSpecBase}
import zio.http.URL
import zio.json.ast
import zio.test.*

import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

object OtpConversationServiceSpec extends UnitSpecBase:

  val email = Email("test@example.com")
  val authId = AuthId(UUID.randomUUID())
  val userId = UserId(UUID.randomUUID())
  val otpCode = OtpCode("123456")
  val realOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 0,
    timesSubmitted = 0,
  )

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  class Env:
    val otpService = stub[OtpService]
    val conversationRepository = stub[ConversationRepository]
    val userRepository = stub[UserRepository]
    val authorizationCodeRepository = stub[AuthorizationCodeRepository]
    val sessionRepository = stub[SessionRepository]
    val authPropertyGenerator = stub[AuthPropertyGenerator]
    val securityService = stub[SecurityService]
    val config = TestEnvConfig.coreConfig
    val service = ConversationService.Impl(
      otpService,
      conversationRepository,
      userRepository,
      authorizationCodeRepository,
      sessionRepository,
      authPropertyGenerator,
      securityService,
      config,
    )

  val initialConversation = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    userId = None,
    credential = None,
    step = ConversationStep.Empty(PrimaryCredential.Phone, passkey = false),
  )

  val spec = suite("OtpConversationService")(
    suite("prepareInitialOtp")(
      test("create conversation record and send OTP") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.otpService.prepareOtp.succeedsWith(Some(realOtp))
          _ <- env.conversationRepository.overwrite.succeedsWith(())
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
        yield assertTrue(
          result == ConversationResult.RenderStep(realOtp),
        )
      },
      test("return LimitsExceeded when OTP generation fails") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(None)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
        yield assertTrue(
          result == ConversationResult.LimitsExceeded,
        )
      },
    ),
    suite("checkOtp")(
      test("return Success when OTP is correct") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1)
        val record = ConversationRecord(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
        )
        for
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result.isInstanceOf[ConversationResult.StepPassed])
      },
      test("return LimitsExceeded when too many attempts") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1, timesSubmitted = 4)
        val record = ConversationRecord(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
        )
        for
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.LimitsExceeded)
          _ <- env.conversationRepository.delete.succeedsWith(())
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result == ConversationResult.LimitsExceeded)
      },
    ),
  )
