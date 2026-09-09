package versola.e2e.support

import zio.*
import zio.http.Client
import zio.test.ZIOSpec

/** Base class for the isolated central admin API specs.
  *
  * Unlike [[E2ESpec]] these tests never sign anybody in, so they deliberately skip the
  * `Flows` bootstrap: no clients, users, challenge settings or configuration syncs are set
  * up. What each test needs, it creates and cleans up itself, which is what makes a failure
  * here point at central's API rather than at somebody else's fixture.
  */
abstract class CentralApiSpec extends ZIOSpec[CentralApi]:
  override val bootstrap: ZLayer[Any, Any, CentralApi] =
    (E2EConfig.live ++ Client.default) >>> CentralApi.live

  val api: URIO[CentralApi, CentralApi] = ZIO.service[CentralApi]
