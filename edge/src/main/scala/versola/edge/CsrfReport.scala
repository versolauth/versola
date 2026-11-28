package versola.edge

import versola.edge.CsrfGuard.Verdict
import zio.*
import zio.http.Request
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What an operator sees of [[CsrfGuard]]: a count of every call it refused or could not place, and a log line that names the origin of a refusal (a call with no origin headers is only counted: there is nothing to name).
  *
  * Counted in `edge_cookie_origin_decisions_total{surface,result}` (`result`: `refused_origin`, `refused_fetch_site`, `no_headers`; an origin is
  * never a label). Logged at most once a minute for each (surface, result, origin, preset): the check runs before the token is read, so
  * anyone can make a call that is refused, and a line for each would be a line for each of their calls. About [[MaxKeys]] keys are
  * remembered; past that a new one is counted and not named in the log until an old one has expired, and one line per minute says so, so
  * that someone who fills the table with made-up origins does not silence the log without it showing.
  *
  * What the log is for: the upgrade that brings the check makes a browser app on another origin meet `403`, and the origin that was refused is
  * what the operator has to put into the preset.
  */
final class CsrfReport(surface: String):
  import CsrfReport.*

  private val lastLogged = new ConcurrentHashMap[String, java.lang.Long]()
  private val lastSaturated = new AtomicLong(Long.MinValue)

  def record(verdict: Verdict, request: Request, preset: String): UIO[Unit] =
    verdict match
      case Verdict.Allowed => ZIO.unit
      case Verdict.NoHeaders =>
        // only counted: a script or an old client with the cookie is not worth a line, and nothing is said of where it came from
        counter.tagged(Set(MetricLabel("surface", surface), MetricLabel("result", "no_headers"))).increment
      case _ =>
        val result = if verdict == Verdict.RefusedOrigin then "refused_origin" else "refused_fetch_site"
        val origin = printable(request.rawHeader("Origin").getOrElse(request.rawHeader("Sec-Fetch-Site").map("Sec-Fetch-Site: " + _).getOrElse("")))
        counter.tagged(Set(MetricLabel("surface", surface), MetricLabel("result", result))).increment *>
          Clock.nanoTime.flatMap: now =>
            shouldLog(s"$surface|$result|$origin|$preset", now) match
              case Logged.Yes =>
                ZIO.logWarning(
                  s"cookie call on $surface refused ($result): ${request.method} origin='$origin' preset=$preset. " +
                    "Put the origin of the app in the preset's post-login redirect URI if it is yours",
                )
              case Logged.Saturated if lastSaturated.get == Long.MinValue || now - lastSaturated.get >= WindowNanos =>
                lastSaturated.set(now)
                ZIO.logWarning(
                  s"cookie calls on $surface refused from more than $MaxKeys different origins within a minute: further origins are counted and not named " +
                    s"until it is quieter ($result is the last)",
                )
              case _ => ZIO.unit
        .unit

  /** `Yes`: log it. `No`: logged within the last minute. `Saturated`: a new key, and the table is full of keys from the last minute. */
  private def shouldLog(key: String, now: Long): Logged =
    val last: java.lang.Long = lastLogged.get(key)
    if last != null && now - last.longValue < WindowNanos then Logged.No
    else
      if last == null && lastLogged.size >= MaxKeys then
        lastLogged.entrySet.removeIf(entry => now - entry.getValue.longValue >= WindowNanos)
      if last == null && lastLogged.size >= MaxKeys then Logged.Saturated
      else
        lastLogged.put(key, java.lang.Long.valueOf(now))
        Logged.Yes

object CsrfReport:
  private enum Logged:
    case Yes, No, Saturated

  val MaxKeys = 256
  private val WindowNanos = 60L * 1_000_000_000L
  private val MaxOriginLength = 100

  private val counter = Metric.counter("edge_cookie_origin_decisions_total")

  /** What a caller wrote, fit for a log line: printable ASCII only, without a quote or a backslash, and short. */
  private[edge] def printable(value: String): String =
    value.take(MaxOriginLength).map(c => if c >= ' ' && c <= '~' && c != '\'' && c != '\\' then c else '?')
