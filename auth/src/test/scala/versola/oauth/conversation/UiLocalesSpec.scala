package versola.oauth.conversation

import versola.util.UnitSpecBase
import zio.NonEmptyChunk
import zio.http.{Cookie, Header, Request, URL}
import zio.test.*

object UiLocalesSpec extends UnitSpecBase:
  private def request(cookie: Option[String] = None, acceptLanguage: Option[String] = None, query: Option[String] = None) =
    val base = Request.get(query.fold(URL.root)(locale => URL.root.addQueryParam("ui_locale", locale)))
    val withCookie = cookie.fold(base)(value => base.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(UiLocales.cookieName, value)))))
    acceptLanguage.fold(withCookie)(value => withCookie.addHeader(Header.AcceptLanguage.parse(value).toOption.get))

  def spec = suite("UiLocales")(
    suite("resolve")(
      test("orders explicit, record, cookie, then Accept-Language") {
        val result = UiLocales.resolve(
          request(cookie = Some("kk"), acceptLanguage = Some("de, fr;q=0.8")),
          explicit = List("ru"),
          fromRecord = List("en"),
        )
        assertTrue(result == Some(List("ru", "en", "kk", "de", "fr")))
      },
      test("drops duplicates, keeping the most preferred position") {
        val result = UiLocales.resolve(request(cookie = Some("ru"), acceptLanguage = Some("ru, en")), fromRecord = List("en"))
        assertTrue(result == Some(List("en", "ru")))
      },
      test("is None when the request names no locale") {
        assertTrue(UiLocales.resolve(request()) == None)
      },
      test("ignores a malformed cookie value") {
        assertTrue(UiLocales.resolve(request(cookie = Some("ru<script>"))) == None)
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
    test("cookie is not HttpOnly, is Secure and SameSite=Lax, and carries only the locale") {
      val cookie = UiLocales.cookie("ru")
      assertTrue(
        cookie.content == "ru",
        !cookie.isHttpOnly,
        cookie.isSecure,
        cookie.sameSite == Some(Cookie.SameSite.Lax),
        cookie.path.exists(_.encode == "/"),
      )
    },
  )
