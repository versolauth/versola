package versola.util.http

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.util.concurrent.ThreadLocalRandom

/** How much of the per-request `receive-http` line is written. Rendering and writing that line is the largest single
  * logging cost of a service (about 17% of an edge's CPU and 12% of auth's in a JFR profile under load), and nearly all
  * of it is successful requests nobody reads.
  *
  *   - `Full`: every request.
  *   - `Sampled`: failures and slow requests always, other requests for a share of traces chosen by trace id.
  *   - `Off`: failures (status 400 and above, or an unhandled error) and slow requests only.
  *
  * Metrics (`http_server_requests_total`, `http_server_request_duration_seconds`) are not affected by any mode.
  */
enum AccessLogMode:
  case Full, Sampled, Off

object AccessLogMode:
  def parse(value: String): Option[AccessLogMode] =
    value.trim.toLowerCase match
      case "full" => Some(Full)
      case "sampled" => Some(Sampled)
      case "off" => Some(Off)
      case _ => None

/** The resolved access-log settings of a process, read on every request.
  *
  * @param slowMillis
  *   a request taking at least this long is logged in `Sampled` mode whatever its outcome; the same threshold lifts a
  *   successful outbound call (`send-http`) from `DEBUG` to `INFO`
  * @param successSampleRate
  *   share of traces whose successful requests are logged in `Sampled` mode, 0 to 1 (0.05: one trace in 20)
  */
final case class AccessLogSettings(
    mode: AccessLogMode,
    slowMillis: Long,
    successSampleRate: Double,
):

  /** Whether the `receive-http` line of a request is written.
    *
    * @param failed
    *   an error was recorded against the request (an unhandled throwable), whatever the status
    * @param traceId
    *   hex trace id of the request, so every hop of one trace takes the same decision; a missing or invalid one (no
    *   tracing) is sampled at random instead
    */
  def logReceive(status: Int, failed: Boolean, elapsedMillis: Long, traceId: String): Boolean =
    mode match
      case AccessLogMode.Full => true
      // A `429` is a refusal by a rate limit, counted by its own metric: logged like a success, so a flood of refused calls
      // cannot become a flood of log lines. Any other 4xx, and every unhandled error, is always logged.
      case _ if failed || (status >= 400 && status != 429) => true
      case _ if elapsedMillis >= slowMillis => true
      case AccessLogMode.Off => false
      case AccessLogMode.Sampled => AccessLogSettings.sampled(traceId, successSampleRate)

  /** Whether a successful outbound call is written at `INFO` rather than `DEBUG`: only a slow one. */
  def isSlow(elapsedMillis: Long): Boolean = elapsedMillis >= slowMillis

object AccessLogSettings:
  val defaultSlowMillis = 500L
  val defaultSuccessSampleRate = 0.05

  /** What a service logs unless it says otherwise: every request. auth and central stay here for good; the edge starts from
    * its own defaults (see `PostgresEdgeApp`) and an operator's [[AccessLogPolicy]] is applied over them. There is no
    * environment variable for any of it. */
  val default: AccessLogSettings = AccessLogSettings(AccessLogMode.Full, defaultSlowMillis, defaultSuccessSampleRate)

  /** A uniform position in `[0, 1)` derived from the trace id alone, so the same trace lands on the same side of every
    * rate in every process. Trace ids are random already; the mixing only keeps a prefix-heavy or non-hex id from
    * skewing the share. */
  private[http] def bucket(traceId: String): Double =
    var h = traceId.hashCode.toLong & 0xffffffffL
    h ^= h >>> 16
    h = (h * 0x85ebca6bL) & 0xffffffffL
    h ^= h >>> 13
    h = (h * 0xc2b2ae35L) & 0xffffffffL
    h ^= h >>> 16
    h.toDouble / 4294967296.0

  private val InvalidTraceId = "00000000000000000000000000000000"

  private[http] def sampled(traceId: String, rate: Double): Boolean =
    if rate <= 0 then false
    else if rate >= 1 then true
    else if traceId.isEmpty || traceId == InvalidTraceId then ThreadLocalRandom.current().nextDouble() < rate
    else bucket(traceId) < rate

/** What an operator sets, per edge, for the access log: the wire form for central to hold and send to the edge, shaped like
  * [[OverloadPolicy]]. Every field is optional and means "the edge's built-in default" (see `PostgresEdgeApp`) when absent,
  * so a policy is a patch over those defaults. Unlike the overload policy it has no environment base: the access log
  * protects nothing before the first sync, so there is one source of truth, central.
  *
  * Applied to [[AccessLogSettings]] with [[applyTo]]; checked with [[validate]], which central would run before storing and
  * the edge before applying.
  *
  * @param mode
  *   `full`, `sampled` or `off`
  */
final case class AccessLogPolicy(
    mode: Option[String] = None,
    slowMillis: Option[Long] = None,
    successSampleRate: Option[Double] = None,
) derives JsonCodec, Schema:

  def validate: Either[String, AccessLogPolicy] =
    for
      _ <- OverloadPolicy.check(mode.forall(m => AccessLogMode.parse(m).isDefined), "mode must be full, sampled or off")
      _ <- OverloadPolicy.check(slowMillis.forall(v => v >= 0 && v <= 600000), "slowMillis must be between 0 and 600000")
      _ <- OverloadPolicy.inRange("successSampleRate", successSampleRate, 0.0, 1.0)
    yield this

  def applyTo(base: AccessLogSettings): AccessLogSettings =
    base.copy(
      mode = mode.flatMap(AccessLogMode.parse).getOrElse(base.mode),
      slowMillis = slowMillis.getOrElse(base.slowMillis),
      successSampleRate = successSampleRate.getOrElse(base.successSampleRate),
    )

object AccessLogPolicy:
  val empty: AccessLogPolicy = AccessLogPolicy()
