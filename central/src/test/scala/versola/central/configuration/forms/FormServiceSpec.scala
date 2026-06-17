package versola.central.configuration.forms

import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.configuration.locales.{LocaleRecord, LocaleService}
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object FormServiceSpec extends ZIOSpecDefault, ZIOStubs:
  class Env(initialForms: Vector[FormRecord] = Vector.empty):
    val cache         = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initialForms)))
    val repository    = stub[FormRepository]
    val config        = TestCentralConfig.config
    val localeService = stub[LocaleService]
    val service       = FormService.Impl(cache, repository, config, localeService)

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
    test("getSyncForms excludes inactive forms") {
      val activeForm   = FormRecord(FormId("a"), 1, true,  "style", None, None, Map("en" -> Map("k" -> "v")), Vector.empty)
      val inactiveForm = FormRecord(FormId("b"), 1, false, "style", None, None, Map("en" -> Map("k" -> "v")), Vector.empty)
      val env = new Env(Vector(activeForm, inactiveForm))

      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncForms
      yield assertTrue(result.map(_.id) == Vector(FormId("a")))
    },
    test("getSyncForms strips localizations of inactive locales") {
      val form = FormRecord(
        FormId("f"), 1, true, "style", None, None,
        Map("en" -> Map("title" -> "Hello"), "fr" -> Map("title" -> "Bonjour")),
        Vector.empty,
      )
      val env = new Env(Vector(form))

      for
        _ <- env.localeService.getActive.succeedsWith(Vector(LocaleRecord("en", "English", isDefault = true, active = true)))
        result <- env.service.getSyncForms
      yield assertTrue(result.head.localizations == Map("en" -> Map("title" -> "Hello")))
    },
  )
