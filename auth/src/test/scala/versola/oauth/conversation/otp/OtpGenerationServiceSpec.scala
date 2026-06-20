package versola.oauth.conversation.otp

import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.util.{CoreConfig, EnvName, Secret, SecureRandom, UnitSpecBase}
import zio.*
import zio.test.*
import zio.json.ast

object OtpGenerationServiceSpec extends UnitSpecBase:

  class Env(envName: EnvName = EnvName.Prod):
    val secureRandom = stub[SecureRandom]
    val service = OtpGenerationService.Impl(secureRandom, envName)

  val spec = suite("OtpGenerationService")(
    suite("generateOtpCode")(
      test("generate numeric OTP of configured length in production") {
        val env = Env(EnvName.Prod)
        for
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("654321"))
          result <- env.service.generateOtpCode(6)
        yield assertTrue(result == OtpCode("654321"))
      },
      test("return fixed OTP of configured length in non-production environment") {
        val env = Env(EnvName.Test("dev"))
        for
          result <- env.service.generateOtpCode(4)
        yield assertTrue(result == OtpCode("1234"))
      },
      test("generate different OTPs on multiple calls in production") {
        val env = Env(EnvName.Prod)
        for
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("111111"))
          result1 <- env.service.generateOtpCode(6)
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("222222"))
          result2 <- env.service.generateOtpCode(6)
        yield assertTrue(
          result1 == OtpCode("111111"),
          result2 == OtpCode("222222"),
          result1 != result2,
        )
      },
    ),
  )

