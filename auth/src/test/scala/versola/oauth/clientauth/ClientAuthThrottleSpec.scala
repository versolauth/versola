package versola.oauth.clientauth

import versola.util.http.{ClientIpSource, GuardMode, Observability}
import zio.*
import zio.http.*
import zio.test.*

import java.net.InetAddress

/** The limits on auth's non-mTLS channel (#745). The routes are stand-ins with the real patterns; the stand-in `/token` ends with
  * `invalid_client` the way the real endpoint records it, so what is proven is the middleware: what it counts and refuses.
  */
object ClientAuthThrottleSpec extends ZIOSpecDefault:

  private val small = ClientAuthThrottle.Settings.default.copy(failedPerMinute = 3, failedBurst = 3, entryPerMinute = 2, entryBurst = 2)

  /** `ok` decides what the stand-in token endpoint answers. */
  private def routes(ok: Ref[Boolean]): Routes[ClientAuthThrottle, Nothing] =
    Routes(
      Method.POST / "token" -> handler((_: Request) =>
        ok.get.flatMap: good =>
          if good then ZIO.succeed(Response.ok)
          else Observability.setError("invalid_client").as(Response.status(Status.Unauthorized)),
      ),
      // records nothing, as the real introspection endpoint does for a bad client: the 401 is what shows
      Method.POST / "introspect" -> handler(Response.status(Status.Unauthorized)),
      Method.POST / "par" -> handler((_: Request) => Observability.setError("invalid_client").as(Response.status(Status.BadRequest))),
      Method.GET / "authorize" -> handler(Response.ok),
      Method.GET / "jwks" -> handler(Response.ok),
    )

  private def request(method: Method, path: String, from: String): Request =
    Request(method = method, url = URL.decode(path).toOption.get).copy(remoteAddress = Some(InetAddress.getByName(from)))

  private def run(throttle: ClientAuthThrottle, ok: Ref[Boolean])(request: Request): UIO[Response] =
    ZIO.scoped(ClientAuthThrottle(routes(ok)).runZIO(request)).provideEnvironment(ZEnvironment(throttle))

  def spec = suite("ClientAuthThrottle")(
    suite("failed client authentication")(
      test("an address that has failed its limit is refused with 429 and Retry-After, whatever the next credential is") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(false)
          go = run(throttle, ok)
          failures <- ZIO.foreach(1 to 3)(_ => go(request(Method.POST, "/token", "203.0.113.7")))
          _ <- ok.set(true)
          refused <- go(request(Method.POST, "/token", "203.0.113.7"))
          body <- refused.body.asString
          other <- go(request(Method.POST, "/token", "203.0.113.8"))
        yield assertTrue(
          failures.forall(_.status == Status.Unauthorized),
          refused.status == Status.TooManyRequests,
          refused.rawHeader("Retry-After").flatMap(_.toIntOption).exists(_ >= 1),
          refused.rawHeader("RateLimit-Policy").contains("\"failed-client-auth-per-address\";q=3;w=60"),
          refused.rawHeader("RateLimit").exists(_.startsWith("\"failed-client-auth-per-address\";r=0;t=")),
          body.isEmpty,
          other.status == Status.Ok,
        )
      },
      test("successful authentications are never counted, however many") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(true)
          calls <- ZIO.foreach(1 to 50)(_ => run(throttle, ok)(request(Method.POST, "/token", "198.51.100.1")))
        yield assertTrue(calls.forall(_.status == Status.Ok))
      },
      test("the four client-authenticating endpoints share the address's failures") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(false)
          go = run(throttle, ok)
          _ <- go(request(Method.POST, "/token", "192.0.2.4"))
          _ <- go(request(Method.POST, "/introspect", "192.0.2.4"))
          _ <- go(request(Method.POST, "/token", "192.0.2.4"))
          refused <- go(request(Method.POST, "/introspect", "192.0.2.4"))
        yield assertTrue(refused.status == Status.TooManyRequests)
      },
      test("a 400 that records invalid_client counts too (a client that did not use Basic)") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(false)
          go = run(throttle, ok)
          _ <- ZIO.foreachDiscard(1 to 3)(_ => go(request(Method.POST, "/par", "192.0.2.6")))
          refused <- go(request(Method.POST, "/token", "192.0.2.6"))
        yield assertTrue(refused.status == Status.TooManyRequests)
      },
      test("other routes are untouched") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(false)
          calls <- ZIO.foreach(1 to 20)(_ => run(throttle, ok)(request(Method.GET, "/jwks", "192.0.2.4")))
        yield assertTrue(calls.forall(_.status == Status.Ok))
      },
      test("off and shadow refuse nothing; shadow counts what enforce would refuse") {
        for
          off <- ClientAuthThrottle.make(small.copy(failedMode = GuardMode.Off))
          shadow <- ClientAuthThrottle.make(small.copy(failedMode = GuardMode.Shadow))
          ok <- Ref.make(false)
          offCalls <- ZIO.foreach(1 to 8)(_ => run(off, ok)(request(Method.POST, "/token", "192.0.2.4")))
          shadowCalls <- ZIO.foreach(1 to 8)(_ => run(shadow, ok)(request(Method.POST, "/token", "192.0.2.4")))
        yield assertTrue(
          offCalls.forall(_.status == Status.Unauthorized),
          shadowCalls.forall(_.status == Status.Unauthorized),
          shadow.failed.stats("failed-client-auth-per-address").exists(_.wouldLimit >= 4L),
        )
      },
    ),
    suite("the address")(
      test("socket ignores a forwarded header; forwarded takes the right-most value and falls back to the peer") {
        for
          socket <- ClientAuthThrottle.make(small.copy(failedPerMinute = 1, failedBurst = 1))
          forwarded <- ClientAuthThrottle.make(small.copy(failedPerMinute = 1, failedBurst = 1, clientIp = ClientIpSource.Forwarded))
          ok <- Ref.make(false)
          viaSocket1 <- run(socket, ok)(request(Method.POST, "/token", "172.16.0.5").addHeader("X-Real-IP", "203.0.113.1"))
          viaSocket2 <- run(socket, ok)(request(Method.POST, "/token", "172.16.0.5").addHeader("X-Real-IP", "203.0.113.2"))
          a <- run(forwarded, ok)(request(Method.POST, "/token", "172.16.0.5").addHeader("X-Real-IP", "198.51.100.9, 203.0.113.1"))
          b <- run(forwarded, ok)(request(Method.POST, "/token", "172.16.0.5").addHeader("X-Real-IP", "203.0.113.2"))
          again <- run(forwarded, ok)(request(Method.POST, "/token", "172.16.0.5").addHeader("X-Real-IP", "203.0.113.1"))
        yield assertTrue(
          viaSocket1.status == Status.Unauthorized,
          viaSocket2.status == Status.TooManyRequests,
          a.status == Status.Unauthorized,
          b.status == Status.Unauthorized,
          again.status == Status.TooManyRequests,
        )
      },
    ),
    suite("entry (/authorize)")(
      test("shadow by default: nothing is refused, would_limit is counted") {
        for
          throttle <- ClientAuthThrottle.make(small)
          ok <- Ref.make(true)
          calls <- ZIO.foreach(1 to 6)(_ => run(throttle, ok)(request(Method.GET, "/authorize", "192.0.2.4")))
        yield assertTrue(calls.forall(_.status == Status.Ok), throttle.entry.stats("authorize-per-address").exists(_.wouldLimit >= 3L))
      },
      test("enforce refuses every call over the limit, by address") {
        for
          throttle <- ClientAuthThrottle.make(small.copy(entryMode = GuardMode.Enforce))
          ok <- Ref.make(true)
          go = run(throttle, ok)
          calls <- ZIO.foreach(1 to 3)(_ => go(request(Method.GET, "/authorize", "192.0.2.4")))
          other <- go(request(Method.GET, "/authorize", "192.0.2.5"))
        yield assertTrue(calls.map(_.status) == List(Status.Ok, Status.Ok, Status.TooManyRequests), other.status == Status.Ok)
      },
    ),
    suite("settings")(
      test("defaults are on for failed authentication, shadow for entry, socket for the address") {
        val s = ClientAuthThrottle.Settings.default
        assertTrue(s.failedMode == GuardMode.Enforce, s.entryMode == GuardMode.Shadow, s.clientIp == ClientIpSource.Socket, s.validate.isRight)
      },
      test("a limit that cannot be applied is refused, never read as off") {
        val s = ClientAuthThrottle.Settings.default
        assertTrue(
          s.copy(failedPerMinute = 0).validate.left.exists(_.contains("failed-per-minute")),
          s.copy(failedBurst = 50, failedPerMinute = 20).validate.left.exists(_.contains("failed-burst")),
          s.copy(ipHeader = "").validate.isLeft,
        )
      },
      test("the limits block is read from the config file, and a mistyped mode fails instead of falling back") {
        import zio.config.magnolia.deriveConfig
        val config = deriveConfig[ClientAuthThrottle.Settings]
        val provider = ConfigProvider.fromMap(
          Map("failed-mode" -> "shadow", "failed-per-minute" -> "5", "client-ip" -> "forwarded", "ip-header" -> "X-Client-IP"),
          pathDelim = ".",
        ).kebabCase
        for
          read <- provider.load(config)
          bad <- ConfigProvider.fromMap(Map("failed-mode" -> "enforse")).kebabCase.load(config).either
        yield assertTrue(
          read.failedMode == GuardMode.Shadow,
          read.failedPerMinute == 5,
          read.failedBurst == 10,
          read.clientIp == ClientIpSource.Forwarded,
          read.ipHeader == "X-Client-IP",
          bad.isLeft,
        )
      },
    ),
  ) @@ TestAspect.silentLogging
