package versola.central.configuration.locales

import com.augustnagro.magnum.magzio.TransactorZIO
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
  case class Env(repository: LocaleRepository)
