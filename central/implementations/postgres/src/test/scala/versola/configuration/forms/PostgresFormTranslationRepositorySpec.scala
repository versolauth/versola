package versola.configuration.forms

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.forms.FormTranslationRepositorySpec
import versola.central.configuration.locales.LocaleRecord
import versola.configuration.locales.PostgresLocaleRepository
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresFormTranslationRepositorySpec extends PostgresSpec, FormTranslationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield FormTranslationRepositorySpec.Env(PostgresFormTranslationRepository(xa), PostgresLocaleRepository(xa), xa)

  // A translation belongs to a registered locale, so the languages the tests use are registered first.
  override def beforeEach(env: FormTranslationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE locales, forms, form_translations RESTART IDENTITY CASCADE".update.run())
    } *> env.locales.update(
      add = Vector("kk", "ru").map(code => LocaleRecord(code = code, name = code, isDefault = false, active = true)),
      delete = Vector.empty,
    )
