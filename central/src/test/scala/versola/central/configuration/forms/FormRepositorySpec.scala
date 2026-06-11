package versola.central.configuration.forms

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.ZIO
import zio.test.*

trait FormRepositorySpec extends DatabaseSpecBase[FormRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val en = FormLocale("en", "English")
  private val ru = FormLocale("ru", "Russian")
  private val fr = FormLocale("fr", "French")
  private val frUpdated = FormLocale("fr", "Français")

  override def testCases(env: FormRepositorySpec.Env) =
    List(
      test("getLocales returns empty list when no locales exist") {
        for
          locales <- env.repository.getLocales
        yield assertTrue(locales.isEmpty)
      },
      test("updateLocales adds new locales") {
        for
          _ <- env.repository.updateLocales(add = Vector(en, fr), delete = Vector.empty)
          locales <- env.repository.getLocales
        yield assertTrue(locales == Vector(en, fr))
      },
      test("updateLocales deletes specified locales") {
        for
          _ <- env.repository.updateLocales(add = Vector(en, fr), delete = Vector.empty)
          _ <- env.repository.updateLocales(add = Vector.empty, delete = Vector("fr"))
          locales <- env.repository.getLocales
        yield assertTrue(locales == Vector(en))
      },
      test("updateLocales upserts locale with updated name on conflict") {
        for
          _ <- env.repository.updateLocales(add = Vector(en, fr), delete = Vector.empty)
          _ <- env.repository.updateLocales(add = Vector(frUpdated), delete = Vector.empty)
          locales <- env.repository.getLocales
        yield assertTrue(locales == Vector(en, frUpdated))
      },
      test("updateLocales handles add and delete in the same call") {
        for
          _ <- env.repository.updateLocales(add = Vector(en, ru), delete = Vector.empty)
          _ <- env.repository.updateLocales(add = Vector(fr), delete = Vector("ru"))
          locales <- env.repository.getLocales
        yield assertTrue(locales == Vector(en, fr))
      },
      test("upsertForm persists js source, style and localizations and find returns them") {
        val formId = FormId("credential")
        val localizations = Map(
          "en" -> Map("title" -> "Sign in"),
          "ru" -> Map("title" -> "Вход"),
        )
        for
          _ <- env.repository.updateLocales(add = Vector(en, ru), delete = Vector.empty)
          _ <- env.repository.upsertForm(formId, ".a{}", Some("src"), Some("compiled"), localizations, Vector.empty, activate = true)
          found <- env.repository.find(formId, 1)
        yield assertTrue(
          found == Some(FormRecord(formId, 1, true, ".a{}", Some("src"), Some("compiled"), localizations, Vector.empty)),
        )
      },
      test("upsertForm increments version and keeps only the newest versions") {
        val formId = FormId("credential")
        for
          _ <- ZIO.foreachDiscard(1 to 6)(i => env.repository.upsertForm(formId, "", Some(s"src$i"), None, Map.empty, Vector.empty, activate = true))
          all <- env.repository.getAll
          versions = all.filter(_.id == formId).map(_.version).sorted
          first <- env.repository.find(formId, 1)
          latest <- env.repository.find(formId, 6)
        yield assertTrue(
          versions == Vector(2, 3, 4, 5, 6),
          first.isEmpty,
          latest.map(_.jsSource) == Some(Some("src6")),
          latest.map(_.active) == Some(true),
        )
      },
      test("first version is active, subsequent versions are not") {
        val formId = FormId("credential")
        for
          _ <- env.repository.upsertForm(formId, "", Some("v1"), None, Map.empty, Vector.empty, activate = false)
          _ <- env.repository.upsertForm(formId, "", Some("v2"), None, Map.empty, Vector.empty, activate = false)
          v1 <- env.repository.find(formId, 1)
          v2 <- env.repository.find(formId, 2)
        yield assertTrue(
          v1.map(_.active) == Some(true),
          v2.map(_.active) == Some(false),
        )
      },
      test("upsertForm with activate=true switches the active version") {
        val formId = FormId("credential")
        for
          _ <- env.repository.upsertForm(formId, "", Some("v1"), None, Map.empty, Vector.empty, activate = false)
          _ <- env.repository.upsertForm(formId, "", Some("v2"), None, Map.empty, Vector.empty, activate = true)
          v1 <- env.repository.find(formId, 1)
          v2 <- env.repository.find(formId, 2)
        yield assertTrue(
          v1.map(_.active) == Some(false),
          v2.map(_.active) == Some(true),
        )
      },
      test("deleting a locale strips its translations from existing forms") {
        val formId = FormId("credential")
        for
          _ <- env.repository.updateLocales(add = Vector(en, fr), delete = Vector.empty)
          _ <- env.repository.upsertForm(
            formId,
            "",
            Some("src"),
            None,
            Map("en" -> Map("title" -> "Sign in"), "fr" -> Map("title" -> "Connexion")),
            Vector.empty,
            activate = true,
          )
          _ <- env.repository.updateLocales(add = Vector.empty, delete = Vector("fr"))
          found <- env.repository.find(formId, 1)
        yield assertTrue(
          found.map(_.localizations) == Some(Map("en" -> Map("title" -> "Sign in"))),
        )
      },
    )

object FormRepositorySpec:
  case class Env(repository: FormRepository)
