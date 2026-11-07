package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, PresetId}
import versola.util.RedirectUri
import zio.http.{Body, Method, Request, URL}
import zio.test.*

object CsrfGuardSpec extends ZIOSpecDefault:

  private val edgeUrl = URL.decode("https://edge.example.com").toOption.get

  private val preset = AuthorizationPreset(
    id = PresetId("web"),
    clientId = ClientId("web-app"),
    description = "web",
    redirectUri = RedirectUri("https://app.example.com/complete"),
    postLoginRedirectUri = RedirectUri("https://app.example.com/home"),
    postLogoutRedirectUri = Some(RedirectUri("https://www.example.com/bye")),
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val allowed = CsrfGuard.allowedOrigins(edgeUrl, Some(preset))

  private def call(method: Method, headers: (String, String)*): Request =
    headers.foldLeft(Request(method = method, url = URL.empty / "resources" / "api", body = Body.empty))((r, h) => r.addHeader(h._1, h._2))

  private def refused(method: Method, headers: (String, String)*): Boolean = CsrfGuard.refuses(call(method, headers*), allowed)

  private val unsafe = List(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)

  def spec = suite("CsrfGuard")(
    suite("a call that changes state")(
      test("from an origin that is not ours is refused, whatever the method") {
        assertTrue(unsafe.forall(refused(_, "Origin" -> "https://evil.example.com")))
      },
      test("from a sibling subdomain of the same site is refused") {
        assertTrue(
          refused(Method.POST, "Origin" -> "https://forum.example.com"),
          refused(Method.POST, "Origin" -> "https://forum.example.com", "Sec-Fetch-Site" -> "same-site"),
        )
      },
      test("from an origin with another scheme or port is refused") {
        assertTrue(
          refused(Method.POST, "Origin" -> "http://edge.example.com"),
          refused(Method.POST, "Origin" -> "https://edge.example.com:8443"),
          refused(Method.POST, "Origin" -> "https://app.example.com.evil.example"),
        )
      },
      test("from `null`, or from something that is not an origin, is refused") {
        assertTrue(
          refused(Method.POST, "Origin" -> "null"),
          refused(Method.POST, "Origin" -> ""),
          refused(Method.POST, "Origin" -> "app.example.com"),
        )
      },
      test("from edge's own origin, the preset's app or its post-logout origin goes on, in any case of the host and with the default port") {
        assertTrue(
          !refused(Method.POST, "Origin" -> "https://edge.example.com"),
          !refused(Method.POST, "Origin" -> "https://EDGE.example.com:443"),
          !refused(Method.PUT, "Origin" -> "https://app.example.com"),
          !refused(Method.DELETE, "Origin" -> "https://www.example.com"),
        )
      },
      test("an Origin that is ours decides even when Sec-Fetch-Site says same-site or cross-site") {
        assertTrue(
          !refused(Method.POST, "Origin" -> "https://app.example.com", "Sec-Fetch-Site" -> "same-site"),
          !refused(Method.POST, "Origin" -> "https://app.example.com", "Sec-Fetch-Site" -> "cross-site"),
        )
      },
      test("an Origin that is not ours decides even when Sec-Fetch-Site says same-origin") {
        assertTrue(refused(Method.POST, "Origin" -> "https://evil.example.com", "Sec-Fetch-Site" -> "same-origin"))
      },
      test("with no Origin, Sec-Fetch-Site same-site and cross-site are refused, same-origin, none and a value it does not know go on") {
        assertTrue(
          !refused(Method.POST, "Sec-Fetch-Site" -> "same-origin"),
          !refused(Method.POST, "Sec-Fetch-Site" -> "none"),
          !refused(Method.POST, "Sec-Fetch-Site" -> "Same-Origin"),
          refused(Method.POST, "Sec-Fetch-Site" -> "same-site"),
          refused(Method.POST, "Sec-Fetch-Site" -> "cross-site"),
          !refused(Method.POST, "Sec-Fetch-Site" -> "something-new"),
          refused(Method.POST, "Sec-Fetch-Site" -> " Cross-Site "),
        )
      },
      test("that says nothing about where it comes from goes on: a script or a test is not a browser holding someone's cookie") {
        assertTrue(unsafe.forall(!refused(_)))
      },
      test("reads the headers whatever the case of their names") {
        assertTrue(refused(Method.POST, "ORIGIN" -> "https://evil.example.com"), refused(Method.POST, "sec-fetch-site" -> "cross-site"))
      },
    ),
    suite("a safe method")(
      test("is never refused, from anywhere") {
        val safe = List(Method.GET, Method.HEAD, Method.OPTIONS, Method.TRACE)
        assertTrue(
          safe.forall(!refused(_, "Origin" -> "https://evil.example.com")),
          safe.forall(!refused(_, "Sec-Fetch-Site" -> "cross-site")),
        )
      },
    ),
    suite("the origins that are ours")(
      test("are edge's, and the preset's when it exists") {
        assertTrue(
          allowed.map(_.encode.stripSuffix("/")).toSet.size == 4,
          CsrfGuard.allowedOrigins(edgeUrl, None) == List(edgeUrl),
        )
      },
      test("without the preset (removed, or a cookie from before presets), only edge's own origin goes on") {
        val onlyEdge = CsrfGuard.allowedOrigins(edgeUrl, None)
        assertTrue(
          !CsrfGuard.refuses(call(Method.POST, "Origin" -> "https://edge.example.com"), onlyEdge),
          CsrfGuard.refuses(call(Method.POST, "Origin" -> "https://app.example.com"), onlyEdge),
        )
      },
    ),
  )
