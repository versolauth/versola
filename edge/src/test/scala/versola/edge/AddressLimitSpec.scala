package versola.edge

import versola.edge.model.EdgeId
import versola.util.http.{Checkpoint, ClientIpSource, GuardMode, RateLimitDimension, RateLimitPolicy, RateLimitWindow, RateLimiter}
import versola.util.{Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.test.*

import java.net.InetAddress
import java.security.KeyPairGenerator

/** R1 on the edge's two listeners: the address of a plain-listener caller (failed and unauthenticated attempts only) and the
  * certificate of a mutual-TLS caller (every call). The routes are stand-ins with the patterns of the real ones, so what is
  * proven is the middleware: what it counts, what it refuses, and what it leaves alone.
  */
object AddressLimitSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(source: ClientIpSource = ClientIpSource.Socket, header: String = "X-Real-IP") = EdgeConfig(
    EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    clientIp = source,
    clientIpHeader = header,
  )

  private def policy(name: String, by: RateLimitDimension, limit: Int, mode: GuardMode = GuardMode.Enforce) =
    RateLimitPolicy(name = name, by = List(by), limits = List(RateLimitWindow(limit = limit, windowSeconds = 3600)), burst = limit, mode = mode)

  /** The answer a proxied call gets, set per test; `/login` always redirects. */
  private def routes(proxied: Ref[Response]): Routes[RateLimiter & EdgeConfig, Nothing] =
    Routes(
      Method.GET / "login" / string("preset") -> handler((_: String, _: Request) => Response.seeOther(URL.root / "authorize")),
      Method.GET / "resources" / string("id") / trailing -> handler((_: String, _: Path, _: Request) => proxied.get),
      Method.GET / "service" / "configuration" / "sync" -> handler(Response.ok),
    )

  private val Ok = Response.ok
  private val Unauthorized = Response.unauthorized

  private def from(path: String, address: String): Request =
    Request.get(URL.decode(path).toOption.get).copy(remoteAddress = Some(InetAddress.getByName(address)))

  private def run(
      limiter: RateLimiter,
      proxied: Ref[Response],
      listener: AddressLimit.Listener = AddressLimit.Listener.Plain,
      cfg: EdgeConfig = config(),
  )(request: Request): UIO[Response] =
    ZIO.scoped(AddressLimit(routes(proxied), listener).runZIO(request))
      .provideEnvironment(ZEnvironment[RateLimiter](limiter) ++ ZEnvironment[EdgeConfig](cfg))

  private def withLimiter(policies: RateLimitPolicy*) = RateLimiter.make().tap(_.update(policies.toList))

  def spec = suite("AddressLimit")(
    suite("the address (plain listener)")(
      test("failed attempts count: after the limit the address gets 429 with Retry-After, before the call is handled") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 2))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied)
          first <- go(from("/resources/x/items", "203.0.113.7"))
          second <- go(from("/resources/x/items", "203.0.113.7"))
          third <- go(from("/resources/x/items", "203.0.113.7"))
          other <- go(from("/resources/x/items", "203.0.113.8"))
          body <- third.body.asString
        yield assertTrue(
          first.status == Status.Unauthorized,
          second.status == Status.Unauthorized,
          third.status == Status.TooManyRequests,
          third.rawHeader("Retry-After").flatMap(_.toIntOption).exists(_ >= 1),
          third.rawHeader("RateLimit-Policy").contains("\"per-ip\";q=2;w=3600"),
          third.rawHeader("RateLimit").exists(_.startsWith("\"per-ip\";r=0;t=")),
          first.rawHeader("RateLimit").isEmpty,
          body == """{"error":"rate_limited","policy":"per-ip"}""",
          other.status == Status.Unauthorized,
        )
      },
      test("successful calls are never counted, however many: a shared carrier address does not refuse honest users") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Ok)
          go = run(limiter, proxied)
          calls <- ZIO.foreach(1 to 50)(_ => go(from("/resources/x/items", "198.51.100.1")))
        yield assertTrue(calls.forall(_.status == Status.Ok), limiter.stats("per-ip").map(_.trackedKeys) == Some(1))
      },
      test("a nonce challenge, a step-up challenge and a re-login redirect are not failures") {
        val challenges = List(
          Response.unauthorized.addHeader(Header.Custom("DPoP-Nonce", "n")),
          Response.unauthorized.addHeader(Header.Custom("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\"")),
          Response.status(Status.Unauthorized).addHeader(Header.Location(URL.root / "login")),
        )
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Ok)
          go = run(limiter, proxied)
          results <- ZIO.foreach(challenges)(response => proxied.set(response) *> go(from("/resources/x/items", "198.51.100.2")))
          // still has its one failed attempt to spend
          _ <- proxied.set(Unauthorized)
          failed <- go(from("/resources/x/items", "198.51.100.2"))
          blocked <- go(from("/resources/x/items", "198.51.100.2"))
        yield assertTrue(
          results.forall(_.status == Status.Unauthorized),
          failed.status == Status.Unauthorized,
          blocked.status == Status.TooManyRequests,
        )
      },
      test("entry points count every call: /login is limited by address even when it redirects") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 2))
          proxied <- Ref.make(Ok)
          go = run(limiter, proxied)
          a <- go(from("/login/web", "192.0.2.1"))
          b <- go(from("/login/web", "192.0.2.1"))
          c <- go(from("/login/web", "192.0.2.1"))
        yield assertTrue(a.status == Status.SeeOther, b.status == Status.SeeOther, c.status == Status.TooManyRequests)
      },
      test("service routes are never limited") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Ok)
          go = run(limiter, proxied)
          calls <- ZIO.foreach(1 to 5)(_ => go(from("/service/configuration/sync", "192.0.2.5")))
        yield assertTrue(calls.forall(_.status == Status.Ok), limiter.stats("per-ip").map(_.trackedKeys) == Some(0))
      },
      test("shadow counts would_limit and refuses nothing") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1, GuardMode.Shadow))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied)
          calls <- ZIO.foreach(1 to 4)(_ => go(from("/resources/x/items", "192.0.2.9")))
        yield assertTrue(calls.forall(_.status == Status.Unauthorized), limiter.stats("per-ip").map(_.wouldLimit).exists(_ >= 2L))
      },
      test("with no address policy nothing is read, counted or refused") {
        for
          limiter <- withLimiter(policy("per-client", RateLimitDimension.Client, 1))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied)
          calls <- ZIO.foreach(1 to 5)(_ => go(from("/resources/x/items", "192.0.2.9")))
        yield assertTrue(calls.forall(_.status == Status.Unauthorized), !limiter.active(Checkpoint.Address))
      },
    ),
    suite("which address")(
      test("socket: the peer; a forwarded header is ignored (anyone could write it)") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied, cfg = config(ClientIpSource.Socket))
          a <- go(from("/resources/x/i", "203.0.113.1").addHeader("X-Real-IP", "10.0.0.1"))
          b <- go(from("/resources/x/i", "203.0.113.1").addHeader("X-Real-IP", "10.0.0.2"))
        yield assertTrue(a.status == Status.Unauthorized, b.status == Status.TooManyRequests)
      },
      test("forwarded: the header, so callers behind one proxy have their own buckets") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied, cfg = config(ClientIpSource.Forwarded))
          proxy = "172.16.0.5"
          a <- go(from("/resources/x/i", proxy).addHeader("X-Real-IP", "203.0.113.1"))
          b <- go(from("/resources/x/i", proxy).addHeader("X-Real-IP", "203.0.113.2"))
          again <- go(from("/resources/x/i", proxy).addHeader("X-Real-IP", "203.0.113.1"))
        yield assertTrue(a.status == Status.Unauthorized, b.status == Status.Unauthorized, again.status == Status.TooManyRequests)
      },
      test("forwarded with the header missing or not an address falls back to the peer, never to the header's text") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied, cfg = config(ClientIpSource.Forwarded))
          a <- go(from("/resources/x/i", "172.16.0.5"))
          b <- go(from("/resources/x/i", "172.16.0.5").addHeader("X-Real-IP", "not-an-address"))
          c <- go(from("/resources/x/i", "172.16.0.5").addHeader("X-Real-IP", "evil.example.com"))
        yield assertTrue(a.status == Status.Unauthorized, b.status == Status.TooManyRequests, c.status == Status.TooManyRequests)
      },
      test("another header name is honoured") {
        for
          limiter <- withLimiter(policy("per-ip", RateLimitDimension.Ip, 1))
          proxied <- Ref.make(Unauthorized)
          go = run(limiter, proxied, cfg = config(ClientIpSource.Forwarded, "X-Client-IP"))
          a <- go(from("/resources/x/i", "172.16.0.5").addHeader("X-Client-IP", "203.0.113.1").addHeader("X-Real-IP", "1.1.1.1"))
          b <- go(from("/resources/x/i", "172.16.0.5").addHeader("X-Client-IP", "203.0.113.2"))
        yield assertTrue(a.status == Status.Unauthorized, b.status == Status.Unauthorized)
      },
    ),
    suite("ClientAddress.normalize")(
      test("IPv4 as written, IPv6 by /64, v4-mapped IPv6 as IPv4, anything else nothing") {
        assertTrue(
          ClientAddress.normalize("203.0.113.7").contains("203.0.113.7"),
          ClientAddress.normalize("2001:db8:1:2:aaaa:bbbb:cccc:dddd") == ClientAddress.normalize("2001:db8:1:2::1"),
          ClientAddress.normalize("2001:db8:1:2::1") != ClientAddress.normalize("2001:db8:1:3::1"),
          ClientAddress.normalize("::ffff:203.0.113.7").contains("203.0.113.7"),
          ClientAddress.normalize("example.com").isEmpty,
          ClientAddress.normalize("").isEmpty,
          ClientAddress.normalize("1.2.3").isEmpty,
          ClientAddress.normalize("a" * 60).isEmpty,
        )
      },
    ),
    suite("the certificate (mutual-TLS listener)")(
      test("every call of a certificate counts, a certificate has its own bucket, and a call with none is not limited") {
        val a = TestCertificates.generate(subject = "CN=bank-a,O=Bank,C=KZ").certificate
        val b = TestCertificates.generate(subject = "CN=bank-b,O=Bank,C=KZ").certificate
        for
          limiter <- withLimiter(policy("per-cert", RateLimitDimension.Certificate, 2))
          proxied <- Ref.make(Ok)
          go = run(limiter, proxied, AddressLimit.Listener.MutualTls)
          call = (cert: X509CertificateOpt) => go(Request.get(URL.decode("/resources/x/items").toOption.get).copy(remoteCertificate = cert))
          r1 <- call(Some(a))
          r2 <- call(Some(a))
          r3 <- call(Some(a))
          other <- call(Some(b))
          none <- call(None)
        yield assertTrue(
          r1.status == Status.Ok,
          r2.status == Status.Ok,
          r3.status == Status.TooManyRequests,
          r3.rawHeader("Retry-After").isDefined,
          other.status == Status.Ok,
          none.status == Status.Ok,
        )
      },
    ),
  ) @@ TestAspect.silentLogging

  private type X509CertificateOpt = Option[java.security.cert.Certificate]
