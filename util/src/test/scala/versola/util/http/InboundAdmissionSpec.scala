package versola.util.http

import zio.*
import zio.http.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

object InboundAdmissionSpec extends ZIOSpecDefault:

  private def settings(
      mode: GuardMode = GuardMode.Enforce,
      max: Int = 10,
      continuation: Double = 0.8,
      start: Double = 0.5,
  ) = InboundAdmissionSettings(mode, max, continuation, start, retryAfterSeconds = 2)

  private def tierOf(method: Method, pattern: String): Tier = pattern match
    case "/never" => Tier.Never
    case "/start" => Tier.Start
    case "/continue" => Tier.Continuation
    case _ => Tier.Critical

  /** Routes whose handlers hold their place until `gate` opens. */
  private def routes(entered: Ref[Int], gate: Promise[Nothing, Unit]) =
    def held(path: String) =
      Method.GET / path -> handler(entered.update(_ + 1) *> gate.await.as(Response.ok))
    Routes(held("never"), held("start"), held("continue"), held("critical"))

  private def get(path: String) = Request.get(URL.decode(path).toOption.get)

  private def refused(tier: String, mode: String) =
    Metric.counter("http_admission_refused_total")
      .tagged(Set(MetricLabel("tier", tier), MetricLabel("mode", mode))).value.map(_.count)

  /** `n` requests to `path` that are in the handler (holding a place) when this returns. */
  private def occupy(app: Routes[Any, Response], path: String, n: Int, entered: Ref[Int]) =
    for
      before <- entered.get
      fibers <- ZIO.foreach(1 to n)(_ => app.runZIO(get(path)).fork)
      _ <- entered.get.repeatUntil(_ == before + n)
    yield fibers

  def spec = suite("InboundAdmission")(
    suite("settings")(
      test("nothing set: shadow, 256, and the documented shares") {
        val s = InboundAdmissionSettings.parse(None, None, None, None, None)
        assertTrue(s == InboundAdmissionSettings(GuardMode.Shadow, 256, 0.85, 0.65, 1))
      },
      test("invalid values fall back; a lower tier never gets more room than a higher one") {
        val bad = InboundAdmissionSettings.parse(Some("loud"), Some("0"), Some("1.5"), Some("abc"), Some("-4"))
        val inverted = InboundAdmissionSettings.parse(Some("enforce"), Some("100"), Some("0.5"), Some("0.9"), Some("3"))
        assertTrue(
          bad == InboundAdmissionSettings(GuardMode.Shadow, 256, 0.85, 0.65, 1),
          inverted.startShare == 0.5,
          inverted.mode == GuardMode.Enforce && inverted.retryAfterSeconds == 3,
        )
      },
      test("the thresholds per tier") {
        val s = settings(max = 10, continuation = 0.8, start = 0.5)
        assertTrue(s.threshold(Tier.Critical) == 10, s.threshold(Tier.Continuation) == 8, s.threshold(Tier.Start) == 5)
      },
    ),
    suite("enforce")(
      test("a new login is refused first, then continuations, the critical last, each with 503 and Retry-After") {
        for
          adm <- InboundAdmission.make(settings())
          entered <- Ref.make(0)
          gate <- Promise.make[Nothing, Unit]
          app = routes(entered, gate) @@ adm.middleware(tierOf)
          // 5 places taken: the start tier (50 % of 10) is full, the others are not
          held <- occupy(app, "/critical", 5, entered)
          start <- app.runZIO(get("/start"))
          cont <- app.runZIO(get("/continue")).fork
          _ <- entered.get.repeatUntil(_ == 6)
          // 8 taken: continuation (80 %) is now full as well
          more <- occupy(app, "/critical", 2, entered)
          cont2 <- app.runZIO(get("/continue"))
          crit <- app.runZIO(get("/critical")).fork
          _ <- entered.get.repeatUntil(_ == 9)
          more2 <- occupy(app, "/critical", 1, entered)
          // 10 taken, the whole limit
          crit2 <- app.runZIO(get("/critical"))
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(held ++ more ++ more2 ++ List(cont, crit))(_.join)
        yield assertTrue(
          start.status == Status.ServiceUnavailable,
          start.headers.get("Retry-After").contains("2"),
          cont2.status == Status.ServiceUnavailable,
          crit2.status == Status.ServiceUnavailable,
        )
      },
      test("a tier that is never counted is never refused, whatever is in flight") {
        for
          adm <- InboundAdmission.make(settings(max = 2))
          entered <- Ref.make(0)
          gate <- Promise.make[Nothing, Unit]
          app = routes(entered, gate) @@ adm.middleware(tierOf)
          held <- occupy(app, "/critical", 2, entered)
          never <- app.runZIO(get("/never")).fork
          _ <- entered.get.repeatUntil(_ == 3)
          inFlightWithNever <- ZIO.succeed(adm.inFlightNow)
          _ <- gate.succeed(())
          resp <- never.join
          _ <- ZIO.foreachDiscard(held)(_.join)
        yield assertTrue(resp.status == Status.Ok, inFlightWithNever == 2)
      },
      test("places come back after a success, a failure and an interrupt: nothing leaks") {
        for
          adm <- InboundAdmission.make(settings(max = 1))
          ok = Routes(Method.GET / "ok" -> handler(ZIO.succeed(Response.ok)))
          boom = Routes(Method.GET / "boom" -> handler(ZIO.fail(Response.status(Status.BadGateway))))
          stuck = Routes(Method.GET / "stuck" -> handler(ZIO.never.as(Response.ok)))
          a <- (ok @@ adm.middleware((_, _) => Tier.Critical)).runZIO(get("/ok"))
          b <- (boom @@ adm.middleware((_, _) => Tier.Critical)).runZIO(get("/boom"))
          fiber <- (stuck @@ adm.middleware((_, _) => Tier.Critical)).runZIO(get("/stuck")).fork
          _ <- ZIO.sleep(50.millis)
          _ <- fiber.interrupt
          // the single place must be free again
          c <- (ok @@ adm.middleware((_, _) => Tier.Critical)).runZIO(get("/ok"))
        yield assertTrue(a.status == Status.Ok, b.status == Status.BadGateway, c.status == Status.Ok, adm.inFlightNow == 0)
      },
    ),
    suite("shadow")(
      test("refuses nothing, counts what enforce would have refused, and keeps the in-flight count honest") {
        for
          adm <- InboundAdmission.make(settings(mode = GuardMode.Shadow, max = 1))
          entered <- Ref.make(0)
          gate <- Promise.make[Nothing, Unit]
          app = routes(entered, gate) @@ adm.middleware(tierOf)
          held <- occupy(app, "/critical", 1, entered)
          over <- occupy(app, "/start", 1, entered)
          counted <- refused("start", "shadow")
          inFlight <- ZIO.succeed(adm.inFlightNow)
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(held ++ over)(_.join)
        yield assertTrue(counted >= 1.0, inFlight == 2, adm.inFlightNow == 0)
      },
    ),
    suite("off")(
      test("passes everything through and counts nothing") {
        for
          adm <- InboundAdmission.make(settings(mode = GuardMode.Off, max = 1))
          entered <- Ref.make(0)
          gate <- Promise.make[Nothing, Unit]
          app = routes(entered, gate) @@ adm.middleware(tierOf)
          held <- occupy(app, "/critical", 3, entered)
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(held)(_.join)
        yield assertTrue(adm.inFlightNow == 0)
      },
    ),
    suite("adaptive limit")(
      test("the limit is the learned one while adaptation is on, and call times that worsen shrink it") {
        val adaptive = AdaptiveSettings(enabled = true, minLimit = 2, maxLimit = 20, tolerance = 1.5, smoothing = 0.5, lagTarget = 50.millis)
        for
          adm <- InboundAdmission.make(settings(max = 5).copy(adaptive = adaptive))
          initial = adm.limit
          _ <- ZIO.foreachDiscard(1 to 20)(_ => ZIO.succeed((1 to 100).foreach(_ => adm.record(Tier.Critical, 10_000_000L))) *> adm.adapt)
          warm = adm.limit
          _ <- ZIO.foreachDiscard(1 to 20)(_ => ZIO.succeed((1 to 100).foreach(_ => adm.record(Tier.Critical, 200_000_000L))) *> adm.adapt)
        yield assertTrue(initial == 5, warm == 5, adm.limit < 5, adm.limit >= 2)
      },
      test("a stalled executor shrinks it even with no call completing") {
        val adaptive = AdaptiveSettings(enabled = true, minLimit = 2, maxLimit = 20, tolerance = 1.5, smoothing = 0.5, lagTarget = 50.millis)
        for
          adm <- InboundAdmission.make(settings(max = 5).copy(adaptive = adaptive))
          _ <- ZIO.foreachDiscard(1 to 30)(_ => ZIO.succeed(adm.recordLag(3_000_000_000L)) *> adm.adapt)
        yield assertTrue(adm.limit < 5, adm.limit >= 2)
      },
      test("what the limit has shrunk to is what requests are held to") {
        val adaptive = AdaptiveSettings(enabled = true, minLimit = 2, maxLimit = 20, tolerance = 1.5, smoothing = 1.0, lagTarget = 50.millis)
        for
          adm <- InboundAdmission.make(settings(max = 5).copy(adaptive = adaptive))
          _ <- ZIO.foreachDiscard(1 to 30)(_ => ZIO.succeed(adm.recordLag(10_000_000_000L)) *> adm.adapt)
          limit = adm.limit
          entered <- Ref.make(0)
          gate <- Promise.make[Nothing, Unit]
          app = routes(entered, gate) @@ adm.middleware(tierOf)
          held <- occupy(app, "/critical", limit, entered)
          over <- app.runZIO(get("/critical"))
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(held)(_.join)
        yield assertTrue(limit >= 2, limit <= 5, over.status == Status.ServiceUnavailable)
      },
      test("with adaptation off the limit is maxInFlight, and turning it on starts from it, inside the bounds") {
        val on = settings(max = 5).copy(adaptive = AdaptiveSettings.default.copy(enabled = true, minLimit = 2, maxLimit = 12))
        val tight = settings(max = 50).copy(adaptive = AdaptiveSettings.default.copy(enabled = true, minLimit = 2, maxLimit = 12))
        for
          adm <- InboundAdmission.make(settings(max = 5))
          off = adm.limit
          _ <- adm.update(on)
          started = adm.limit
          other <- InboundAdmission.make(settings(max = 5))
          _ <- other.update(tight)
        yield assertTrue(off == 5, started == 5, other.limit == 12)
      },
      test("the limit that was learned stays through a settings update that keeps it inside the bounds") {
        val adaptive = AdaptiveSettings(enabled = true, minLimit = 2, maxLimit = 20, tolerance = 1.5, smoothing = 1.0, lagTarget = 50.millis)
        for
          adm <- InboundAdmission.make(settings(max = 5).copy(adaptive = adaptive))
          _ <- ZIO.foreachDiscard(1 to 10)(_ => ZIO.succeed(adm.recordLag(1_000_000_000L)) *> adm.adapt)
          learned = adm.limit
          _ <- adm.update(settings(max = 5).copy(adaptive = adaptive))
        yield assertTrue(adm.limit == learned, learned < 5)
      },
    ),
    test("settings can be replaced while the service runs") {
      for
        adm <- InboundAdmission.make(settings(max = 1))
        entered <- Ref.make(0)
        gate <- Promise.make[Nothing, Unit]
        app = routes(entered, gate) @@ adm.middleware(tierOf)
        held <- occupy(app, "/critical", 1, entered)
        full <- app.runZIO(get("/critical"))
        _ <- adm.update(settings(max = 2))
        more <- occupy(app, "/critical", 1, entered)
        _ <- gate.succeed(())
        _ <- ZIO.foreachDiscard(held ++ more)(_.join)
      yield assertTrue(full.status == Status.ServiceUnavailable)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
