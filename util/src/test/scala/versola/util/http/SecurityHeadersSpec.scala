package versola.util.http

import zio.*
import zio.http.*
import zio.test.*

object SecurityHeadersSpec extends ZIOSpecDefault:

  private val routes = Routes(
    Method.GET / "page" -> handler(Response.html("<p>hi</p>")),
    Method.GET / "data" -> handler(Response.json("{}")),
    Method.GET / "custom" -> handler(
      Response.html("<p/>").addHeader(Header.Custom("X-Frame-Options", "SAMEORIGIN")),
    ),
  ) @@ SecurityHeaders.middleware

  private def get(path: String, headers: Headers = Headers.empty): UIO[Response] =
    ZIO.scoped(routes.runZIO(Request.get(URL.decode(path).toOption.get).addHeaders(headers)))

  def spec = suite("SecurityHeaders")(
    test("every response is told not to be sniffed, to send no referrer and to drop powerful features") {
      for response <- get("/data")
      yield assertTrue(
        response.rawHeader("X-Content-Type-Options").contains("nosniff"),
        response.rawHeader("Referrer-Policy").contains("no-referrer"),
        response.rawHeader("Permissions-Policy").exists(_.contains("camera=()")),
      )
    },
    test("an HTML page cannot be framed") {
      for response <- get("/page")
      yield assertTrue(
        response.rawHeader("Content-Security-Policy").contains(SecurityHeaders.HtmlContentSecurityPolicy),
        response.rawHeader("X-Frame-Options").contains("DENY"),
      )
    },
    test("a JSON response carries no framing policy") {
      for response <- get("/data")
      yield assertTrue(
        response.rawHeader("Content-Security-Policy").isEmpty,
        response.rawHeader("X-Frame-Options").isEmpty,
      )
    },
    test("a header the handler set is left alone") {
      for response <- get("/custom")
      yield assertTrue(response.rawHeader("X-Frame-Options").contains("SAMEORIGIN"))
    },
    test("HSTS is sent behind a TLS terminator and not over plain http") {
      for
        plain <- get("/data")
        proxied <- get("/data", Headers(Header.Custom("X-Forwarded-Proto", "https")))
      yield assertTrue(
        plain.rawHeader("Strict-Transport-Security").isEmpty,
        proxied.rawHeader("Strict-Transport-Security").contains(SecurityHeaders.StrictTransportSecurity),
      )
    },
  )
