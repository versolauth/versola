package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{Scope, ZIO, ZLayer}

object PostgresPasswordRepositorySpec extends PostgresSpec, PasswordRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasswordRepositorySpec.Env(PostgresPasswordRepository(xa))

  override def beforeEach(env: PasswordRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE user_passwords, first_login_tokens".update.run())
    }.unit

  override def implementationCases(
      env: PasswordRepositorySpec.Env,
  ): List[Spec[PasswordRepositorySpec.Env & TransactorZIO & Scope, Any]] =
    List(
      // `create` deletes the user's tokens, which is why "setting a permanent password kills the token" would pass
      // without the guard in `consumeFirstLoginToken`. This is the case the guard is for: a permanent password that
      // got in without going through `create` (a previous release that did not know the table, then a roll-forward).
      test("a token is refused for a user who has a permanent password, even if the token row was left behind") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- TestClock.setTime(baseInstant)
          _ <- bootstrap(env, userId1, token(1))
          _ <- xa.connect(
            sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
                  VALUES ('a1000000-0000-0000-0000-000000000001', decode('01', 'hex'), decode('01', 'hex'), now(), NULL)""".update.run(),
          )
          viaToken <- env.repository.consumeFirstLoginToken(token(1))
          leftBehind <- xa.connect(sql"SELECT count(*) FROM first_login_tokens".query[Int].run().head)
        yield assertTrue(viaToken.isEmpty, leftBehind == 1)
      },
    )
