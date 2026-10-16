package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresUserIntentRepository(
    xa: TransactorZIO,
) extends UserIntentRepository, BasicCodecs:

  private given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  private given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[UserIntent] = jsonBCodec[UserIntent]

  override def create(userId: UserId, clientId: ClientId, intent: UserIntent, ttl: Duration): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("create-user-intent"):
        sql"""
          INSERT INTO user_intents (user_id, client_id, intent, expires_at)
          VALUES ($userId, $clientId, $intent, ${now.plusSeconds(ttl.toSeconds)})
          ON CONFLICT (user_id) DO UPDATE SET
            client_id = excluded.client_id,
            intent = excluded.intent,
            expires_at = excluded.expires_at
        """.update.run()
    .unit

  override def consume(userId: UserId, clientId: ClientId, intent: UserIntent): Task[Boolean] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("consume-user-intent"):
        sql"""
          DELETE FROM user_intents
          WHERE user_id = $userId AND client_id = $clientId AND intent = $intent AND expires_at > $now
        """.update.run()
    .map(_ > 0)

object PostgresUserIntentRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserIntentRepository] =
    ZLayer.fromFunction(PostgresUserIntentRepository(_))
