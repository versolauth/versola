package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.antifraud.AntifraudSettingsRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresAntifraudSettingsRepositorySpec extends PostgresSpec, AntifraudSettingsRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AntifraudSettingsRepositorySpec.Env(repository = PostgresAntifraudSettingsRepository(xa))

  override def beforeEach(env: AntifraudSettingsRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM antifraud_settings".update.run())
    yield ()
