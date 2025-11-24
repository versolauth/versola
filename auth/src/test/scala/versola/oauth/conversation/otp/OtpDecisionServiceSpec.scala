package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.model.ConversationStep
import versola.oauth.conversation.otp.model.SendOtpResult
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.test.*

import java.util.UUID

object OtpDecisionServiceSpec extends UnitSpecBase:

  val service = OtpDecisionService.Impl()
  val userId = UserId(UUID.randomUUID())
  val code = OtpCode("123456")
  val previousOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(code)),
    timesRequested = 1,
    timesSubmitted = 0,
  )

  val spec = suite("OtpDecisionService")(
    suite("checkRequest")(
      test("allow first OTP request when no previous request exists") {
        for
          result <- service.checkRequest(None, None)
        yield assertTrue(
          result == SendOtpResult.Success(fake = false),
        )
      },
      test("allow second OTP request when previous exists") {
        for
          result <- service.checkRequest(Some(previousOtp), None)
        yield assertTrue(
          result == SendOtpResult.Success(fake = false),
        )
      },
      test("reject OTP request when limit exceeded (3+ requests)") {
        for
          result <- service.checkRequest(Some(previousOtp.copy(timesRequested = 2)), None)
        yield assertTrue(result == SendOtpResult.LimitsExceeded)
      },
      test("return fake OTP when previous was fake") {
        val fakePrevious = ConversationStep.Otp(
          real = None,
          timesRequested = 1,
          timesSubmitted = 0,
        )
        for
          result <- service.checkRequest(Some(fakePrevious), None)
        yield assertTrue(
          result == SendOtpResult.Success(fake = true),
        )
      },
    ),
  )
