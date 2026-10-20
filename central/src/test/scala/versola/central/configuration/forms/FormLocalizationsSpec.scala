package versola.central.configuration.forms

import zio.test.*

object FormLocalizationsSpec extends ZIOSpecDefault:
  private val en = Map("title" -> "Sign in", "greeting" -> "Hello, {name}", "hint" -> "Code sent to {destination} in {seconds}s")
  private val ru = Map("title" -> "Вход", "greeting" -> "Здравствуйте, {name}", "hint" -> "Код отправлен на {destination} за {seconds}с")

  def spec = suite("FormLocalizations")(
    suite("accepts")(
      test("languages with the same keys and the same placeholders") {
        assertTrue(FormLocalizations.validate(Map("en" -> en, "ru" -> ru)) == Right(()))
      },
      test("placeholders written in a different order") {
        val swapped = ru.updated("hint", "{seconds} с, {destination}")
        assertTrue(FormLocalizations.validate(Map("en" -> en, "ru" -> swapped)) == Right(()))
      },
      test("no languages at all, and a single one") {
        assertTrue(
          FormLocalizations.validate(Map.empty) == Right(()),
          FormLocalizations.validate(Map("en" -> en)) == Right(()),
        )
      },
      test("a language that is not a registered locale, held to the same keys as the others") {
        assertTrue(
          FormLocalizations.validate(Map("en" -> en, "xx-unknown" -> ru)) == Right(()),
          FormLocalizations.validate(Map("en" -> en, "xx-unknown" -> (ru - "title"))).isLeft,
        )
      },
      test("a blank text, which the locale completeness check reports when the language is activated") {
        assertTrue(FormLocalizations.validate(Map("en" -> en, "ru" -> ru.updated("title", "  "))) == Right(()))
      },
    ),
    suite("rejects")(
      test("a language with a key the reference lacks text for") {
        val result = FormLocalizations.validate(Map("en" -> en, "ru" -> (ru - "title")))
        assertTrue(result == Left("Locale 'ru' has no text for 'title', which 'en' has"))
      },
      test("a language with an extra key") {
        val result = FormLocalizations.validate(Map("en" -> en, "ru" -> ru.updated("surprise", "Сюрприз")))
        assertTrue(result == Left("Locale 'ru' has the key 'surprise', which 'en' lacks"))
      },
      test("a placeholder that differs between languages") {
        val result = FormLocalizations.validate(Map("en" -> en, "ru" -> ru.updated("greeting", "Здравствуйте, {imya}")))
        assertTrue(result == Left("Key 'greeting' has the placeholders {name} in 'en' but {imya} in 'ru'"))
      },
      test("a placeholder that is missing in one language") {
        val result = FormLocalizations.validate(Map("en" -> en, "ru" -> ru.updated("hint", "Код отправлен на {destination}")))
        assertTrue(result == Left("Key 'hint' has the placeholders {destination}, {seconds} in 'en' but {destination} in 'ru'"))
      },
      test("compares with the first language by code when English is absent") {
        val result = FormLocalizations.validate(Map("ru" -> ru, "de" -> (en - "title")))
        assertTrue(result.isLeft, result.left.exists(_.contains("'ru' has the key 'title', which 'de' lacks")))
      },
      test("lists the first few faults and counts the rest") {
        val many = (1 to 9).map(i => s"k$i" -> "x").toMap
        val result = FormLocalizations.validate(Map("en" -> many, "ru" -> Map.empty))
        assertTrue(result.left.exists(_.endsWith("; and 4 more")), result.left.exists(_.split("; ").length == 6))
      },
    ),
  )
