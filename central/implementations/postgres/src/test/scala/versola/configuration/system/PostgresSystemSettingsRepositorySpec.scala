package versola.configuration.system

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.system.SystemSettingsRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresSystemSettingsRepositorySpec extends PostgresSpec, SystemSettingsRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield SystemSettingsRepositorySpec.Env(PostgresSystemSettingsRepository(xa))

  override def beforeEach(env: SystemSettingsRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE system_settings".update.run())
    }.unit
