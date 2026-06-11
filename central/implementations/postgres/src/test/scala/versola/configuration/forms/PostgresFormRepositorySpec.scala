package versola.configuration.forms

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.forms.FormRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresFormRepositorySpec extends PostgresSpec, FormRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield FormRepositorySpec.Env(PostgresFormRepository(xa))

  override def beforeEach(env: FormRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE form_locales, forms RESTART IDENTITY CASCADE".update.run())
    }.unit
