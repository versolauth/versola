package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.conversation.otp.model.{OtpTemplate, SendOtpResult, SubmitOtpResult}
import versola.user.model.UserId
import versola.util.{Email, Phone, UnitSpecBase}
import zio.test.*

import java.util.UUID

object OtpServiceSpec extends UnitSpecBase:

  val email = Email("test@example.com")
  val phone = Phone("+1234567890")
  val authId = AuthId(UUID.randomUUID())
  val userId = UserId(UUID.randomUUID())
  val otpCode = OtpCode("123456")
  val realOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 1,
    timesSubmitted = 0,
  )

  class Env:
    val otpGenerationService = stub[OtpGenerationService]
    val otpDecisionService = stub[OtpDecisionService]
    val emailOtpProvider = stub[EmailOtpProvider]
    val service = OtpService.Impl(
      otpGenerationService,
      otpDecisionService,
      emailOtpProvider,
    )

  val spec = suite("OtpService")(
    suite("prepareOtp")(
      test("generate new OTP when decision service allows") {
        val env = Env()
        for
          _ <- env.otpDecisionService.checkRequest.succeedsWith(SendOtpResult.Success(fake = false))
          _ <- env.otpGenerationService.generateOtpCode.succeedsWith(otpCode)
          result <- env.service.prepareOtp(None, None)
        yield assertTrue(
          result.contains(
            realOtp.copy(timesRequested = 0),
          ),
        )
      },
      test("return None when limits exceeded") {
        val env = Env()
        for
          _ <- env.otpDecisionService.checkRequest.succeedsWith(SendOtpResult.LimitsExceeded)
          result <- env.service.prepareOtp(None, None)
        yield assertTrue(
          result.isEmpty,
        )
      },
      test("generate fake OTP when decision service indicates") {
        val env = Env()
        for
          _ <- env.otpDecisionService.checkRequest.succeedsWith(SendOtpResult.Success(fake = true))
          result <- env.service.prepareOtp(None, None)
        yield assertTrue(
          result.contains(
            ConversationStep.Otp(
              real = None,
              timesRequested = 0,
              timesSubmitted = 0,
            ),
          )
        )
      },
    ),
    suite("checkOtp")(
      test("return Success when code matches") {
        val env = Env()

        for
          result <- env.service.checkOtp(realOtp, otpCode)
        yield assertTrue(result == SubmitOtpResult.Success)
      },
      test("return Failure when code doesn't match") {
        val env = Env()
        val otp = realOtp.copy(real = Some(ConversationStep.Otp.Real(OtpCode("111111"))))
        for
          result <- env.service.checkOtp(otp, otpCode)
        yield assertTrue(result == SubmitOtpResult.Failure)
      },
      test("return LimitsExceeded when too many attempts") {
        val env = Env()
        val otp = realOtp.copy(timesSubmitted = 4)
        for
          result <- env.service.checkOtp(otp, otpCode)
        yield assertTrue(result == SubmitOtpResult.LimitsExceeded)
      },
    ),
  )
