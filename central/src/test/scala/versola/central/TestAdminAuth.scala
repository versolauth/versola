package versola.central

import zio.http.Header

/** Auth helper for controller tests: provides the shared-secret Basic auth header that
  * central's `authorizeBasic` expects from the edge proxy.
  */
object TestAdminAuth:
  /** HTTP Basic auth header using the test edge secret from [[TestCentralConfig]]. */
  val basicAuthHeader: Header.Authorization = TestCentralConfig.basicAuthHeader
