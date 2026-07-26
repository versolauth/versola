package versola.e2e.support

import zio.*
import zio.http.Client
import zio.test.ZIOSpec

/** Base class for e2e specs.
  *
  * The `bootstrap` layer registers all shared clients/users once per spec run,
  * flushes the Central user outbox, and syncs the Auth configuration cache —
  * so individual tests start with a fully-populated environment.
  */
abstract class E2ESpec extends ZIOSpec[Flows.Setups]:
  override val bootstrap: ZLayer[Any, Any, Flows.Setups] =
    (E2EConfig.live ++ Client.default) >>> OAuthClient.live >>> Flows.layer

  def setup(id: Flows.Id): ZIO[Flows.Setups, Nothing, (Flows.Setup, OAuthClient)] =
    ZIO.serviceWith[Flows.Setups](s => s(id) -> s.client)
