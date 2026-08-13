package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresPushedAuthorizationRepositorySpec extends PostgresSpec, PushedAuthorizationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield PushedAuthorizationRepositorySpec.Env(PostgresPushedAuthorizationRepository(xa))

  override def beforeEach(env: PushedAuthorizationRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE pushed_authorization_requests".update.run())
    yield ()
