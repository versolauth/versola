package versola.edge

import versola.edge.model.{ClientCredential, OAuthClient}
import zio.*

/** Warms the mutual-TLS call at start, so the first login after a restart does not pay for it.
  *
  * A fresh edge built its TLS context and opened its first connection to auth inside the first
  * login that needed a certificate: ~0.5 s on a laptop, against ~30 ms for every login after it.
  * Nothing about that is specific to the user who happens to come first, so it is done here, in the
  * background, instead.
  *
  * One client, not all of them. What is slow is mostly shared (class loading, the TLS stack's first
  * use), and what is per client -- its own context and connection -- is what a warm-up of every one
  * would open for each of them at every start, whether or not anyone ever signs in with it. The
  * first by id is chosen so the choice does not change from one start to the next; a client
  * that is not that one still pays its own part at its first login, as before.
  *
  * Best effort, and never in the way: forked, bounded, and a failure is logged and dropped. The
  * login this is for does not depend on it.
  *
  * What it does not cover: a client central sends after this edge started is not warmed, and one
  * whose certificate is replaced by a renewal builds its context again at its next login. Auth
  * answers the request with `401 invalid_request` and logs it at INFO, once per start of this edge.
  */
object SSOClientWarmUp:

  /** Long enough for a cold handshake on a slow link, short enough that a hung auth does not hold a
    * fibre for the life of the process. */
  private[edge] val Timeout: Duration = 30.seconds

  val live: ZLayer[OAuthClientService & SSOClient & Scope, Nothing, Unit] =
    ZLayer.fromZIO(
      (for
        clients <- ZIO.serviceWithZIO[OAuthClientService](_.listClients)
        sso <- ZIO.service[SSOClient]
        _ <- warmUp(sso, clients)
      yield ()).forkScoped.unit,
    )

  private[edge] def warmUp(sso: SSOClient, clients: Seq[OAuthClient]): UIO[Unit] =
    clients
      .filter(_.credential.isInstanceOf[ClientCredential.MutualTls])
      .minByOption(_.id.toString)
      .fold(ZIO.unit): client =>
        sso.warmUp(client)
          .timeoutFail(new RuntimeException(s"no answer within $Timeout"))(Timeout)
          .catchAll(error =>
            ZIO.logWarningCause(
              s"Could not warm up the mutual-TLS connection for client '${client.id}'; its first login pays for it instead",
              Cause.fail(error),
            ),
          )
