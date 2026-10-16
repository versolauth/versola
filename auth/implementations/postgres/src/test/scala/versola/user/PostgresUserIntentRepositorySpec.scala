package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresUserIntentRepositorySpec extends PostgresSpec, UserIntentRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield UserIntentRepositorySpec.Env(PostgresUserIntentRepository(xa))

  override def beforeEach(env: UserIntentRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE user_intents".update.run())
    yield ()
