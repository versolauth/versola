package versola.central.configuration.locales

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.DatabaseSpecBase
import zio.test.*

trait LocaleRepositorySpec extends DatabaseSpecBase[LocaleRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val en = LocaleRecord("en", "English", isDefault = false, active = true)
  private val ru = LocaleRecord("ru", "Russian", isDefault = false, active = true)
  private val fr = LocaleRecord("fr", "French", isDefault = false, active = true)

  override def testCases(env: LocaleRepositorySpec.Env) =
    List(
      test("update adds locales and getAll returns them ordered by code") {
        for
          _ <- env.repository.update(add = Vector(ru, en), delete = Vector.empty)
          all <- env.repository.getAll
        yield assertTrue(all.map(_.code) == Vector("en", "ru"))
      },
      test("update removes a locale") {
        for
          _ <- env.repository.update(add = Vector(en, ru, fr), delete = Vector.empty)
          _ <- env.repository.update(add = Vector.empty, delete = Vector("ru"))
          all <- env.repository.getAll
        yield assertTrue(all.map(_.code) == Vector("en", "fr"))
      },
      test("update delete removes locale key from forms localizations") {
        for
          _ <- env.repository.update(add = Vector(en, ru), delete = Vector.empty)
          _ <- env.xa.connect:
            sql"""INSERT INTO forms (id, version, active, style, js_source, js_compiled, localizations, properties)
                  VALUES ('credential', 1, true, '', null, null, '{"en":{"title":"Sign in"},"ru":{"title":"Вход"}}', '{}')""".update.run()
          _ <- env.repository.update(add = Vector.empty, delete = Vector("ru"))
          locs <- env.xa.connect:
            sql"""SELECT localizations::text FROM forms WHERE id = 'credential' AND version = 1""".query[String].run()
        yield assertTrue(
          locs.headOption.exists(l => l.contains(""""en"""") && !l.contains(""""ru"""")),
        )
      },
      test("update delete removes locale key from otp_templates localizations") {
        for
          _ <- env.repository.update(add = Vector(en, ru), delete = Vector.empty)
          _ <- env.xa.connect:
            sql"""INSERT INTO tenants (id, description) VALUES ('t1', 'Test Tenant')""".update.run()
          _ <- env.xa.connect:
            sql"""INSERT INTO otp_templates (id, tenant_id, localizations)
                  VALUES ('email', 't1', '{"en":{"subject":"Code"},"ru":{"subject":"Код"}}')""".update.run()
          _ <- env.repository.update(add = Vector.empty, delete = Vector("ru"))
          locs <- env.xa.connect:
            sql"""SELECT localizations::text FROM otp_templates WHERE id = 'email' AND tenant_id = 't1'""".query[String].run()
        yield assertTrue(
          locs.headOption.exists(l => l.contains(""""en"""") && !l.contains(""""ru"""")),
        )
      },
      test("update batch-deletes multiple locale keys from forms localizations") {
        for
          _ <- env.repository.update(add = Vector(en, ru, fr), delete = Vector.empty)
          _ <- env.xa.connect:
            sql"""INSERT INTO forms (id, version, active, style, js_source, js_compiled, localizations, properties)
                  VALUES ('credential', 1, true, '', null, null, '{"en":{"title":"Sign in"},"ru":{"title":"Вход"},"fr":{"title":"Connexion"}}', '{}')""".update.run()
          _ <- env.repository.update(add = Vector.empty, delete = Vector("ru", "fr"))
          locs <- env.xa.connect:
            sql"""SELECT localizations::text FROM forms WHERE id = 'credential' AND version = 1""".query[String].run()
        yield assertTrue(
          locs.headOption.exists(l => l.contains(""""en"""") && !l.contains(""""ru"""") && !l.contains(""""fr"""")),
        )
      },
      test("setDefault marks only the given locale as default") {
        for
          _ <- env.repository.update(add = Vector(en, ru), delete = Vector.empty)
          _ <- env.repository.setDefault("en")
          allAfterFirst <- env.repository.getAll
          _ <- env.repository.setDefault("ru")
          allAfterSecond <- env.repository.getAll
        yield assertTrue(
          allAfterFirst.find(_.code == "en").exists(_.isDefault),
          !allAfterFirst.find(_.code == "ru").exists(_.isDefault),
          allAfterSecond.find(_.code == "ru").exists(_.isDefault),
          !allAfterSecond.find(_.code == "en").exists(_.isDefault),
        )
      },
      test("update with upsert does not overwrite is_default flag") {
        for
          _ <- env.repository.update(add = Vector(en), delete = Vector.empty)
          _ <- env.repository.setDefault("en")
          _ <- env.repository.update(add = Vector(en.copy(name = "English (updated)")), delete = Vector.empty)
          all <- env.repository.getAll
        yield assertTrue(
          all.find(_.code == "en").exists(rec => rec.isDefault && rec.name == "English (updated)")
        )
      },
      test("update persists active = false and getAll returns it") {
        for
          _ <- env.repository.update(add = Vector(en.copy(active = false)), delete = Vector.empty)
          all <- env.repository.getAll
        yield assertTrue(all.find(_.code == "en").exists(!_.active))
      },
    )

object LocaleRepositorySpec:
  case class Env(repository: LocaleRepository, xa: TransactorZIO)
