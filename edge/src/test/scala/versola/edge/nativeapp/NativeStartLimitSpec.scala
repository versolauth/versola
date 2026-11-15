package versola.edge.nativeapp

import versola.edge.model.EdgeId
import versola.edge.{EdgeConfig, EdgeMode}
import versola.util.Secret
import versola.util.http.{ClientIpSource, GuardMode, NativeStartRateLimit}
import zio.*
import zio.http.*
import zio.test.*

import java.net.InetAddress
import java.security.KeyPairGenerator

/** The limit on `/native/start`: what it counts, what applies while nothing is set on each kind of edge, and that a set limit replaces it. */
object NativeStartLimitSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(source: ClientIpSource, header: String = "X-Real-IP", mode: EdgeMode = EdgeMode.FirstParty) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    clientIp = source,
    clientIpHeader = header,
    mode = mode,
  )

  private val forwarded = config(ClientIpSource.Forwarded)
  private val partner = config(ClientIpSource.Forwarded, mode = EdgeMode.Partner)
  private val Burst = NativeStartRateLimit.default(GuardMode.Enforce).burst
  private val enforced = Some(NativeStartRateLimit.default(GuardMode.Enforce))

  private def call(address: Option[String] = None, peer: String = "10.0.0.1"): Request =
    val base = Request.post(URL.decode("https://edge.example/native/start/mobile-app").toOption.get, Body.empty)
      .copy(remoteAddress = Some(InetAddress.getByName(peer)))
    address.fold(base)(base.addHeader("X-Real-IP", _))

  private def limit(config: EdgeConfig, configured: UIO[Option[NativeStartRateLimit]] = ZIO.none, replicas: Int = 1) =
    NativeStartLimit.make(configured, ZIO.succeed(replicas), config).provideSomeLayer[Any](ZLayer.succeed(Scope.global))

  private def refusedWith(effect: IO[NativeError, Unit]): UIO[Option[Int]] =
    effect.fold(
      {
        case NativeError.RateLimited(seconds) => Some(seconds)
        case _ => None
      },
      _ => None,
    )

  private def allPass(start: NativeStartLimit, request: Request, calls: Int = 200): UIO[Boolean] =
    ZIO.foreach(1 to calls)(_ => start.enforce(request).either).map(_.forall(_.isRight))

  def spec = suite("NativeStartLimit")(
    suite("a limit that refuses")(
      test("lets an address start as many times as the burst, and then refuses it with the wait") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced))
          passed <- ZIO.foreach(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))).either)
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(passed.forall(_.isRight), refused.exists(_ >= 1))
      },
      test("counts addresses apart") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced))
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))))
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
          other <- start.enforce(call(Some("203.0.113.8"))).either
        yield assertTrue(refused.isDefined, other.isRight)
      },
      test("counts an IPv6 caller by its /64") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced))
          _ <- ZIO.foreachDiscard(1 to Burst)(i => start.enforce(call(Some(s"2001:db8:1:2::$i"))))
          sameNetwork <- refusedWith(start.enforce(call(Some("2001:db8:1:2:ffff::1"))))
          otherNetwork <- start.enforce(call(Some("2001:db8:1:3::1"))).either
        yield assertTrue(sameNetwork.isDefined, otherNetwork.isRight)
      },
      test("gives the bucket back as time passes") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced))
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))))
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
          _ <- TestClock.adjust(2.minutes)
          again <- start.enforce(call(Some("203.0.113.7"))).either
        yield assertTrue(refused.isDefined, again.isRight)
      },
      test("reads the header the operator named") {
        for
          start <- limit(config(ClientIpSource.Forwarded, header = "X-Client-Address"), ZIO.succeed(enforced))
          request = call(None).addHeader("X-Client-Address", "203.0.113.7")
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(request))
          refused <- refusedWith(start.enforce(request))
        yield assertTrue(refused.isDefined)
      },
      test("never counts a call whose forwarded header is missing or not an address") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced))
          missing <- allPass(start, call(None))
          garbage <- allPass(start, call(Some("not-an-address")))
        yield assertTrue(missing, garbage)
      },
      test("is divided by the number of pods") {
        for
          start <- limit(forwarded, ZIO.succeed(enforced), replicas = 4)
          passed <- ZIO.foreach(1 to Burst / 4)(_ => start.enforce(call(Some("203.0.113.7"))).either)
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(passed.forall(_.isRight), refused.isDefined)
      },
    ),
    suite("while nothing is set")(
      test("an edge of the operator only counts: nobody is refused") {
        for
          start <- limit(forwarded)
          passed <- allPass(start, call(Some("203.0.113.7")))
        yield assertTrue(
          passed,
          NativeStartLimit.effective(None, EdgeMode.FirstParty, ClientIpSource.Forwarded).map(p => (p.name, p.mode)) ==
            Some((NativeStartRateLimit.DefaultPolicyName, GuardMode.Shadow)),
        )
      },
      test("a partner's edge refuses over the default") {
        for
          start <- limit(partner)
          passed <- ZIO.foreach(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))).either)
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(passed.forall(_.isRight), refused.isDefined)
      },
      test("nothing applies when the address is the peer's: behind a proxy that would count every caller as one") {
        for
          start <- limit(config(ClientIpSource.Socket, mode = EdgeMode.Partner))
          passed <- allPass(start, call(Some("203.0.113.7")))
        yield assertTrue(passed, NativeStartLimit.effective(None, EdgeMode.Partner, ClientIpSource.Socket).isEmpty)
      },
    ),
    suite("a limit that was set")(
      test("replaces the default of a partner's edge with its own numbers") {
        for
          start <- limit(partner, ZIO.some(NativeStartRateLimit(limit = 5, windowSeconds = 60, burst = 2, mode = GuardMode.Enforce)))
          passed <- ZIO.foreach(1 to 2)(_ => start.enforce(call(Some("203.0.113.7"))).either)
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(passed.forall(_.isRight), refused.isDefined)
      },
      test("turns the limit off with mode off") {
        for
          start <- limit(partner, ZIO.some(NativeStartRateLimit.default(GuardMode.Off)))
          passed <- allPass(start, call(Some("203.0.113.7")))
        yield assertTrue(passed)
      },
      test("applies on an edge that reads the address from the socket: whoever set it knows what the address is") {
        for
          start <- limit(config(ClientIpSource.Socket), ZIO.succeed(enforced))
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(call(peer = "198.51.100.4")))
          refused <- refusedWith(start.enforce(call(peer = "198.51.100.4")))
          other <- start.enforce(call(peer = "198.51.100.5")).either
        yield assertTrue(refused.isDefined, other.isRight)
      },
      test("is followed when it changes, and the default comes back when it is removed") {
        for
          held <- Ref.make(Option(NativeStartRateLimit.default(GuardMode.Off)))
          start <- limit(partner, held.get)
          off <- allPass(start, call(Some("203.0.113.7")))
          _ <- held.set(None)
          _ <- TestClock.adjust(1.second)
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))))
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(off, refused.isDefined)
      },
      test("keeps the previous limit when the new one is not valid") {
        for
          held <- Ref.make(enforced)
          start <- limit(forwarded, held.get)
          _ <- held.set(Some(NativeStartRateLimit(limit = 5, windowSeconds = 60, burst = 50, mode = GuardMode.Off)))
          _ <- TestClock.adjust(1.second)
          _ <- ZIO.foreachDiscard(1 to Burst)(_ => start.enforce(call(Some("203.0.113.7"))))
          refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        yield assertTrue(refused.isDefined)
      },
    ),
  )
