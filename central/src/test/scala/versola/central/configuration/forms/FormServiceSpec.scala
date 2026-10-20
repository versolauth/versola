package versola.central.configuration.forms

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.locales.{LocaleRecord, LocaleService}
import versola.central.configuration.sync.SyncEvent
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object FormServiceSpec extends ZIOSpecDefault, ZIOStubs:
  class Env(initialForms: Vector[FormRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initialForms)))
    val repository = stub[FormRepository]
    val localeService = stub[LocaleService]
    val translations = stub[FormTranslationRepository]
    val service = FormService.Impl(cache, repository, localeService, translations)

  private val translatedForm = FormRecord(
    FormId("f"),
    1,
    true,
    "style",
    None,
    None,
    Map("en" -> Map("title" -> "Hello", "hint" -> "Code {code}"), "ru" -> Map("title" -> "Привет", "hint" -> "Код {code}")),
    Vector.empty,
  )
  private def activeLocale(code: String, active: Boolean = true) = LocaleRecord(code, code, isDefault = code == "en", active = active)

  def spec = suite("FormService")(
    test("updateForm stores through the repository and leaves the cache to the change notification") {
      val env = new Env()
      val formId = FormId("test")
      val concurrent = FormRecord(FormId("other"), 1, false, "style", None, None, Map.empty, Vector.empty)

      for
        _ <- env.repository.upsertForm.succeedsWith(())
        // A notification applied between the store and the end of the call must survive it.
        _ <- env.cache.set(Vector(concurrent))
        _ <- env.service.updateForm(formId, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty, activate = false)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsertForm.calls == List((formId, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty, false)),
        env.repository.getAll.calls.isEmpty,
        cached == Vector(concurrent),
      )
    },
    test("updateForm refuses texts whose languages disagree and stores nothing") {
      val env = new Env()
      val ragged = Map("en" -> Map("title" -> "Hello", "hint" -> "Code {code}"), "ru" -> Map("title" -> "Привет"))

      for
        result <- env.service.updateForm(FormId("test"), "style", None, None, ragged, Vector.empty, activate = false).either
      yield assertTrue(
        result.left.exists(_.isInstanceOf[IllegalArgumentException]),
        result.left.exists(_.getMessage == "Locale 'ru' has no text for 'hint', which 'en' has"),
        env.repository.upsertForm.calls.isEmpty,
      )
    },
    test("getSyncForms excludes inactive forms") {
      val activeForm = FormRecord(FormId("a"), 1, true, "style", None, None, Map("en" -> Map("k" -> "v")), Vector.empty)
      val inactiveForm = FormRecord(FormId("b"), 1, false, "style", None, None, Map("en" -> Map("k" -> "v")), Vector.empty)
      val env = new Env(Vector(activeForm, inactiveForm))

      for
        _ <- env.translations.getAll.succeedsWith(Vector.empty)
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncForms
      yield assertTrue(result.map(_.id) == Vector(FormId("a")))
    },
    test("getSyncForms strips localizations of inactive locales") {
      val form = FormRecord(
        FormId("f"),
        1,
        true,
        "style",
        None,
        None,
        Map("en" -> Map("title" -> "Hello"), "fr" -> Map("title" -> "Bonjour")),
        Vector.empty,
      )
      val env = new Env(Vector(form))

      for
        _ <- env.translations.getAll.succeedsWith(Vector.empty)
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncForms
      yield assertTrue(result.head.localizations == Map("en" -> Map("title" -> "Hello")))
    },
    suite("getSyncForms lays the stored translations over the form's own texts")(
      test("a language only the store carries is served, whole when the store has every key") {
        val env = new Env(Vector(translatedForm))
        val kk = FormTranslationRecord(formId = FormId("f"), locale = "kk", texts = Map("title" -> "Сәлем", "hint" -> "Код {code}"))
        for
          _ <- env.translations.getAll.succeedsWith(Vector(kk))
          _ <- env.localeService.getActive.succeedsWith(Vector(activeLocale("en"), activeLocale("ru"), activeLocale("kk")))
          result <- env.service.getSyncForms
        yield assertTrue(result.head.localizations("kk") == kk.texts, result.head.localizations("en") == translatedForm.localizations("en"))
      },
      test("a key the store sets wins, and a key it leaves out keeps the form's") {
        val env = new Env(Vector(translatedForm))
        val ru = FormTranslationRecord(formId = FormId("f"), locale = "ru", texts = Map("title" -> "Здравствуйте"))
        for
          _ <- env.translations.getAll.succeedsWith(Vector(ru))
          _ <- env.localeService.getActive.succeedsWith(Vector(activeLocale("en"), activeLocale("ru")))
          result <- env.service.getSyncForms
        yield assertTrue(result.head.localizations("ru") == Map("title" -> "Здравствуйте", "hint" -> "Код {code}"))
      },
      test("a translation of another form, or into a language that is not active, is not served") {
        val env = new Env(Vector(translatedForm))
        for
          _ <- env.translations.getAll.succeedsWith(
            Vector(
              FormTranslationRecord(formId = FormId("other"), locale = "kk", texts = Map("title" -> "x")),
              FormTranslationRecord(formId = FormId("f"), locale = "kk", texts = Map("title" -> "y")),
            ),
          )
          _ <- env.localeService.getActive.succeedsWith(Vector(activeLocale("en"), activeLocale("ru")))
          result <- env.service.getSyncForms
        yield assertTrue(result.head.localizations.keySet == Set("en", "ru"))
      },
    ),
    test("getAllForms returns all forms sorted by id") {
      val formB = FormRecord(FormId("b"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val formA = FormRecord(FormId("a"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val env = new Env(Vector(formB, formA))

      for result <- env.service.getAllForms
      yield assertTrue(result == Vector(formA, formB))
    },
    test("setActiveVersion delegates to the repository without replacing the cache") {
      val env = new Env()
      val formId = FormId("test")
      val record = FormRecord(formId, 1, true, "style", None, None, Map.empty, Vector.empty)

      for
        _ <- env.repository.setActiveVersion.succeedsWith(())
        _ <- env.cache.set(Vector(record))
        _ <- env.service.setActiveVersion(formId, 2)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.setActiveVersion.calls == List((formId, 2)),
        env.repository.getAll.calls.isEmpty,
        cached == Vector(record),
      )
    },
    test("sync removes cached form on delete event") {
      val formA = FormRecord(FormId("a"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val formB = FormRecord(FormId("b"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val env = new Env(Vector(formA, formB))

      for
        _ <- env.service.sync(SyncEvent.FormsUpdated(formA.id, formA.version, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached == Vector(formB))
    },
    test("sync upserts fetched form for non-delete event") {
      val formA = FormRecord(FormId("a"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val env = new Env(Vector(formA))
      val updatedForm = formA.copy(active = false)

      for
        _ <- env.repository.find.succeedsWith(Some(updatedForm))
        _ <- env.service.sync(SyncEvent.FormsUpdated(formA.id, formA.version, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.find.calls == List((formA.id, formA.version)),
        cached == Vector(updatedForm),
      )
    },
    test("sync removes cached form when record is missing on non-delete event") {
      val formA = FormRecord(FormId("a"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val env = new Env(Vector(formA))

      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.FormsUpdated(formA.id, formA.version, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.find.calls == List((formA.id, formA.version)),
        cached == Vector.empty,
      )
    },
  )
