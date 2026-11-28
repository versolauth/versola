package versola.user.move

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.model.RefreshToken
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresUserDepartureRepository(xa: TransactorZIO) extends UserDepartureRepository, BasicCodecs:

  private given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  private given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  private given DbCodec[Instant] = DbCodec.InstantCodec

  override def find(userId: UserId): Task[Option[UserDeparture]] =
    xa.connectMeasured("find-user-departure"):
      sql"SELECT to_cell, move_id, state, at FROM user_departures WHERE user_id = $userId"
        .query[(String, UUID, String, Instant)]
        .run()
        .headOption
        // A state this build does not know is not read as "not leaving".
        .map((toCell, moveId, state, at) =>
          UserDeparture(
            userId = userId,
            toCell = toCell,
            moveId = moveId,
            state = DepartureState.parse(state).getOrElse(throw IllegalStateException("unknown departure state")),
            at = at,
          ),
        )

  override def begin(userId: UserId, toCell: String, moveId: UUID, at: Instant): Task[Boolean] =
    xa.connectMeasured("begin-user-departure"):
      sql"""INSERT INTO user_departures (user_id, to_cell, move_id, state, at)
            VALUES ($userId, $toCell, $moveId, ${DepartureState.Leaving.value}, $at)
            ON CONFLICT (user_id) DO NOTHING""".update.run() > 0

  override def cancel(userId: UserId): Task[Boolean] =
    xa.connectMeasured("cancel-user-departure"):
      sql"DELETE FROM user_departures WHERE user_id = $userId AND state = ${DepartureState.Leaving.value}".update.run() > 0

  override def findToken(token: MAC.Of[RefreshToken], clientId: ClientId): Task[Option[String]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-departed-refresh-token"):
        sql"SELECT to_cell FROM departed_refresh_tokens WHERE id = $token AND client_id = ${clientId: String} AND expires_at > $now"
          .query[String]
          .run()
          .headOption

  override def summary: Task[DepartureSummary] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("user-departure-summary"):
        val (leaving, moved) =
          sql"""SELECT COUNT(*) FILTER (WHERE state = ${DepartureState.Leaving.value}),
                       COUNT(*) FILTER (WHERE state = ${DepartureState.Moved.value})
                FROM user_departures""".query[(Long, Long)].run().head
        val (tokens, until) =
          sql"SELECT COUNT(*), MAX(expires_at) FROM departed_refresh_tokens WHERE expires_at > $now"
            .query[(Long, Option[Instant])]
            .run()
            .head
        DepartureSummary(leaving = leaving, moved = moved, tokens = tokens, tokensUntil = until)

  override def removeExpiredTokens: Task[Int] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("remove-expired-departed-refresh-tokens"):
        sql"DELETE FROM departed_refresh_tokens WHERE expires_at <= $now".update.run()

object PostgresUserDepartureRepository:
  def live: ZLayer[TransactorZIO, Nothing, UserDepartureRepository] =
    ZLayer.fromFunction(PostgresUserDepartureRepository(_))
