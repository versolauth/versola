package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresOAuthClientRepositorySpec extends PostgresSpec, OAuthClientRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield OAuthClientRepositorySpec.Env(PostgresOAuthClientRepository(xa))

  override def beforeEach(env: OAuthClientRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE oauth_clients".update.run())
    yield ()
