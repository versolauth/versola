package versola.oauth.challenge.passkey

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.PasskeyEnrollmentState
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasskeyEnrollmentRepository(xa: TransactorZIO) extends PasskeyEnrollmentRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PasskeyEnrollmentState] = DbCodec.derived[PasskeyEnrollmentState]

  override def find(userId: UserId): Task[Option[PasskeyEnrollmentState]] =
    xa.connectMeasured("find-passkey-enrollment"):
      sql"SELECT user_id, first_offered_at, skips FROM passkey_enrollment WHERE user_id = $userId"
        .query[PasskeyEnrollmentState]
        .run()
        .headOption

  override def recordOffer(userId: UserId, now: Instant): Task[Unit] =
    xa.connectMeasured("record-passkey-offer"):
      sql"""
        INSERT INTO passkey_enrollment (user_id, first_offered_at, skips, updated_at)
        VALUES ($userId, $now, 0, $now)
        ON CONFLICT (user_id) DO NOTHING
      """.update.run()
    .unit

  override def recordSkip(userId: UserId, now: Instant): Task[Unit] =
    xa.connectMeasured("record-passkey-skip"):
      sql"""
        INSERT INTO passkey_enrollment (user_id, first_offered_at, skips, updated_at)
        VALUES ($userId, $now, 1, $now)
        ON CONFLICT (user_id) DO UPDATE SET skips = passkey_enrollment.skips + 1, updated_at = EXCLUDED.updated_at
      """.update.run()
    .unit

object PostgresPasskeyEnrollmentRepository:
  def live: ZLayer[TransactorZIO, Throwable, PasskeyEnrollmentRepository] =
    ZLayer.fromFunction(PostgresPasskeyEnrollmentRepository(_))
