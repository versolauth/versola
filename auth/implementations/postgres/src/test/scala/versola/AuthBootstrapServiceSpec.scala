package versola

import versola.util.{EnvName, Phone}
import zio.test.*

object AuthBootstrapServiceSpec extends ZIOSpecDefault:

  def spec = suite("AuthBootstrapService")(
    test("uses the test admin phone outside production") {
      assertTrue(
        AuthBootstrapService.adminPhone(EnvName.Test("docker-local")) == Some(Phone("+12025551234")),
        AuthBootstrapService.adminPhone(EnvName.Prod).isEmpty,
      )
    },
  )