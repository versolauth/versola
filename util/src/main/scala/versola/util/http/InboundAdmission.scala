package versola.util.http

import zio.*
import zio.http.*
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference, LongAdder}
import scala.util.Try

/** How much a request matters when the pod is full. A request is admitted while the calls in flight are below the tier's share
  * of the limit, so the top of the limit stays free for the more important tiers (the way Stripe reserves a fraction of its fleet
  * for critical requests, and Google's criticality levels shed the lower ones first).
  *
  *   - `Never`: not counted and never refused (health and metrics, config sync, back-channel logout).
  *   - `Critical`: refused last (a user already inside: the proxied calls).
  *   - `Continuation`: work the user has already started (code exchange, logout).
  *   - `Start`: work not yet started and the most expensive (a new login); refused first.
  */
enum Tier:
  case Never, Critical, Continuation, Start

/** `Off` does nothing; `Shadow` counts what `Enforce` would refuse and refuses nothing; `Enforce` answers `503` with `Retry-After`. */
final case class InboundAdmissionSettings(
    mode: GuardMode,
    maxInFlight: Int,
    continuationShare: Double,
    startShare: Double,
    retryAfterSeconds: Int,
    adaptive: AdaptiveSettings = AdaptiveSettings.default,
):
  /** Calls in flight a request of `tier` may find and still be admitted, out of `limit` places. */
  def threshold(tier: Tier, limit: Int): Int = tier match
    case Tier.Never | Tier.Critical => limit
    case Tier.Continuation => math.max(1, math.ceil(limit * continuationShare).toInt)
    case Tier.Start => math.max(1, math.ceil(limit * startShare).toInt)

  def threshold(tier: Tier): Int = threshold(tier, maxInFlight)

object InboundAdmissionSettings:
  val defaultMaxInFlight = 256
  val defaultContinuationShare = 0.85
  val defaultStartShare = 0.65

  /** From raw values (`None` when unset). An invalid value falls back to its default, so a typo cannot take the service down. */
  def parse(
      mode: Option[String],
      maxInFlight: Option[String],
      continuationShare: Option[String],
      startShare: Option[String],
      retryAfter: Option[String],
  ): InboundAdmissionSettings =
    def share(raw: Option[String], default: Double) =
      raw.flatMap(v => Try(v.trim.toDouble).toOption).filter(s => s > 0 && s <= 1).getOrElse(default)
    val cont = share(continuationShare, defaultContinuationShare)
    // a lower tier never gets more room than a higher one
    val start = math.min(share(startShare, defaultStartShare), cont)
    InboundAdmissionSettings(
      mode = mode.flatMap(GuardMode.parse).getOrElse(GuardMode.Shadow),
      maxInFlight = maxInFlight.flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(defaultMaxInFlight),
      continuationShare = cont,
      startShare = start,
      retryAfterSeconds = retryAfter.flatMap(_.trim.toIntOption).filter(s => s > 0 && s <= 60).getOrElse(1),
    )

  def fromEnv: InboundAdmissionSettings =
    def env(n: String) = Option(java.lang.System.getenv(n))
    parse(
      env("HTTP_ADMISSION_MODE"),
      env("HTTP_ADMISSION_MAX_IN_FLIGHT"),
      env("HTTP_ADMISSION_CONTINUATION_SHARE"),
      env("HTTP_ADMISSION_START_SHARE"),
      env("HTTP_ADMISSION_RETRY_AFTER"),
    )

/** Admission control at the entry of a service: bounded calls in flight, refused above the tier's share of the limit, at once
  * and without a queue. A queue in front of a pod that cannot serve is where overload turns into latency for everyone.
  *
  * The settings are replaced as a whole while the process runs ([[update]]). While the mode is not `off` the limit is a value
  * [[AdaptiveLimit]] keeps inside `[minLimit, maxLimit]` from the call times it observes and from the lag of the executor;
  * `maxInFlight` is where it starts (clamped into those bounds). `shadow` learns and counts what `enforce` would refuse;
  * `enforce` refuses above the learned limit.
  *
  * Metrics: `http_admission_in_flight`, `http_admission_in_flight_peak` (the highest value since the previous sample: a stall
  * is over before a 10 s scrape sees its top), `http_admission_limit`, and `http_admission_refused_total` by `tier` and `mode`
  * (in `shadow`: what `enforce` would have refused). Also `http_admission_adaptive_limit_ratio` (the learned limit over its ceiling), `http_admission_rtt_seconds` by `tier` and
  * `http_executor_lag_seconds` (the worst lateness of a 10 ms timer in the last second).
  */
