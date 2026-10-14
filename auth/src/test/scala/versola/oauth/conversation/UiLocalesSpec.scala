package versola.oauth.conversation

import versola.util.UnitSpecBase
import zio.http.{Header, Request, URL}
import zio.test.*

object UiLocalesSpec extends UnitSpecBase:
  private def request(acceptLanguage: Option[String] = None, query: Option[String] = None) =
    val base = Request.get(query.fold(URL.root)(locale => URL.root.addQueryParam("ui_locale", locale)))
    acceptLanguage.fold(base)(value => base.addHeader(Header.AcceptLanguage.parse(value).toOption.get))

  def spec = suite("UiLocales")(
    suite("resolve")(
      test("orders the submitted locale, the hints, then Accept-Language") {
        val result = UiLocales.resolve(request(acceptLanguage = Some("de, fr;q=0.8"), query = Some("ru")), hints = List("en"))
        assertTrue(result == Some(List("ru", "en", "de", "fr")))
      },
      test("drops duplicates, keeping the most preferred position") {
        val result = UiLocales.resolve(request(acceptLanguage = Some("ru, en"), query = Some("en")), hints = List("ru"))
        assertTrue(result == Some(List("en", "ru")))
      },
      test("is None when the request names no locale") {
        assertTrue(UiLocales.resolve(request()) == None)
      },
      test("ignores a malformed submitted locale") {
        assertTrue(UiLocales.resolve(request(query = Some("ru<script>"))) == None)
      },
    ),
    suite("acceptLanguage")(
      test("drops q=0 and orders by quality") {
        val result = UiLocales.acceptLanguage(request(acceptLanguage = Some("en;q=0, de;q=0.5, ru")))
        assertTrue(result == List("ru", "de"))
      },
    ),
    suite("submitted")(
      test("reads a well-formed ui_locale") {
        assertTrue(
          UiLocales.submitted(request(query = Some("ru"))) == Some("ru"),
          UiLocales.submitted(request(query = Some("pt-BR"))) == Some("pt-BR"),
        )
      },
      test("ignores anything that is not a language tag") {
        assertTrue(
          UiLocales.submitted(request(query = Some("ru<script>"))) == None,
          UiLocales.submitted(request(query = Some("../x"))) == None,
          UiLocales.submitted(request()) == None,
        )
      },
    ),
    suite("withLocale")(
      test("adds the locale to the URL when there is one") {
        assertTrue(
          UiLocales.withLocale(URL.root / "challenge", Some("ru")).encode == "/challenge?ui_locale=ru",
          UiLocales.withLocale(URL.root / "challenge", None).encode == "/challenge",
        )
      },
    ),
  )
