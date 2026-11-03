package versola.central.antifraud

import versola.central.users.AuthClient
import zio.{IO, ZLayer}

/** Clears antifraud counters: the support path for "my number gets no code" and for a range that was closed. The counters
  * are auth's, which validates the request (a value goes with its kind, a phone is hashed there), clears the memory of every
  * instance and the stored rows, and records who did it; central passes the admin's request on with `userId`. */
trait AntifraudCountersService:
  def reset(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse]

object AntifraudCountersService:
  val live: ZLayer[AuthClient, Nothing, AntifraudCountersService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudCountersService:
    override def reset(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse] =
      authClient.resetAntifraudCounters(request)
