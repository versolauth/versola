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
      xa.connect(sql"TRUNCATE TABLE user_passwords, one_time_links".update.run())
    }.unit

  override def implementationCases(
      env: PasswordRepositorySpec.Env,
  ): List[Spec[PasswordRepositorySpec.Env & TransactorZIO & Scope, Any]] =
    List(
      // `create` deletes the user's tokens, which is why "setting a permanent password kills the token" would pass
      // without the guard in `consumeLink`. This is the case the guard is for: a permanent password that
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
          viaToken <- consume(env, token(1))
          leftBehind <- xa.connect(sql"SELECT count(*) FROM one_time_links".query[Int].run().head)
        yield assertTrue(viaToken.isEmpty, leftBehind == 1)
      },
      // A purpose is part of the schema, and an older release may meet a row a newer one wrote: it must neither
      // spend it nor treat it as the bootstrap link.
      test("a link of a purpose this release does not know is not spent, and the first-login deletes leave it alone") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- TestClock.setTime(baseInstant)
          _ <- xa.connect(
            sql"""INSERT INTO one_time_links (user_id, purpose, token_hash, created_at, expires_at)
                  VALUES ('a1000000-0000-0000-0000-000000000001', 'from_a_newer_release', decode('0909', 'hex'), now(), now() + interval '1 hour')""".update
              .run(),
          )
          _ <- bootstrap(env, userId1, token(1))
          _ <- env.repository.resetBootstrapCredential(userId1)
          _ <- env.repository.createTemporary(userId1, pass(2), salt(2), baseInstant.plusSeconds(3600))
          _ <- createChecked(env, userId1, pass(3), salt(3), 5, 1)
          spent <- consume(env, Array[Byte](9, 9))
          left <- xa.connect(sql"SELECT count(*) FROM one_time_links".query[Int].run().head)
        yield assertTrue(spent.isEmpty, left == 1)
      },
    )
