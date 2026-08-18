package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.consent.{ConsentRepositorySpec, PostgresConsentRepository}
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresConsentRepositorySpec extends PostgresSpec, ConsentRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield ConsentRepositorySpec.Env(PostgresConsentRepository(xa))

  override def beforeEach(env: ConsentRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE user_consents".update.run())
      _ <- xa.connect(
        sql"""INSERT INTO users (id, claims)
              VALUES
                ('00000000-0000-7000-8000-000000000001'::uuid, '{}'::jsonb),
                ('00000000-0000-7000-8000-000000000002'::uuid, '{}'::jsonb)
              ON CONFLICT (id) DO NOTHING""".update.run()
      )
    yield ()
