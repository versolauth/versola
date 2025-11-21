package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresBanRepositorySpec extends PostgresSpec, BanRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield BanRepositorySpec.Env(PostgresBanRepository(xa))

  override def beforeEach(env: BanRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE bans".update.run())
    yield ()
