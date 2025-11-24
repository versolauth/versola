package versola.oauth.conversation.otp

import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.security.{Secret, SecureRandom}
import versola.util.{CoreConfig, EnvName, UnitSpecBase}
import zio.*
import zio.test.*
import zio.json.ast

object OtpGenerationServiceSpec extends UnitSpecBase:

  class Env(envName: EnvName = EnvName.Prod):
    val secureRandom = stub[SecureRandom]
    val coreConfig = TestEnvConfig.buildCoreConfig(envName)
    val service = OtpGenerationService.Impl(secureRandom, coreConfig)

  val spec = suite("OtpGenerationService")(
    suite("generateOtpCode")(
      test("generate 6-digit numeric OTP in production") {
        val env = Env(EnvName.Prod)
        for
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("654321"))
          result <- env.service.generateOtpCode
        yield assertTrue(result == OtpCode("654321"))
      },
      test("return fixed OTP in non-production environment") {
        val env = Env(EnvName.Test("dev"))
        for
          result <- env.service.generateOtpCode
        yield assertTrue(result == OtpCode("123456"))
      },
      test("generate different OTPs on multiple calls in production") {
        val env = Env(EnvName.Prod)
        for
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("111111"))
          result1 <- env.service.generateOtpCode
          _ <- env.secureRandom.nextNumeric.succeedsWith(OtpCode("222222"))
          result2 <- env.service.generateOtpCode
        yield assertTrue(
          result1 == OtpCode("111111"),
          result2 == OtpCode("222222"),
          result1 != result2,
        )
      },
    ),
  )

