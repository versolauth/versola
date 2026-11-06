package versola.edge.nativeapp

import versola.edge.EdgeConfig
import versola.edge.model.EdgeId
import versola.util.Secret
import versola.util.http.{Checkpoint, ClientIpSource, GuardMode, RateLimitDimension, RateLimitPolicy, RateLimitScope, RateLimitWindow, RateLimiter}
import zio.*
import zio.http.*
import zio.test.*

import java.net.InetAddress
import java.security.KeyPairGenerator

/** The default limit on `/native/start`: what it counts, when it stays out of the way, and that it refuses before anything else. */
object NativeStartLimitSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(source: ClientIpSource, header: String = "X-Real-IP") = EdgeConfig(
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
  )

  private val forwarded = config(ClientIpSource.Forwarded)

  private def call(address: Option[String] = None, peer: String = "10.0.0.1"): Request =
    val base = Request.post(URL.decode("https://edge.example/native/start/mobile-app").toOption.get, Body.empty)
      .copy(remoteAddress = Some(InetAddress.getByName(peer)))
    address.fold(base)(base.addHeader("X-Real-IP", _))

  private def limit(config: EdgeConfig, operator: RateLimiter) =
    NativeStartLimit.make(operator, config).provideSomeLayer[Any](ZLayer.succeed(Scope.global))

  private val operatorAddressPolicy = RateLimitPolicy(
    name = "per-address",
    by = List(RateLimitDimension.Ip),
    scope = RateLimitScope.EdgeWide,
    limits = List(RateLimitWindow(limit = 600, windowSeconds = 60)),
    burst = 100,
    cost = 1,
    mode = GuardMode.Shadow,
  )

  private def refusedWith(effect: IO[NativeError, Unit]): UIO[Option[Int]] =
    effect.fold(
      {
        case NativeError.RateLimited(seconds) => Some(seconds)
        case _ => None
      },
      _ => None,
    )

  def spec = suite("NativeStartLimit")(
    test("lets an address start as many times as the burst, and then refuses it with the wait") {
      for
        operator <- RateLimiter.make()
        start <- limit(forwarded, operator)
        passed <- ZIO.foreach(1 to NativeStartLimit.Default.burst)(_ => start.enforce(call(Some("203.0.113.7"))).either)
        refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
      yield assertTrue(passed.forall(_.isRight), refused.exists(_ >= 1))
    },
    test("counts addresses apart") {
      for
        operator <- RateLimiter.make()
        start <- limit(forwarded, operator)
        _ <- ZIO.foreachDiscard(1 to NativeStartLimit.Default.burst)(_ => start.enforce(call(Some("203.0.113.7"))))
        refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        other <- start.enforce(call(Some("203.0.113.8"))).either
      yield assertTrue(refused.isDefined, other.isRight)
    },
    test("counts an IPv6 caller by its /64") {
      for
        operator <- RateLimiter.make()
        start <- limit(forwarded, operator)
        _ <- ZIO.foreachDiscard(1 to NativeStartLimit.Default.burst)(i => start.enforce(call(Some(s"2001:db8:1:2::$i"))))
        sameNetwork <- refusedWith(start.enforce(call(Some("2001:db8:1:2:ffff::1"))))
        otherNetwork <- start.enforce(call(Some("2001:db8:1:3::1"))).either
      yield assertTrue(sameNetwork.isDefined, otherNetwork.isRight)
    },
    test("gives the bucket back as time passes") {
      for
        operator <- RateLimiter.make()
        start <- limit(forwarded, operator)
        _ <- ZIO.foreachDiscard(1 to NativeStartLimit.Default.burst)(_ => start.enforce(call(Some("203.0.113.7"))))
        refused <- refusedWith(start.enforce(call(Some("203.0.113.7"))))
        _ <- TestClock.adjust(2.minutes)
        again <- start.enforce(call(Some("203.0.113.7"))).either
      yield assertTrue(refused.isDefined, again.isRight)
    },
    test("does nothing when the address is the peer's: behind a proxy that would count every caller as one") {
      for
        operator <- RateLimiter.make()
        start <- limit(config(ClientIpSource.Socket), operator)
        results <- ZIO.foreach(1 to 200)(_ => start.enforce(call(Some("203.0.113.7"))).either)
      yield assertTrue(results.forall(_.isRight))
    },
    test("does nothing when `client-ip` is forwarded and the header is missing or not an address") {
      for
        operator <- RateLimiter.make()
        start <- limit(forwarded, operator)
        missing <- ZIO.foreach(1 to 200)(_ => start.enforce(call(None)).either)
        garbage <- ZIO.foreach(1 to 200)(_ => start.enforce(call(Some("not-an-address"))).either)
      yield assertTrue(missing.forall(_.isRight), garbage.forall(_.isRight))
    },
    test("reads the header the operator named") {
      for
        operator <- RateLimiter.make()
        start <- limit(config(ClientIpSource.Forwarded, header = "X-Client-Address"), operator)
        request = call(None).addHeader("X-Client-Address", "203.0.113.7")
        _ <- ZIO.foreachDiscard(1 to NativeStartLimit.Default.burst)(_ => start.enforce(request))
        refused <- refusedWith(start.enforce(request))
      yield assertTrue(refused.isDefined)
    },
    test("stands aside once the operator has a policy at the address checkpoint, even one that only counts") {
      for
        operator <- RateLimiter.make()
        _ <- operator.update(List(operatorAddressPolicy))
        start <- limit(forwarded, operator)
        results <- ZIO.foreach(1 to 200)(_ => start.enforce(call(Some("203.0.113.7"))).either)
      yield assertTrue(operator.active(Checkpoint.Address), results.forall(_.isRight))
    },
  )
