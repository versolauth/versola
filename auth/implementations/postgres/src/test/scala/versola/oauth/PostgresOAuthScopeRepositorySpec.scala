package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.client.OAuthScopeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresOAuthScopeRepositorySpec extends PostgresSpec, OAuthScopeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield OAuthScopeRepositorySpec.Env(PostgresOAuthScopeRepository(xa))

  override def beforeEach(env: OAuthScopeRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE oauth_scopes".update.run())
    yield ()
