package versola.user.move

import versola.oauth.client.model.ClientId
import versola.oauth.model.RefreshToken
import versola.user.model.UserId
import versola.util.MAC
import zio.{IO, ZIO, ZLayer}

/** The question every request about one user asks before it reads or changes the user's rows: is this user leaving this cell,
  * or gone from it (versolauth/versola#1051)?
  *
  * One read by the key of `user_departures`, where the user is being looked at anyway. What a refusal is answered with is the
  * endpoint's to say: the sign-in step's "service unavailable", a `503` with `Retry-After`, a `421 moved`, or central's `409`.
  * A lookup that fails is a failure: a request is not served on a guess about a user that may be half way to another cell.
  */
trait UserDepartures:
  /** Fails with [[DepartureRefusal.Leaving]] or [[DepartureRefusal.Moved]] when there is a departure of the user. */
  def refuse(userId: UserId): IO[Throwable | DepartureRefusal, Unit]

  /** Fails with [[DepartureRefusal.Moved]] when `token` is one of a user that moved and was issued to `clientId`: to any other
    * client it is a token that does not exist. Asked only of a token this cell holds no record of, so a request with a token that
    * is here costs no read. */
  def refuseToken(token: MAC.Of[RefreshToken], clientId: ClientId): IO[Throwable | DepartureRefusal, Unit]

object UserDepartures:
  val live: ZLayer[UserDepartureRepository, Nothing, UserDepartures] = ZLayer.fromFunction(Impl(_))

  /** A cell that moves nobody: never refuses, reads nothing. */
  val none: UserDepartures = new UserDepartures:
    override def refuse(userId: UserId): IO[Throwable | DepartureRefusal, Unit] = ZIO.unit
    override def refuseToken(token: MAC.Of[RefreshToken], clientId: ClientId): IO[Throwable | DepartureRefusal, Unit] = ZIO.unit

  private class Impl(repository: UserDepartureRepository) extends UserDepartures:
    override def refuse(userId: UserId): IO[Throwable | DepartureRefusal, Unit] =
      repository.find(userId).flatMap:
        case None => ZIO.unit
        case Some(departure) =>
          departure.state match
            case DepartureState.Leaving => ZIO.fail(DepartureRefusal.Leaving)
            case DepartureState.Moved => ZIO.fail(DepartureRefusal.Moved(departure.toCell))

    override def refuseToken(token: MAC.Of[RefreshToken], clientId: ClientId): IO[Throwable | DepartureRefusal, Unit] =
      repository.findToken(token, clientId).flatMap:
        case None => ZIO.unit
        case Some(cell) => ZIO.fail(DepartureRefusal.Moved(cell))
