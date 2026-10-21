package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Login form translations on central's admin API: the texts of a form in a language, stored apart from the form.
  *
  * A form version is immutable, so a language added to the form's own record needs a new version of every form.
  * A translation does not: it is laid over the form's own texts when the form is served. These tests check what is
  * accepted and what is refused at `PUT /configuration/forms/translations`, and that a refused one is not stored.
  * They use the built-in `otp` form and the seeded `ru` locale, and take their translation away afterwards, since
  * a translation of a built-in form is served to every client.
  */
object FormTranslationApiSpec extends CentralApiSpec:

  private val path = "/configuration/forms/translations"

  private def texts(entries: (String, String)*): Json =
    Json.Obj(entries.map((key, text) => key -> (Json.Str(text): Json))*)

  private def translation(formId: String, locale: String, entries: (String, String)*): Json =
    Json.Obj("formId" -> Json.Str(formId), "locale" -> Json.Str(locale), "texts" -> texts(entries*))

  private def stored(central: CentralApi, formId: String, locale: String): Task[Option[Json.Obj]] =
    central.get(path).flatMap(_.items("translations")).map(_.find(t => t.str("formId").contains(formId) && t.str("locale").contains(locale)))

  /** Takes the translation away whatever the test did. */
  private def cleaned[A](central: CentralApi, formId: String, locale: String)(test: Task[A]): Task[A] =
    test.ensuring(central.delete(path, "formId" -> formId, "locale" -> locale).ignore)

  def spec = suite("Central API: form translations")(
    test("the texts of a form in a registered language are stored, listed, and removed") {
      for
        central <- api
        result <- cleaned(central, "otp", "ru"):
          for
            put <- central.put(path, translation("otp", "ru", "title" -> "Введите код (e2e)"))
            listed <- eventually(stored(central, "otp", "ru"))(_.isDefined)
            removed <- central.delete(path, "formId" -> "otp", "locale" -> "ru")
            after <- eventually(stored(central, "otp", "ru"))(_.isEmpty)
          yield assertTrue(put.status == Status.NoContent, listed.isDefined, removed.status == Status.NoContent, after.isEmpty)
      yield result
    },
    test("a translation need not be complete: a language is checked as a whole when it is activated") {
      for
        central <- api
        result <- cleaned(central, "otp", "ru"):
          central.put(path, translation("otp", "ru", "title" -> "Введите код (e2e)")).map(put => assertTrue(put.status == Status.NoContent))
      yield result
    },
    test("a language that is not registered is refused with the reason, and nothing is stored") {
      for
        central <- api
        put <- central.put(path, translation("otp", "xx", "title" -> "?"))
        message <- put.obj.map(_.str("message"))
        listed <- stored(central, "otp", "xx")
      yield assertTrue(put.status == Status.BadRequest, message.contains("Locale 'xx' is not registered"), listed.isEmpty)
    },
    test("a form that does not exist is refused") {
      for
        central <- api
        put <- central.put(path, translation("no-such-form", "ru", "title" -> "?"))
        message <- put.obj.map(_.str("message"))
      yield assertTrue(put.status == Status.BadRequest, message.exists(_.contains("There is no form 'no-such-form'")))
    },
    test("a key the form does not have is refused") {
      for
        central <- api
        result <- cleaned(central, "otp", "ru"):
          for
            put <- central.put(path, translation("otp", "ru", "nonsense" -> "x"))
            message <- put.obj.map(_.str("message"))
            listed <- stored(central, "otp", "ru")
          yield assertTrue(put.status == Status.BadRequest, message.exists(_.contains("'nonsense' is not a text of this form")), listed.isEmpty)
      yield result
    },
    test("a text whose placeholders differ from the form's is refused") {
      for
        central <- api
        result <- cleaned(central, "otp", "ru"):
          for
            put <- central.put(path, translation("otp", "ru", "resend_in" -> "Повтор через {secs} с"))
            message <- put.obj.map(_.str("message"))
          yield assertTrue(put.status == Status.BadRequest, message.exists(_.contains("Key 'resend_in' has the placeholders {seconds}")))
      yield result
    },
    test("a blank text is refused, since it would count as missing") {
      for
        central <- api
        result <- cleaned(central, "otp", "ru"):
          central.put(path, translation("otp", "ru", "title" -> "  ")).map(put => assertTrue(put.status == Status.BadRequest))
      yield result
    },
  ) @@ TestAspect.sequential
