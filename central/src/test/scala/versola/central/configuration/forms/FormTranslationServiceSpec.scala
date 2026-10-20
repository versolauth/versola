package versola.central.configuration.forms

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.locales.{LocaleRecord, LocaleService}
import zio.*
import zio.test.*

object FormTranslationServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val formId = FormId("otp")
  private val form = FormRecord(
    id = formId,
    version = 2,
    active = true,
    style = "",
    jsSource = None,
    jsCompiled = None,
    localizations = Map("en" -> Map("title" -> "Enter code", "resend_in" -> "Resend in {seconds}s")),
    properties = Vector.empty,
  )
  private val kazakh = LocaleRecord(code = "kk", name = "Қазақша", isDefault = false, active = false)

  class Env(forms: Vector[FormRecord] = Vector(form), locales: Vector[LocaleRecord] = Vector(kazakh)):
    val repository = stub[FormTranslationRepository]
    val formService = stub[FormService]
    val localeService = stub[LocaleService]
    val service = FormTranslationService.Impl(repository, formService, localeService)

    val ready: UIO[Unit] =
      formService.getAllForms.succeedsWith(forms) *> localeService.getAll.succeedsWith(locales) *> repository.upsert.succeedsWith(())

  private def refused(texts: Map[String, String], env: Env = new Env()) =
    for
      _ <- env.ready
      result <- env.service.put(formId, "kk", texts).either
    yield (result, env.repository.upsert.calls)

  def spec = suite("FormTranslationService")(
    test("stores the texts of an active form in a registered language, even when it is not active yet") {
      val env = new Env()
      val texts = Map("title" -> "Кодты енгізіңіз", "resend_in" -> "{seconds} с кейін")
      for
        _ <- env.ready
        _ <- env.service.put(formId, "kk", texts)
      yield assertTrue(env.repository.upsert.calls == List(FormTranslationRecord(formId = formId, locale = "kk", texts = texts)))
    },
    test("accepts a partial translation: a language is checked as a whole when it is activated") {
      val env = new Env()
      for
        _ <- env.ready
        _ <- env.service.put(formId, "kk", Map("title" -> "Кодты енгізіңіз"))
      yield assertTrue(env.repository.upsert.calls.length == 1)
    },
    test("refuses a language that is not registered, and stores nothing") {
      for (result, stored) <- refused(Map("title" -> "x"), new Env(locales = Vector.empty))
      yield assertTrue(
        result.left.exists(_.isInstanceOf[IllegalArgumentException]),
        result.left.exists(_.getMessage == "Locale 'kk' is not registered"),
        stored.isEmpty,
      )
    },
    test("refuses a form that does not exist, or an inactive version's id that has no active one") {
      for (result, stored) <- refused(Map("title" -> "x"), new Env(forms = Vector(form.copy(active = false))))
      yield assertTrue(result.left.exists(_.getMessage == "There is no form 'otp'"), stored.isEmpty)
    },
    test("refuses a key the form does not have") {
      for (result, stored) <- refused(Map("title" -> "x", "nonsense" -> "y"))
      yield assertTrue(result.left.exists(_.getMessage == "'nonsense' is not a text of this form"), stored.isEmpty)
    },
    test("refuses a blank text, which would count as missing") {
      for (result, stored) <- refused(Map("title" -> " "))
      yield assertTrue(result.left.exists(_.getMessage.contains("'title' in 'kk' is blank")), stored.isEmpty)
    },
    test("refuses a text whose placeholders differ from the form's") {
      for (result, stored) <- refused(Map("resend_in" -> "{secs} с кейін"))
      yield assertTrue(
        result.left.exists(_.getMessage == "Key 'resend_in' has the placeholders {seconds} in the form but {secs} in 'kk'"),
        stored.isEmpty,
      )
    },
    test("reports every fault, not only the first") {
      for (result, _) <- refused(Map("nonsense" -> "y", "resend_in" -> "{secs}"))
      yield assertTrue(result.left.exists(error => error.getMessage.contains("nonsense") && error.getMessage.contains("resend_in")))
    },
    test("delete removes the translation through the repository, and getAll reads the repository") {
      val env = new Env()
      val record = FormTranslationRecord(formId = formId, locale = "kk", texts = Map("title" -> "x"))
      for
        _ <- env.repository.delete.succeedsWith(true)
        _ <- env.repository.getAll.succeedsWith(Vector(record))
        _ <- env.service.delete(formId, "kk")
        all <- env.service.getAll
      yield assertTrue(env.repository.delete.calls == List((formId, "kk")), all == Vector(record))
    },
  )
