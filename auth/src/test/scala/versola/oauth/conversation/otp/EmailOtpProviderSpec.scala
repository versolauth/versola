package versola.oauth.conversation.otp

import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, Email}
import zio.*
import zio.test.*
import zio.test.TestAspect.silentLogging

object EmailOtpProviderSpec extends ZIOSpecDefault:

  private val email    = Email("user@example.com")
  private val code     = OtpCode("123456")
  private val template = OtpTemplate("Your code is {{code}}")

  def spec = suite("EmailOtpProvider")(
    test("NoOpEmailOtpProvider.sendOtp succeeds without sending") {
      for
        _ <- NoOpEmailOtpProvider.sendOtp(email, code, template)
      yield assertCompletes
    },

    test("NoOpEmailOtpProvider.send succeeds without sending") {
      for
        _ <- NoOpEmailOtpProvider.send(email, "hello")
      yield assertCompletes
    },

    test("EmailOtpProvider.live returns NoOpEmailOtpProvider when smtp is not configured") {
      val config = TestEnvConfig.coreConfig.copy(smtp = None)
      for
        provider <- ZIO.service[EmailOtpProvider]
      yield assertTrue(provider == NoOpEmailOtpProvider)
    }.provide(ZLayer.succeed(TestEnvConfig.coreConfig.copy(smtp = None)), EmailOtpProvider.live),

    test("EmailOtpProvider.live returns SMTPOtpProvider when smtp is configured") {
      for
        provider <- ZIO.service[EmailOtpProvider]
      yield assertTrue(provider.isInstanceOf[SMTPOtpProvider])
    }.provide(ZLayer.succeed(TestEnvConfig.coreConfig), EmailOtpProvider.live),
  ) @@ silentLogging
