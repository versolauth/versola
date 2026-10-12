package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.identifier.{IdentifierChangeIntentRepository, IdentifierChangeKind}
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import versola.util.{Email, Phone}
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresIdentifierChangeIntentRepository(
    xa: TransactorZIO,
) extends IdentifierChangeIntentRepository, BasicCodecs:

  private given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  private given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[IdentifierChangeKind] = DbCodec.StringCodec.biMap(
    {
      case "phone" => IdentifierChangeKind.Phone
      case _ => IdentifierChangeKind.Email
    },
    {
      case IdentifierChangeKind.Phone => "phone"
      case IdentifierChangeKind.Email => "email"
    },
  )

  override def create(
      userId: UserId,
      clientId: ClientId,
      kind: IdentifierChangeKind,
      credential: Either[Email, Phone],
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      val value: String = credential.merge
      xa.connectMeasured("create-identifier-change-intent"):
        sql"""
          INSERT INTO identifier_change_intents (user_id, client_id, kind, value, expires_at)
          VALUES ($userId, $clientId, $kind, $value, ${now.plusSeconds(ttl.toSeconds)})
          ON CONFLICT (user_id) DO UPDATE SET
            client_id = excluded.client_id,
            kind = excluded.kind,
            value = excluded.value,
            expires_at = excluded.expires_at
        """.update.run()
    .unit

  override def consume(
      userId: UserId,
      clientId: ClientId,
      kind: IdentifierChangeKind,
      credential: Either[Email, Phone],
  ): Task[Boolean] =
    Clock.instant.flatMap: now =>
      val value: String = credential.merge
      xa.connectMeasured("consume-identifier-change-intent"):
        sql"""
          DELETE FROM identifier_change_intents
          WHERE user_id = $userId AND client_id = $clientId AND kind = $kind AND value = $value
            AND expires_at > $now
        """.update.run()
    .map(_ > 0)

object PostgresIdentifierChangeIntentRepository:
  def live: ZLayer[TransactorZIO, Throwable, IdentifierChangeIntentRepository] =
    ZLayer.fromFunction(PostgresIdentifierChangeIntentRepository(_))
