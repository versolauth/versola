package versola.util

import zio.test.*

object EnvNameSpec extends ZIOSpecDefault:

  def spec = suite("EnvName.parse")(
    test("prod and every name ending in it is production") {
      assertTrue(
        EnvName.parse("prod") == EnvName.Prod,
        EnvName.parse("eu-prod") == EnvName.Prod,
        EnvName.parse("preprod") == EnvName.Prod,
        EnvName.parse("prod").isProd,
      )
    },
    test("any other name is a test environment that keeps its name") {
      assertTrue(
        EnvName.parse("local") == EnvName.Test("local"),
        EnvName.parse("k8s") == EnvName.Test("k8s"),
        EnvName.parse("Prod") == EnvName.Test("Prod"),
        EnvName.parse("prd").isTest,
        EnvName.parse("production").isTest,
      )
    },
  )
