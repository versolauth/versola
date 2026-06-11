package versola.central.configuration.forms

import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object FormServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val en = FormLocale("en", "English")
  private val fr = FormLocale("fr", "French")

  class Env(initialForms: Vector[FormRecord] = Vector.empty):
    val cache    = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initialForms)))
    val repository = stub[FormRepository]
    val config   = TestCentralConfig.config
    val service  = FormService.Impl(cache, repository, config)

  def spec = suite("FormService")(
    test("updateLocales delegates add/delete to repository without triggering cache sync") {
      val env = new Env()

      for
        _ <- env.repository.updateLocales.succeedsWith(())
        _ <- env.service.updateLocales(add = Vector(en, fr), delete = Vector("de"))
      yield assertTrue(
        env.repository.updateLocales.calls == List((Vector(en, fr), Vector("de"))),
        env.repository.getAll.calls.isEmpty,
      )
    },
    test("updateLocales with empty add and delete does not trigger cache sync") {
      val env = new Env()

      for
        _ <- env.repository.updateLocales.succeedsWith(())
        _ <- env.service.updateLocales(add = Vector.empty, delete = Vector.empty)
      yield assertTrue(
        env.repository.updateLocales.calls == List((Vector.empty, Vector.empty)),
        env.repository.getAll.calls.isEmpty,
      )
    },
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
  )
