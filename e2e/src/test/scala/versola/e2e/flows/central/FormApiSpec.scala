package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Login form versions on central's admin API: what `PUT /configuration/forms` accepts as a form's texts.
  *
  * A login form looks a text up by key and fills its `{placeholders}` from the page's state, so a version whose
  * languages disagree would break the page for the readers of one language. Central refuses such a version, and
  * these tests check the refusal reaches the caller as a 400 with the reason, and that a refused version is not
  * stored. A form has no delete, so the forms these tests store stay (a name no login page asks for, so none
  * serves them); each uses a fresh id.
  */
object FormApiSpec extends CentralApiSpec:

  private val path = "/configuration/forms"

  private def texts(entries: (String, String)*): Json =
    Json.Obj(entries.map((key, text) => key -> (Json.Str(text): Json))*)

  private def version(id: String, localizations: (String, Json)*): Json =
    Json.Obj(
      "id" -> Json.Str(id),
      "style" -> Json.Str(""),
      "jsSource" -> Json.Null,
      "jsCompiled" -> Json.Null,
      "localizations" -> Json.Obj(localizations*),
      "properties" -> Json.Arr(),
    )

  private def stored(central: CentralApi, id: String, expect: Boolean): Task[Boolean] =
    eventually(central.get(path).flatMap(_.items("forms")).map(_.exists(_.str("id").contains(id))))(_ == expect)

  private val en = texts("title" -> "Sign in", "hint" -> "Code sent to {destination} in {seconds}s")
  private val ru = texts("title" -> "Вход", "hint" -> "Код отправлен на {destination} за {seconds}с")

  def spec = suite("Central API: forms")(
    test("a version whose languages share keys and placeholders is stored") {
      for
        central <- api
        id <- CentralApi.id("probe-form")
        put <- central.put(path, version(id, "en" -> en, "ru" -> ru))
        listed <- stored(central, id, expect = true)
      yield assertTrue(put.status == Status.NoContent, listed)
    },
    test("a language that is not a registered locale is stored when it has the same keys") {
      for
        central <- api
        id <- CentralApi.id("probe-form")
        put <- central.put(path, version(id, "en" -> en, "xx" -> ru))
      yield assertTrue(put.status == Status.NoContent)
        .label("central stores texts ahead of a language being switched on; activating the locale checks completeness")
    },
    test("a language that lacks a key is refused with the key named, and nothing is stored") {
      for
        central <- api
        id <- CentralApi.id("probe-form")
        put <- central.put(path, version(id, "en" -> en, "ru" -> texts("title" -> "Вход")))
        listed <- stored(central, id, expect = false)
        message <- put.obj.map(_.str("message"))
      yield assertTrue(
        put.status == Status.BadRequest,
        message.exists(_.contains("'ru' has no text for 'hint'")),
        !listed,
      )
    },
    test("a language with an extra key is refused") {
      for
        central <- api
        id <- CentralApi.id("probe-form")
        put <-
          central.put(path, version(id, "en" -> en, "ru" -> texts("title" -> "Вход", "hint" -> "Код {destination} {seconds}", "extra" -> "Лишнее")))
        message <- put.obj.map(_.str("message"))
      yield assertTrue(put.status == Status.BadRequest, message.exists(_.contains("'ru' has the key 'extra'")))
    },
    test("a placeholder spelled differently in one language is refused") {
      for
        central <- api
        id <- CentralApi.id("probe-form")
        put <- central.put(path, version(id, "en" -> en, "ru" -> texts("title" -> "Вход", "hint" -> "Код на {address} за {seconds}с")))
        message <- put.obj.map(_.str("message"))
      yield assertTrue(put.status == Status.BadRequest, message.exists(_.contains("Key 'hint' has the placeholders")))
    },
  )
