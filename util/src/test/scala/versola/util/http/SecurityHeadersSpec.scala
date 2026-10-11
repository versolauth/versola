package versola.util.http

import zio.*
import zio.http.*
import zio.test.*

object SecurityHeadersSpec extends ZIOSpecDefault:

  private val routes = Routes(
    Method.GET / "page" -> handler(Response.html("<p>hi</p>")),
    Method.GET / "data" -> handler(Response.json("{}")),
    Method.GET / "failed" -> Handler.fail(Response.html("<p>no</p>").status(Status.Forbidden)),
    Method.GET / "custom" -> handler(
      Response.html("<p/>").addHeader(Header.Custom("X-Frame-Options", "SAMEORIGIN")),
    ),
  ) @@ SecurityHeaders.middleware

  private val tlsRoutes = Routes(Method.GET / "data" -> handler(Response.json("{}"))) @@ SecurityHeaders.tlsMiddleware

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
    test("a response a handler failed with is decorated like a returned one") {
      for response <- get("/failed")
      yield assertTrue(
        response.status == Status.Forbidden,
        response.rawHeader("X-Frame-Options").contains("DENY"),
        response.rawHeader("X-Content-Type-Options").contains("nosniff"),
      )
    },
    test("the fallback for an unknown path is decorated too") {
      for response <- get("/nope")
      yield assertTrue(response.status == Status.NotFound, response.rawHeader("X-Content-Type-Options").contains("nosniff"))
    },
    test("a listener that terminates TLS itself sends HSTS with no forwarding header") {
      for response <- ZIO.scoped(tlsRoutes.runZIO(Request.get(URL.decode("/data").toOption.get)))
      yield assertTrue(response.rawHeader("Strict-Transport-Security").contains(SecurityHeaders.StrictTransportSecurity))
    },
    // The in-memory `runZIO` skips what the real server does when it installs routes
    // (`oldRoutes ++ newRoutes`), which is how an unmatched path once escaped these headers.
    test("a running server decorates an unknown path") {
      ZIO.scoped:
        for
          env <- (ZLayer.succeed(Server.Config.default.onAnyOpenPort) >>> Server.live).build
          port <- Server.install(routes).provideEnvironment(env)
          client <- Client.default.build.map(_.get[Client])
          url <- ZIO.fromEither(URL.decode(s"http://localhost:$port/no-such-path")).mapError(RuntimeException(_))
          response <- Client.batched(Request.get(url)).provide(ZLayer.succeed(client))
        yield assertTrue(
          response.status == Status.NotFound,
          response.rawHeader("X-Content-Type-Options").contains("nosniff"),
        )
    },
  ) @@ TestAspect.withLiveClock
