package versola.oauth.challenge.password

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.PasswordRecord
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.user.model.UserId
import versola.util.{Salt, Secret}
import versola.util.postgres.BasicCodecs
import zio.prelude.EqualOps
import zio.{Clock, IO, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasswordRepository(xa: TransactorZIO) extends PasswordRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Salt] = DbCodec.ByteArrayCodec.biMap(Salt(_), identity[Array[Byte]])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PasswordRecord] = DbCodec.derived[PasswordRecord]

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
      historySize: Int,
      numDifferent: Int,
  ): IO[Throwable | PasswordReuseError, Unit] =
    for
      now <- Clock.instant
      result <- xa.transactMeasured("create-password") {
        // Serialise concurrent password changes for the same user. The transaction runs at
        // READ COMMITTED, so FOR UPDATE alone cannot stop a second, concurrent change from
        // missing the not-yet-visible insert of the first (phantom read); it also would not
        // lock anything when the user has no history yet. Taking a per-user advisory lock as a
        // separate statement before the history SELECT forces the second transaction to wait,
        // then read a snapshot that already includes the first transaction's committed insert.
        // The lock is released automatically on commit/rollback.
        sql"SELECT 1 FROM pg_advisory_xact_lock(${PostgresPasswordRepository.PasswordHistoryLockNamespace}, hashtext(${userId.toString}))"
          .query[Int].run()

        // Only permanent passwords count toward history and reuse checks.
        // Order by id (SERIAL), not created_at: id is assigned at INSERT inside the serialized
        // section, so it reflects the true commit order. `now` is captured before the advisory
        // lock, so created_at values of two contending changes can be inverted relative to commit
        // order — ordering by id keeps reuse/prune correct under contention.
        val permanents = sql"""
          SELECT id, user_id, password, salt, created_at, expires_at
          FROM user_passwords
          WHERE user_id = $userId AND expires_at IS NULL
          ORDER BY id DESC
        """.query[PasswordRecord].run()

        if permanents.take(numDifferent).exists(_.password === password) then
          Left(PasswordReuseError(numDifferent))
        else {
          // Remove any temporary passwords so they are never part of history
          sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
             """.update.run()

          sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
                VALUES ($userId, $password, $salt, $now, NULL)
             """.update.run()

          val toDelete = permanents.drop(historySize - 1)
          if toDelete.nonEmpty then
            val ids = toDelete.map(_.id)
            sql"""DELETE FROM user_passwords WHERE id = ANY($ids)""".update.run()
          Right(())
        }
      }.absolve
    yield result

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
    * changes. Chosen per feature so advisory locks of unrelated features never collide; the value
    * matches issue #92. The second key is derived from the user id via `hashtext`.
    *
    * `hashtext` is 32-bit, so two distinct user ids can collide and briefly serialize each other's
    * password changes. This is an accepted trade-off: collisions are rare and only cost a short
    * wait, never a correctness issue.
    */
  private[password] val PasswordHistoryLockNamespace: Int = 92

  def live: ZLayer[TransactorZIO, Throwable, PasswordRepository] =
    ZLayer.fromFunction(PostgresPasswordRepository(_))
