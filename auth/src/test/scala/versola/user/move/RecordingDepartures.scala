package versola.user.move

import versola.oauth.client.model.ClientId
import versola.oauth.model.RefreshToken
import versola.user.model.UserId
import versola.util.MAC
import zio.{IO, Ref, UIO, ZIO}

/** The departures of a cell, as a spec of an endpoint says them: which users are leaving or gone, and where the tokens that moved
  * went. It records what it was asked, so that a request that must not cost a read is shown not to. */
final class RecordingDepartures private (
    refusals: Map[UserId, DepartureRefusal],
    tokensTo: Option[String],
    private val log: Ref[List[String]],
    private val clients: Ref[List[ClientId]],
) extends UserDepartures:

  override def refuse(userId: UserId): IO[Throwable | DepartureRefusal, Unit] =
    log.update(_ :+ s"user:$userId") *> ZIO.foreachDiscard(refusals.get(userId))(ZIO.fail(_))

  override def refuseToken(token: MAC.Of[RefreshToken], clientId: ClientId): IO[Throwable | DepartureRefusal, Unit] =
    log.update(_ :+ "token") *> clients.update(_ :+ clientId) *> ZIO.foreachDiscard(tokensTo)(cell => ZIO.fail(DepartureRefusal.Moved(cell)))

  /** What was asked, in order: `user:<id>` and `token`. */
  def asked: UIO[List[String]] = log.get

  /** The clients a token was asked for, in order. */
  def askedFor: UIO[List[ClientId]] = clients.get

object RecordingDepartures:
  def make(refusals: Map[UserId, DepartureRefusal], tokensTo: Option[String]): UIO[RecordingDepartures] =
    for
      log <- Ref.make(List.empty[String])
      clients <- Ref.make(List.empty[ClientId])
    yield RecordingDepartures(refusals, tokensTo, log, clients)
