package versola.e2e.support

import zio.*
import zio.http.Client
import zio.test.{TestAspect, TestAspectAtLeastR, TestEnvironment, ZIOSpec}

/** Base class for e2e specs.
  *
  * The `bootstrap` layer registers all shared clients/users once per spec run,
  * flushes the Central user outbox, and syncs the Auth configuration cache —
  * so individual tests start with a fully-populated environment.
  */
abstract class E2ESpec extends ZIOSpec[Flows.Setups]:
  override val bootstrap: ZLayer[Any, Any, Flows.Setups] =
    (E2EConfig.live ++ Client.default) >>> OAuthClient.live >>> Flows.layer

  /** Real time for every spec: they drive running services, so each wait (a poll for a revocation or an outbox to settle, a
    * retry, the timeout around it) is a real one. Under the default test clock, which nothing here advances, such a wait
    * never ends, its own timeout included: a test hangs the first time what it polls is not there yet, until the CI job is
    * cancelled.
    */
  override def aspects: Chunk[TestAspectAtLeastR[Flows.Setups & TestEnvironment]] =
    super.aspects :+ TestAspect.withLiveClock

  def setup(id: Flows.Id): ZIO[Flows.Setups, Nothing, (Flows.Setup, OAuthClient)] =
    ZIO.serviceWith[Flows.Setups](s => s(id) -> s.client)
