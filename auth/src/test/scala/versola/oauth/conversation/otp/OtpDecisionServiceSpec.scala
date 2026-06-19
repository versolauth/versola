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
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = None,
  )

  val spec = suite("OtpDecisionService")(
    suite("checkRequest")(
      test("return fake OTP for first request when no user exists") {
        for
          result <- service.checkRequest(None, None)
        yield assertTrue(
          result == SendOtpResult.Success(fake = true),
        )
      },
      test("allow first OTP request when user exists") {
        for
          result <- service.checkRequest(None, Some(userId))
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
      test("return fake OTP when previous was fake") {
        val fakePrevious = ConversationStep.Otp(
          real = None,
          timesRequested = 1,
          timesSubmitted = 0,
          factorIndex = 0,
          rateLimitExceeded = false,
          lockedSeconds = 0,
          lastSentAt = None,
        )
        for
          result <- service.checkRequest(Some(fakePrevious), None)
        yield assertTrue(
          result == SendOtpResult.Success(fake = true),
        )
      },
    ),
  )
