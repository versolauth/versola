package versola.edge

import versola.edge.model.AccessToken
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant

object EdgeSessionCookieSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2024-01-01T00:00:00Z")

  def spec = suite("EdgeSessionCookie")(
    test("apply builds a secure, http-only session cookie with domain and path") {
      val cookie = EdgeSessionCookie(AccessToken("at-123"), 1.hour, Some("app.example"), Some("/app"), now)
      assertTrue(
        cookie.name == EdgeSessionCookie.name,
        cookie.content == "at-123",
        cookie.domain.contains("app.example"),
        cookie.path.contains(Path.decode("/app")),
        cookie.isSecure,
        cookie.isHttpOnly,
        cookie.maxAge.contains(1.hour),
        cookie.sameSite.contains(Cookie.SameSite.Strict),
      )
    },
    test("apply defaults the path to root when none is provided") {
      val cookie = EdgeSessionCookie(AccessToken("at"), 1.hour, None, None, now)
      assertTrue(
        cookie.path.contains(Path.root),
        cookie.domain.isEmpty,
      )
    },
    test("clear produces an immediately-expiring empty cookie") {
      val cookie = EdgeSessionCookie.clear(Some("app.example"), Some("/app"), now)
      assertTrue(
        cookie.name == EdgeSessionCookie.name,
        cookie.content == "",
        cookie.maxAge.contains(Duration.Zero),
        cookie.isSecure,
        cookie.isHttpOnly,
        cookie.path.contains(Path.decode("/app")),
        cookie.sameSite.contains(Cookie.SameSite.Strict),
      )
    },
  )
