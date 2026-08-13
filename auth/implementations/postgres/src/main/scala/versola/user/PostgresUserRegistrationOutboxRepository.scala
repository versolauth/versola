package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

import java.util.UUID

class PostgresUserRegistrationOutboxRepository(xa: TransactorZIO)
    extends UserRegistrationOutboxRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[UserRegisteredEvent] = jsonBCodec[UserRegisteredEvent]
  given DbCodec[UserRegistrationOutboxRecord] = DbCodec.derived[UserRegistrationOutboxRecord]

  /** Claims a batch by pushing `next_attempt_at` forward by the lease, keeping per-user FIFO
    * order so a user's events cannot be delivered out of sequence.
    */
  override def claimDueEvents(limit: Int, lease: Duration): Task[Vector[UserRegistrationOutboxRecord]] =
    val leaseSeconds = lease.toSeconds
    xa.connectMeasured("claim-due-registration-events"):
      sql"""UPDATE user_registration_outbox SET
              next_attempt_at = NOW() + ($leaseSeconds || ' seconds')::interval
            WHERE id IN (
              SELECT id FROM user_registration_outbox u1
              WHERE next_attempt_at <= NOW()
              AND NOT EXISTS (
                SELECT 1 FROM user_registration_outbox u2
                WHERE u2.user_id = u1.user_id
                AND u2.id < u1.id
              )
              ORDER BY id
              LIMIT $limit
              FOR UPDATE SKIP LOCKED
            )
            RETURNING id, user_id, payload, attempts"""
        .returning[UserRegistrationOutboxRecord]
        .run()

  override def deleteEvent(id: UUID): Task[Unit] =
    xa.connectMeasured("delete-registration-event"):
      sql"DELETE FROM user_registration_outbox WHERE id = $id".update.run()
    .unit

  override def rescheduleEvent(id: UUID, delay: Duration): Task[Unit] =
    val seconds = delay.toSeconds
    xa.connectMeasured("reschedule-registration-event"):
      sql"""UPDATE user_registration_outbox SET
              attempts = attempts + 1,
              next_attempt_at = NOW() + ($seconds || ' seconds')::interval
            WHERE id = $id""".update.run()
    .unit

  override def moveToDeadLetter(id: UUID, error: String): Task[Unit] =
    xa.transactMeasured("move-registration-event-to-dead-letter"):
      sql"""INSERT INTO user_registration_outbox_dead (id, user_id, payload, attempts, failed_at, error)
            SELECT id, user_id, payload, attempts, NOW(), $error
            FROM user_registration_outbox
            WHERE id = $id""".update.run()
      sql"DELETE FROM user_registration_outbox WHERE id = $id".update.run()
    .unit

object PostgresUserRegistrationOutboxRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserRegistrationOutboxRepository] =
    ZLayer.fromFunction(PostgresUserRegistrationOutboxRepository(_))
