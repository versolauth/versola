package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.test.*

object PassedAuthFactorSpec extends UnitSpecBase:

  val spec = suite("PassedAuthFactor.satisfies")(
    test("otp satisfies otp directly") {
      assertTrue(PassedAuthFactor.otp.satisfies(PassedAuthFactor.otp, Map.empty))
    },
    test("password satisfies password directly") {
      assertTrue(PassedAuthFactor.password.satisfies(PassedAuthFactor.password, Map.empty))
    },
    test("passkey satisfies passkey directly") {
      assertTrue(PassedAuthFactor.passkey.satisfies(PassedAuthFactor.passkey, Map.empty))
    },
    test("otp does not satisfy password with empty equivalents") {
      assertTrue(!PassedAuthFactor.otp.satisfies(PassedAuthFactor.password, Map.empty))
    },
    test("passkey does not satisfy otp with empty equivalents") {
      assertTrue(!PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, Map.empty))
    },
    test("passkey satisfies otp when mapped to Set(otp)") {
      val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp))
      assertTrue(PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, equivalents))
    },
    test("passkey satisfies password when mapped to Set(password)") {
      val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.password))
      assertTrue(PassedAuthFactor.passkey.satisfies(PassedAuthFactor.password, equivalents))
    },
    test("passkey satisfies both otp and password when mapped to Set(otp, password)") {
      val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp, PassedAuthFactor.password))
      assertTrue(
        PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, equivalents),
        PassedAuthFactor.passkey.satisfies(PassedAuthFactor.password, equivalents),
      )
    },
    test("passkey does not satisfy otp when only mapped to Set(password)") {
      val equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.password))
      assertTrue(!PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, equivalents))
    },
    test("equivalents for other factor do not affect passkey check") {
      val equivalents = Map(PassedAuthFactor.otp -> Set(PassedAuthFactor.password))
      assertTrue(!PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, equivalents))
    },
  )
