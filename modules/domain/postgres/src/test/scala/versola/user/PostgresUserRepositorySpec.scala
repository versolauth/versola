package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresUserRepositorySpec extends PostgresSpec, UserRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield UserRepositorySpec.Env(PostgresUserRepository(xa, userIdGenerator))

  override def beforeEach(env: UserRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE users".update.run())
    yield ()


