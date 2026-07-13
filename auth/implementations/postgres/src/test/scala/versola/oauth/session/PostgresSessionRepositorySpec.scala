package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*


object PostgresSessionRepositorySpec extends PostgresSpec, SessionRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield SessionRepositorySpec.Env(PostgresSessionRepository(xa))

  override def beforeEach(env: SessionRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE sso_sessions".update.run())
      _ <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()
