package versola.central.antifraud

import versola.central.users.AuthClient
import zio.{IO, ZLayer}

/** What an operator reads of the antifraud: the names a rule can be fired under (for the console and for checking the
  * settings) and the state of an auth instance. Both are auth's, central passes them on. */
trait AntifraudStatusService:
  def rules: IO[AntifraudListError | Throwable, AntifraudRulesResponse]

  def status: IO[AntifraudListError | Throwable, AntifraudStatusResponse]

object AntifraudStatusService:
  val live: ZLayer[AuthClient, Nothing, AntifraudStatusService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudStatusService:
    override def rules: IO[AntifraudListError | Throwable, AntifraudRulesResponse] = authClient.antifraudRules

    override def status: IO[AntifraudListError | Throwable, AntifraudStatusResponse] = authClient.antifraudStatus
