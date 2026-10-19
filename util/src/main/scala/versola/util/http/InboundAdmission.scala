package versola.util.http

import zio.*
import zio.http.*
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
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
):
  /** Calls in flight a request of `tier` may find and still be admitted. */
  def threshold(tier: Tier): Int = tier match
    case Tier.Never | Tier.Critical => maxInFlight
    case Tier.Continuation => math.max(1, math.ceil(maxInFlight * continuationShare).toInt)
    case Tier.Start => math.max(1, math.ceil(maxInFlight * startShare).toInt)

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
    parse(env("HTTP_ADMISSION_MODE"), env("HTTP_ADMISSION_MAX_IN_FLIGHT"), env("HTTP_ADMISSION_CONTINUATION_SHARE"),
      env("HTTP_ADMISSION_START_SHARE"), env("HTTP_ADMISSION_RETRY_AFTER"))

/** Admission control at the entry of a service: bounded calls in flight, refused above the tier's share of the limit, at once
  * and without a queue. A queue in front of a pod that cannot serve is where overload turns into latency for everyone.
  *
  * The settings are replaced as a whole while the process runs ([[update]]), which is also what an adaptive limit will do.
  *
  * Metrics: `http_admission_in_flight`, `http_admission_in_flight_peak` (the highest value since the previous sample: a stall
  * is over before a 10 s scrape sees its top), `http_admission_limit`, and `http_admission_refused_total` by `tier` and `mode`
  * (in `shadow`: what `enforce` would have refused).
  */
final class InboundAdmission private (settings: AtomicReference[InboundAdmissionSettings]):
  import InboundAdmission.*

  private val inFlight = new AtomicInteger(0)
  private val peak = new AtomicInteger(0)

  def current: InboundAdmissionSettings = settings.get
  def update(next: InboundAdmissionSettings): UIO[Unit] = ZIO.succeed(settings.set(next))
  def inFlightNow: Int = inFlight.get

  /** Takes a place if there is one for `tier`. */
  private def tryEnter(tier: Tier, s: InboundAdmissionSettings): Boolean =
    val limit = s.threshold(tier)
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
              if tryEnter(tier, s) then restore(handler(request)).ensuring(ZIO.succeed(inFlight.decrementAndGet()))
              else
                val tags = Set(MetricLabel("tier", tier.toString.toLowerCase), MetricLabel("mode", s.mode.toString.toLowerCase))
                refused.tagged(tags).increment *>
                  (if enforce then ZIO.succeed(refusal(s))
                   else
                     // shadow: let it through, counted in flight like any other so the numbers stay honest
                     ZIO.succeed(inFlight.incrementAndGet()) *>
                       restore(handler(request)).ensuring(ZIO.succeed(inFlight.decrementAndGet())))

  /** A middleware classifying every route by its method and pattern; routes the function does not name are `Critical`. */
  def middleware(tierOf: (Method, String) => Tier): Middleware[Any] = new Middleware[Any]:
    def apply[Env1 <: Any, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      Routes.fromIterable(routes.routes.map(route =>
        route.transform(protect(tierOf(route.routePattern.method, route.routePattern.pathCodec.render))),
      ))

  /** Publishes the gauges once a second; run it for the life of the service. */
  val sample: ZIO[Any, Nothing, Nothing] =
    (for
      _ <- inFlightGauge.set(inFlight.get.toDouble)
      _ <- peakGauge.set(peak.getAndSet(inFlight.get).toDouble)
      _ <- limitGauge.set(settings.get.maxInFlight.toDouble)
    yield ()).repeat(Schedule.fixed(1.second)).forever

object InboundAdmission:
  private val inFlightGauge = Metric.gauge("http_admission_in_flight")
  private val peakGauge = Metric.gauge("http_admission_in_flight_peak")
  private val limitGauge = Metric.gauge("http_admission_limit")
  private val refused = Metric.counter("http_admission_refused_total")

  def make(initial: InboundAdmissionSettings): UIO[InboundAdmission] =
    ZIO.succeed(new InboundAdmission(new AtomicReference(initial)))
