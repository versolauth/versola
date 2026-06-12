package versola.configuration.locales

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.locales.LocaleRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresLocaleRepositorySpec extends PostgresSpec, LocaleRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield LocaleRepositorySpec.Env(PostgresLocaleRepository(xa))

  override def beforeEach(env: LocaleRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE locales RESTART IDENTITY CASCADE".update.run())
    }.unit
