package versola.oauth.challenge.password

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.PasswordRecord
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.user.model.UserId
import versola.util.{Salt, Secret}
import zio.prelude.EqualOps
import zio.{Clock, IO, Task}

import java.util.UUID

class PostgresPasswordRepository(xa: TransactorZIO) extends PasswordRepository:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Salt] = DbCodec.ByteArrayCodec.biMap(Salt(_), identity[Array[Byte]])
  given DbCodec[Secret] = DbCodec.ByteArrayCodec.biMap(Secret(_), identity[Array[Byte]])
  given DbCodec[PasswordRecord] = DbCodec.derived[PasswordRecord]

  override def list(userId: UserId): Task[Vector[PasswordRecord]] =
    xa.connect:
      sql"""
        SELECT id, user_id, password, salt, created_at, is_current
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
      result <- xa.transact {
        val oldPasswords = sql"""
          SELECT id, user_id, password, salt, created_at, is_current
          FROM user_passwords
          WHERE user_id = $userId ORDER BY DESC
        """.query[PasswordRecord].run()

        if oldPasswords.take(numDifferent).exists(_.password === password) then
          Left(PasswordReuseError(numDifferent))
        else {
          sql"""INSERT INTO user_passwords (user_id, password, salt, created_at)
                VALUES ($userId, $password, $salt, $now)
             """.update.run()

          if oldPasswords.size == historySize then
            sql"""DELETE FROM user_passwords WHERE id = ${oldPasswords.last.id}
               """.update.run()
          Right(())
        }
      }.absolve
    yield result
