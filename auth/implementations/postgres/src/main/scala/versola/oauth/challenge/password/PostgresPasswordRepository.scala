package versola.oauth.challenge.password

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.PasswordRecord
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import versola.util.{Salt, Secret}
import zio.prelude.EqualOps
import zio.{Clock, IO, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasswordRepository(xa: TransactorZIO) extends PasswordRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Salt] = DbCodec.ByteArrayCodec.biMap(Salt(_), identity[Array[Byte]])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PasswordRecord] = DbCodec.derived[PasswordRecord]

  // Note: this read-only listing orders by created_at (the logical "password set" time) for
  // display. The reuse/prune read in `create` deliberately orders by id (physical commit order)
  // instead, because it must stay correct under concurrent changes where created_at can be
  // non-monotonic relative to insert order. The two only diverge under clock skew.
  override def list(userId: UserId): Task[Vector[PasswordRecord]] =
    xa.connectMeasured("list-passwords"):
      sql"""
        SELECT id, user_id, password, salt, created_at, expires_at
        FROM user_passwords
        WHERE user_id = $userId
        ORDER BY created_at DESC, id DESC
      """.query[PasswordRecord]
        .run()

  override def create(
      userId: UserId,
      password: Secret,
      salt: Salt,
  ): Task[Unit] =
    for
      now <- Clock.instant
      _ <- xa.transactMeasured("create-password") {
        // Serialise concurrent password changes for the same user: without the lock two changes could both
        // delete and then both insert, leaving the user with two passwords. The lock is per user (the
        // advisory key), taken as its own statement, and released on commit or rollback.
        sql"SELECT 1 FROM pg_advisory_xact_lock(${PostgresPasswordRepository.PasswordLockNamespace}, hashtext(${userId.toString}))"
          .query[Int].run()

        sql"""DELETE FROM user_passwords WHERE user_id = $userId""".update.run()

        sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
              VALUES ($userId, $password, $salt, $now, NULL)
           """.update.run()
      }
    yield ()

  override def createTemporary(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("create-temporary-password") {
        // Remove any existing temporary for this user (at most one temp at a time)
        sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
           """.update.run()

        sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
              VALUES ($userId, $passwordHash, $salt, $now, $expiresAt)
           """.update.run()
      }.unit

  override def deleteTemporary(userId: UserId): Task[Unit] =
    xa.connectMeasured("delete-temporary-password"):
      sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
         """.update.run()
    .unit

object PostgresPasswordRepository:
  /** Namespace (first key) for the transaction-level advisory lock guarding password history
    * changes. Advisory-lock keys are global to the entire database — shared across every table,
    * feature, and service on it — so this value must be unique DB-wide, not just within this
    * module. It is `92` (the issue number) by convention; if more advisory locks are introduced,
    * move these into a single shared registry to guarantee uniqueness. The second key is derived
    * from the user id via `hashtext`.
    *
    * `hashtext` is 32-bit, so two distinct user ids can collide and briefly serialize each other's
    * password changes. This is an accepted trade-off: collisions are rare and only cost a short
    * wait, never a correctness issue.
    */
  private[password] val PasswordLockNamespace: Int = 92

  def live: ZLayer[TransactorZIO, Throwable, PasswordRepository] =
    ZLayer.fromFunction(PostgresPasswordRepository(_))
