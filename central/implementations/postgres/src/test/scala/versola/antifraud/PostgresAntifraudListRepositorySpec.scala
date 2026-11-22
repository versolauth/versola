package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.antifraud.AntifraudListRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresAntifraudListRepositorySpec extends PostgresSpec, AntifraudListRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AntifraudListRepositorySpec.Env(repository = PostgresAntifraudListRepository(xa))

  override def beforeEach(env: AntifraudListRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"DELETE FROM antifraud_list_entries".update.run()
        sql"DELETE FROM antifraud_list_audit".update.run()
        sql"UPDATE antifraud_list_version SET version = 0".update.run()
    yield ()
