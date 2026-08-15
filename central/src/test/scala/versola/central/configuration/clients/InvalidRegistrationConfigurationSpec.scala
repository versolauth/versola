package versola.central.configuration.clients

import versola.central.configuration.roles.RoleId
import versola.util.UnitSpecBase
import zio.test.*

object InvalidRegistrationConfigurationSpec extends UnitSpecBase:

  private val clientId = ClientId("registration-client")

  def spec = suite("InvalidRegistrationConfiguration")(
    test("accepts multiple assigned roles") {
      val flow = RegistrationFlow.default.copy(
        roleIds = Set(RoleId("user"), RoleId("member")),
      )

      assertTrue(
        InvalidRegistrationConfiguration.validate(clientId, Some(AuthFlow.default), Some(flow)).isEmpty,
      )
    },
    test("rejects a registration flow without an assigned role") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(roleIds = Set.empty)),
      )

      assertTrue(result.exists(_.reason == "registration requires at least one assigned role"))
    },
    test("rejects password setup without credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.SetPassword()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
    test("rejects account setup before credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.PasskeyEnroll(), RegistrationStep.Otp()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
  )