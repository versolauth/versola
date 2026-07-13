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
        ORDER BY created_at DESC
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
        // Only permanent passwords count toward history and reuse checks
        val permanents = sql"""
          SELECT id, user_id, password, salt, created_at, expires_at
          FROM user_passwords
          WHERE user_id = $userId AND expires_at IS NULL
          ORDER BY created_at DESC
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

          if permanents.size >= historySize then
            sql"""DELETE FROM user_passwords WHERE id = ${permanents.last.id}
               """.update.run()
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
  def live: ZLayer[TransactorZIO, Throwable, PasswordRepository] =
    ZLayer.fromFunction(PostgresPasswordRepository(_))
