package versola.util.http

import zio.test.*

/** The access log prints a query parameter only if the config names it, and no service names any. That is what keeps the actor
  * signature edge adds to a call to central (a query parameter, valid for a minute) and the actor parameters out of every log line:
  * a spec that changes the default has to change this one, and so says why. */
object HttpObservabilityConfigSpec extends ZIOSpecDefault:

  def spec = suite("what the access log prints of a query")(
    test("by default no query parameter is logged, on a request received or on one sent") {
      assertTrue(
        HttpObservabilityConfig.Server.default.logQuery.isEmpty,
        HttpObservabilityConfig.Client.default.logQuery.isEmpty,
      )
    },
  )
