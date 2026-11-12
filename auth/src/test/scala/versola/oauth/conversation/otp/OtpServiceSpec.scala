package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.OtpSettings
import versola.oauth.client.model.{ClientId, OtpType}
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.conversation.otp.model.{OtpTemplate, SendOtpResult, SubmitOtpResult}
import versola.user.model.UserId
import versola.util.{Email, EnvName, Phone, UnitSpecBase}
import zio.*
import zio.test.*

import java.util.UUID

object OtpServiceSpec extends UnitSpecBase:

  val email = Email("test@example.com")
  val phone = Phone("+1234567890")
  val authId = AuthId(UUID.randomUUID())
  val clientId = ClientId("test-client")
  val userId = UserId(UUID.randomUUID())
  val otpCode = OtpCode("123456")
  val realOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 1,
    timesSubmitted = 0,
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    // The test clock starts at the epoch: the code was sent just now.
    lastSentAt = Some(java.time.Instant.EPOCH),
  )

  class Env:
    val otpGenerationService = stub[OtpGenerationService]
    val otpDecisionService = stub[OtpDecisionService]
    val emailOtpProvider = stub[EmailOtpProvider]
    val otpClient = stub[SmsOtpProvider]
    val configService = stub[OAuthConfigurationService]
    val service = OtpService.Impl(
      otpGenerationService,
      otpDecisionService,
      emailOtpProvider,
      otpClient,
      configService,
      EnvName.Prod,
    )

  val spec = suite("OtpService")(
    suite("prepareOtp")(
      test("generate new OTP when decision service allows") {
        val env = Env()
        for
          _ <- env.otpDecisionService.checkRequest.succeedsWith(SendOtpResult.Success(fake = false))
          _ <- env.configService.getOtpSettings.succeedsWith(OtpSettings.default)
          _ <- env.otpGenerationService.generateOtpCode.succeedsWith(otpCode)
          result <- env.service.prepareOtp(None, None, clientId, isRegistering = false)
        yield assertTrue(
          result == realOtp.copy(timesRequested = 0, lastSentAt = None),
        )
      },
      test("generate fake OTP when decision service indicates") {
        val env = Env()
        for
          _ <- env.otpDecisionService.checkRequest.succeedsWith(SendOtpResult.Success(fake = true))
          result <- env.service.prepareOtp(None, None, clientId, isRegistering = false)
        yield assertTrue(
          result == ConversationStep.Otp(
            real = None,
            timesRequested = 0,
            timesSubmitted = 0,
            factorIndex = 0,
            rateLimitExceeded = false,
            lockedSeconds = 0,
            lastSentAt = None,
          ),
        )
      },
    ),
    suite("sendOtp")(
      test("returns unit immediately when otp.real is None") {
        val env = Env()
        val fakeOtp = ConversationStep.Otp(
          real = None,
          timesRequested = 0,
          timesSubmitted = 0,
          factorIndex = 0,
          rateLimitExceeded = false,
          lockedSeconds = 0,
          lastSentAt = None,
        )
        for
          _ <- env.service.sendOtp(fakeOtp, Left(email), authId, clientId, None)
        yield assertTrue(
          env.emailOtpProvider.sendOtp.calls.isEmpty,
          env.otpClient.sendOtp.calls.isEmpty,
          env.configService.getClientTemplate.calls.isEmpty,
        )
      },
      test("calls emailOtpProvider when credential is an email") {
        val env = Env()
        val template = OtpTemplate("Your code: {{code}}")
        for
          _ <- env.configService.getClientTemplate.succeedsWith(template)
          _ <- env.configService.getClientSubject.succeedsWith(None)
          _ <- env.emailOtpProvider.sendOtp.succeedsWith(())
          _ <- env.service.sendOtp(realOtp, Left(email), authId, clientId, None)
        yield assertTrue(
          env.emailOtpProvider.sendOtp.calls == List((email, otpCode, template, None)),
          env.configService.getClientTemplate.calls == List((clientId, OtpType.email, None)),
        )
      },
      test("passes the subject of the client's email template on, for the locales the user asked for") {
        val env = Env()
        val template = OtpTemplate("Your code: {{code}}")
        for
          _ <- env.configService.getClientTemplate.succeedsWith(template)
          _ <- env.configService.getClientSubject.succeedsWith(Some("{{code}} is your code"))
          _ <- env.emailOtpProvider.sendOtp.succeedsWith(())
          _ <- env.service.sendOtp(realOtp, Left(email), authId, clientId, Some(List("ru")))
        yield assertTrue(
          env.emailOtpProvider.sendOtp.calls == List((email, otpCode, template, Some("{{code}} is your code"))),
          env.configService.getClientSubject.calls == List((clientId, Some(List("ru")))),
        )
      },
      test("calls smsOtpProvider when credential is a phone") {
        val env = Env()
        val template = OtpTemplate("Your code: {{code}}")
        for
          _ <- env.configService.getClientTemplate.succeedsWith(template)
          _ <- env.otpClient.sendOtp.succeedsWith(())
          _ <- env.service.sendOtp(realOtp, Right(phone), authId, clientId, None)
        yield assertTrue(
          env.otpClient.sendOtp.calls == List((phone, otpCode, template)),
          env.configService.getClientTemplate.calls == List((clientId, OtpType.sms, None)),
          env.configService.getClientSubject.calls.isEmpty,
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
      test("return Success up to the end of the code's lifetime") {
        val env = Env()
        for
          _ <- TestClock.adjust(OtpService.Lifetime)
          result <- env.service.checkOtp(realOtp, otpCode)
        yield assertTrue(result == SubmitOtpResult.Success)
      },
      test("return Failure for the right code once its lifetime is over, although the conversation is still alive") {
        val env = Env()
        for
          _ <- TestClock.adjust(OtpService.Lifetime.plusSeconds(1))
          result <- env.service.checkOtp(realOtp, otpCode)
        yield assertTrue(result == SubmitOtpResult.Failure)
      },
      test("count the lifetime from the last send: a resent code starts a new one") {
        val env = Env()
        for
          _ <- TestClock.adjust(OtpService.Lifetime.plusSeconds(30))
          now <- Clock.instant
          resent = realOtp.copy(lastSentAt = Some(now.minusSeconds(5)))
          result <- env.service.checkOtp(resent, otpCode)
        yield assertTrue(result == SubmitOtpResult.Success)
      },
      test("return Failure for a code that has no send time: it was never sent") {
        val env = Env()
        for
          result <- env.service.checkOtp(realOtp.copy(lastSentAt = None), otpCode)
        yield assertTrue(result == SubmitOtpResult.Failure)
      },
      test("return Failure for a code of another length, or with a matching prefix") {
        val env = Env()
        for
          shorter <- env.service.checkOtp(realOtp, OtpCode("12345"))
          longer <- env.service.checkOtp(realOtp, OtpCode("1234567"))
        yield assertTrue(shorter == SubmitOtpResult.Failure, longer == SubmitOtpResult.Failure)
      },
      test("return Failure for fake OTP even with predictable non-prod code") {
        val env = Env()
        val fakeOtp = realOtp.copy(real = None)
        for
          result <- env.service.checkOtp(fakeOtp, otpCode)
        yield assertTrue(result == SubmitOtpResult.Failure)
      },
    ),
  )
