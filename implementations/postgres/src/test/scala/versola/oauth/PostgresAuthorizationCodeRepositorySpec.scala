package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresAuthorizationCodeRepositorySpec extends PostgresSpec, AuthorizationCodeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield AuthorizationCodeRepositorySpec.Env(PostgresAuthorizationCodeRepository(xa))

  override def beforeEach(env: AuthorizationCodeRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE authorization_codes".update.run())
    yield ()

