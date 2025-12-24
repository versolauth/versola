package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresTokenRepositorySpec extends PostgresSpec, TokenRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield TokenRepositorySpec.Env(PostgresTokenRepository(xa))

  override def beforeEach(env: TokenRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE access_tokens, refresh_tokens".update.run())
    yield ()

