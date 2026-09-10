package versola.oauth.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresDpopProofRepositorySpec extends PostgresSpec, DpopProofRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield DpopProofRepositorySpec.Env(PostgresDpopProofRepository(xa))

  override def beforeEach(env: DpopProofRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE dpop_proofs".update.run())
    yield ()
