package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresIdentifierChangeIntentRepositorySpec extends PostgresSpec, IdentifierChangeIntentRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield IdentifierChangeIntentRepositorySpec.Env(PostgresIdentifierChangeIntentRepository(xa))

  override def beforeEach(env: IdentifierChangeIntentRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE identifier_change_intents".update.run())
    yield ()
