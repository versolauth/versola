package versola.edge.login

import versola.edge.EdgeService.LoginCompletion
import versola.edge.model.{AccessToken, PresetId}
import versola.util.{RedirectUri, UnitSpecBase}
import zio.*
import zio.http.URL
import zio.test.*

object ReturnToSpec extends UnitSpecBase:

  private val postLogin = URL.decode("https://app.example:8443/central/admin/?welcome=1").toOption.get

  def spec = suite("ReturnTo")(
    suite("parse")(
      test("accepts a path with a query") {
        assertTrue(
          ReturnTo.parse("/resources/auth/settings") == Some("/resources/auth/settings"),
          ReturnTo.parse("/resources/auth/settings?tab=passkeys") == Some("/resources/auth/settings?tab=passkeys"),
        )
      },
      test("refuses anything that could name another host") {
        assertTrue(
          ReturnTo.parse("//evil.example/x").isEmpty,
          ReturnTo.parse("/\\evil.example").isEmpty,
          ReturnTo.parse("https://evil.example/x").isEmpty,
          ReturnTo.parse("evil.example/x").isEmpty,
          ReturnTo.parse("javascript:alert(1)").isEmpty,
        )
      },
      test("refuses control characters, a fragment, an empty value and an over-long one") {
        assertTrue(
          ReturnTo.parse("/a\nb").isEmpty,
          ReturnTo.parse("/a\u0000b").isEmpty,
          ReturnTo.parse("/a#frag").isEmpty,
          ReturnTo.parse("").isEmpty,
          ReturnTo.parse("/" + "a" * 3000).isEmpty,
        )
      },
    ),
    suite("onto")(
      test("keeps the scheme, host and port of the base and takes the path and query of the value") {
        val url = ReturnTo.onto(postLogin, Some("/resources/auth/settings?tab=passkeys"))
        assertTrue(url.encode == "https://app.example:8443/resources/auth/settings?tab=passkeys")
      },
      test("is the base itself without a value, and for a value that is not a path of this origin") {
        assertTrue(
          ReturnTo.onto(postLogin, None) == postLogin,
          ReturnTo.onto(postLogin, Some("//evil.example/x")) == postLogin,
          ReturnTo.onto(postLogin, Some("https://evil.example/x")) == postLogin,
        )
      },
    ),
    suite("LoginCompletion.redirectUrl")(
      test("goes to the page that asked for the login, else to the preset's post-login page") {
        def completion(returnTo: Option[String]) = LoginCompletion(
          presetId = PresetId("preset"),
          accessToken = AccessToken("token"),
          cookieTtl = 1.hour,
          postLoginRedirectUri = RedirectUri("https://app.example:8443/central/admin/"),
          returnTo = returnTo,
          cookieDomain = None,
          cookiePath = None,
        )
        assertTrue(
          completion(Some("/resources/auth/settings")).redirectUrl.encode == "https://app.example:8443/resources/auth/settings",
          completion(None).redirectUrl.encode == "https://app.example:8443/central/admin/",
        )
      },
    ),
  )