final class InboundAdmission private (settings: AtomicReference[InboundAdmissionSettings]):
  import InboundAdmission.*

  private val inFlight = new AtomicInteger(0)
  private val peak = new AtomicInteger(0)

  // the limit adaptation has arrived at
  private val adaptation = new AtomicReference(AdaptiveLimit.initial(settings.get.adaptive, Classes, settings.get.maxInFlight))
  private val learned = new AtomicInteger(adaptation.get.limit.toInt)
  // what the window being collected has seen, per class: summed call time (ns) and calls completed
  private val rttSum = Array.fill(Classes)(new LongAdder)
  private val rttCount = Array.fill(Classes)(new LongAdder)
  private val windowPeak = new AtomicInteger(0)
  private val lagMax = new AtomicLong(0)

  /** The number of places in force now. */
  def limit: Int = learned.get

  def current: InboundAdmissionSettings = settings.get
  def update(next: InboundAdmissionSettings): UIO[Unit] =
    ZIO.succeed:
      val before = settings.getAndSet(next)
      // Switched on, or a new starting value: start from `maxInFlight`. Otherwise what was learned stays if it is still
      // inside the new bounds, and is brought inside them if not.
      val state =
        if (before.mode == GuardMode.Off && next.mode != GuardMode.Off) || before.maxInFlight != next.maxInFlight then
          AdaptiveLimit.initial(next.adaptive, Classes, next.maxInFlight)
        else AdaptiveLimit.rebound(adaptation.get, next.adaptive)
      adaptation.set(state)
      learned.set(state.limit.toInt)
  def inFlightNow: Int = inFlight.get

  /** Takes a place if there is one for `tier`. */
  private def tryEnter(tier: Tier, s: InboundAdmissionSettings): Boolean =
    val limit = s.threshold(tier, learned.get)
    var entered = false
    var done = false
    while !done do
      val cur = inFlight.get
      if cur >= limit then done = true
      else if inFlight.compareAndSet(cur, cur + 1) then
        entered = true
        done = true
        // the highest value since the last sample
        var p = peak.get
        while cur + 1 > p && !peak.compareAndSet(p, cur + 1) do p = peak.get
        var w = windowPeak.get
        while cur + 1 > w && !windowPeak.compareAndSet(w, cur + 1) do w = windowPeak.get
    entered

  private def refusal(s: InboundAdmissionSettings): Response =
    Response.status(Status.ServiceUnavailable).addHeader(Header.Custom("Retry-After", s.retryAfterSeconds.toString))

  /** Wraps one route's handler. `tier` is chosen once, when the route is built. */
  def protect[Env](tier: Tier)(handler: Handler[Env, Response, Request, Response]): Handler[Env, Response, Request, Response] =
    if tier == Tier.Never then handler
    else
      Handler.scoped[Env]:
        Handler.fromFunctionZIO[Request]: request =>
          val s = settings.get
          if s.mode == GuardMode.Off then handler(request)
          else
            val enforce = s.mode == GuardMode.Enforce
            // Uninterruptible from taking the place to the `ensuring` that gives it back: an interrupt landing between the two
            // would leak a place for good, and enough leaked places refuse everything.
            ZIO.uninterruptibleMask: restore =>
              if tryEnter(tier, s) then served(tier, handler, request, restore)
              else
                val tags = Set(MetricLabel("tier", tier.toString.toLowerCase), MetricLabel("mode", s.mode.toString.toLowerCase))
                refused.tagged(tags).increment *>
                  (if enforce then ZIO.succeed(refusal(s))
                   else
                     // shadow: let it through, counted in flight like any other so the numbers stay honest
                     ZIO.succeed(inFlight.incrementAndGet()) *> served(tier, handler, request, restore))

  /** Runs the handler of an admitted request, gives its place back when it ends, and keeps its time for [[AdaptiveLimit]]
    * (an interrupted request, a client that went away, says nothing about how long the work takes, so it is left out).
    */
  private def served[Env](
      tier: Tier,
      handler: Handler[Env, Response, Request, Response],
      request: Request,
      restore: ZIO.InterruptibilityRestorer,
  ): ZIO[Scope & Env, Response, Response] =
    Clock.nanoTime.flatMap: started =>
      restore(handler(request)).onExit: exit =>
        ZIO.succeed(inFlight.decrementAndGet()) *>
          ZIO.when(!exit.isInterrupted && settings.get.mode != GuardMode.Off)(
            Clock.nanoTime.map(now => record(tier, now - started)),
          )

  private[http] def record(tier: Tier, nanos: Long): Unit =
    val i = classOf(tier)
    rttSum(i).add(nanos)
    rttCount(i).increment()

  /** One adaptation step over the window that just ended. */
  private[http] val adapt: UIO[Unit] =
    ZIO.succeed:
      val s = settings.get.adaptive
      val windows = Vector.tabulate(Classes): i =>
        val n = rttCount(i).sumThenReset()
        val sum = rttSum(i).sumThenReset()
        RttWindow(if n > 0 then sum.toDouble / n else 0.0, n.toInt)
      val lag = Duration.fromNanos(lagMax.getAndSet(0))
      val peakInWindow = windowPeak.getAndSet(inFlight.get)
      if settings.get.mode != GuardMode.Off then
        val next = AdaptiveLimit.step(s, adaptation.get, windows, peakInWindow, lag)
        adaptation.set(next)
        learned.set(next.limit.toInt)
      (windows, lag)
    .flatMap: (windows, lag) =>
      lagGauge.set(lag.toNanos / 1e9) *>
        ZIO.foreachDiscard(windows.zipWithIndex): (w, i) =>
          ZIO.when(w.completed > 0)(rttGauge.tagged("tier", ClassNames(i)).set(w.meanRtt / 1e9))

  /** Measures how late the executor runs a short timer. A stalled pod completes no calls, so call times cannot show it; a
    * timer that fires seconds late can.
    */
  private[http] def recordLag(nanos: Long): Unit = lagMax.accumulateAndGet(nanos, math.max)

  private val probe: UIO[Nothing] =
    (for
      before <- Clock.nanoTime
      _ <- ZIO.sleep(ProbeInterval)
      after <- Clock.nanoTime
      late = (after - before - ProbeInterval.toNanos).max(0)
      _ = recordLag(late)
    yield ()).forever

  /** A middleware classifying every route by its method and pattern; routes the function does not name are `Critical`. */
  def middleware(tierOf: (Method, String) => Tier): Middleware[Any] = new Middleware[Any]:
    def apply[Env1 <: Any, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      Routes.fromIterable(routes.routes.map(route =>
        route.transform(protect(tierOf(route.routePattern.method, route.routePattern.pathCodec.render))),
      ))

  /** Publishes the gauges and adapts the limit once a second, and runs the lag probe; run it for the life of the service. */
  val sample: ZIO[Any, Nothing, Nothing] =
    (for
      _ <- adapt
      _ <- inFlightGauge.set(inFlight.get.toDouble)
      _ <- peakGauge.set(peak.getAndSet(inFlight.get).toDouble)
      _ <- limitGauge.set(limit.toDouble)
      _ <- gradientGauge.set(adaptation.get.limit / math.max(1, settings.get.adaptive.maxLimit))
    yield ()).repeat(Schedule.fixed(1.second)).forever.race(probe)

object InboundAdmission:
  private val inFlightGauge = Metric.gauge("http_admission_in_flight")
  private val peakGauge = Metric.gauge("http_admission_in_flight_peak")
  private val limitGauge = Metric.gauge("http_admission_limit")
  private val refused = Metric.counter("http_admission_refused_total")
  private val lagGauge = Metric.gauge("http_executor_lag_seconds")
  private val rttGauge = Metric.gauge("http_admission_rtt_seconds")
  private val gradientGauge = Metric.gauge("http_admission_adaptive_limit_ratio")

  private val Classes = 3
  private val ClassNames = Vector("critical", "continuation", "start")
  private val ProbeInterval: Duration = 10.millis

  private def classOf(tier: Tier): Int = tier match
    case Tier.Never | Tier.Critical => 0
    case Tier.Continuation => 1
    case Tier.Start => 2

  def make(initial: InboundAdmissionSettings): UIO[InboundAdmission] =
    ZIO.succeed(new InboundAdmission(new AtomicReference(initial)))
