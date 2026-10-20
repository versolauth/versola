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
    val service = FormService.Impl(cache, repository, localeService, Unsafe.unsafe(unsafe ?=> Semaphore.unsafe.make(1)(using unsafe)))

  def spec = suite("FormService")(
    test("updateForm delegates to repository upsertForm and refreshes cache") {
      val env = new Env()
      val formId = FormId("test")
      val record = FormRecord(formId, 1, true, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty)

      for
        _ <- env.repository.upsertForm.succeedsWith(())
        _ <- env.repository.getAll.succeedsWith(Vector(record))
        _ <- env.service.updateForm(formId, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty, activate = false)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsertForm.calls == List((formId, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty, false)),
        cached == Vector(record),
      )
    },
    test("two updates at once both end up in the cache, whichever reads the repository first") {
      val formA = FormId("a")
      val formB = FormId("b")
      def record(id: FormId) =
        FormRecord(
          id = id,
          version = 1,
          active = false,
          style = "style",
          jsSource = None,
          jsCompiled = None,
          localizations = Map.empty,
          properties = Vector.empty,
        )

      for
        stored <- Ref.make(Vector.empty[FormRecord])
        reads <- Ref.make(0)
        // The first read is slow, so its snapshot is the older one and arrives last unless writes are serialised.
        repository = new FormRepository:
          def getAll = reads.getAndUpdate(_ + 1).flatMap(n => stored.get <* ZIO.sleep(if n == 0 then 2.seconds else Duration.Zero))
          def find(id: FormId, version: Int) = ZIO.none
          def upsertForm(
              id: FormId,
              style: String,
              jsSource: Option[String],
              jsCompiled: Option[String],
              localizations: Map[String, Map[String, String]],
              properties: Vector[BackendProperty],
              activate: Boolean,
          ) = stored.update(_ :+ record(id))
          def setActiveVersion(id: FormId, version: Int) = ZIO.unit
        cache <- Ref.make(Vector.empty[FormRecord]).map(ReloadingCache(_))
        writes <- Semaphore.make(1)
        service = FormService.Impl(cache = cache, repository = repository, localeService = stub[LocaleService], writes = writes)
        first <- service.updateForm(formA, "style", None, None, Map.empty, Vector.empty, activate = false).fork
        _ <- ZIO.yieldNow.repeatN(10)
        second <- service.updateForm(formB, "style", None, None, Map.empty, Vector.empty, activate = false).fork
        _ <- ZIO.yieldNow.repeatN(10)
        _ <- TestClock.adjust(3.seconds)
        _ <- first.join *> second.join
        cached <- cache.get
      yield assertTrue(cached.map(_.id).toSet == Set(formA, formB))
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
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncForms
      yield assertTrue(result.head.localizations == Map("en" -> Map("title" -> "Hello")))
    },
    test("getAllForms returns all forms sorted by id") {
      val formB = FormRecord(FormId("b"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val formA = FormRecord(FormId("a"), 1, true, "style", None, None, Map.empty, Vector.empty)
      val env = new Env(Vector(formB, formA))

      for result <- env.service.getAllForms
      yield assertTrue(result == Vector(formA, formB))
    },
    test("setActiveVersion delegates to repository and refreshes cache") {
      val env = new Env()
      val formId = FormId("test")
      val record = FormRecord(formId, 2, true, "style", None, None, Map.empty, Vector.empty)

      for
        _ <- env.repository.setActiveVersion.succeedsWith(())
        _ <- env.repository.getAll.succeedsWith(Vector(record))
        _ <- env.service.setActiveVersion(formId, 2)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.setActiveVersion.calls == List((formId, 2)),
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
