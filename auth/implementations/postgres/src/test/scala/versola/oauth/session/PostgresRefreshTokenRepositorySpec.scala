package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresRefreshTokenRepositorySpec extends PostgresSpec, RefreshTokenRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield RefreshTokenRepositorySpec.Env(PostgresRefreshTokenRepository(xa))

  override def beforeEach(env: RefreshTokenRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()

