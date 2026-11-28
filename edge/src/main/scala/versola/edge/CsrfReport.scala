package versola.edge

import versola.edge.CsrfGuard.Verdict
import zio.*
import zio.http.Request
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.ConcurrentHashMap

/** What an operator sees of [[CsrfGuard]]: a count of every call it refused or could not place, and a log line that names the origin.
  *
  * Counted in `edge_cookie_origin_decisions_total{surface,result}` (`result`: `refused_origin`, `refused_fetch_site`, `no_headers`; an origin is
  * never a label). Logged at most once a minute for each (surface, result, origin, preset): the check runs before the token is read, so
  * anyone can make a call that is refused, and a line for each would be a line for each of their calls. At most [[MaxKeys]] keys are
  * remembered; past that a new one is counted and not logged until an old one has expired.
  *
  * What the log is for: the upgrade that brings the check makes a browser app on another origin meet `403`, and the origin that was refused is
  * what the operator has to put into the preset.
  */
final class CsrfReport(surface: String):
  import CsrfReport.*

  private val lastLogged = new ConcurrentHashMap[String, java.lang.Long]()

  def record(verdict: Verdict, request: Request, preset: String): UIO[Unit] =
    verdict match
      case Verdict.Allowed => ZIO.unit
      case _ =>
        val (result, refused) = verdict match
          case Verdict.RefusedOrigin => ("refused_origin", true)
          case Verdict.RefusedFetchSite => ("refused_fetch_site", true)
          case _ => ("no_headers", false)
        val origin = printable(request.rawHeader("Origin").getOrElse(request.rawHeader("Sec-Fetch-Site").map("Sec-Fetch-Site: " + _).getOrElse("")))
        counter.tagged(Set(MetricLabel("surface", surface), MetricLabel("result", result))).increment *>
          Clock.nanoTime.flatMap: now =>
            ZIO.when(shouldLog(s"$surface|$result|$origin|$preset", now)):
              val line =
                s"cookie call on $surface ${if refused then "refused" else "has no origin headers"} ($result): ${request.method} origin='$origin' preset=$preset" +
                  (if refused then ". Put the origin of the app in the preset's post-login redirect URI if it is yours" else "")
              if refused then ZIO.logWarning(line) else ZIO.logInfo(line)
        .unit

  private def shouldLog(key: String, now: Long): Boolean =
    val last: java.lang.Long = lastLogged.get(key)
    if last != null && now - last.longValue < WindowNanos then false
    else
      if last == null && lastLogged.size >= MaxKeys then
        lastLogged.entrySet.removeIf(entry => now - entry.getValue.longValue >= WindowNanos)
      if last == null && lastLogged.size >= MaxKeys then false
      else
        lastLogged.put(key, java.lang.Long.valueOf(now))
        true

object CsrfReport:
  val MaxKeys = 256
  private val WindowNanos = 60L * 1_000_000_000L
  private val MaxOriginLength = 100

  private val counter = Metric.counter("edge_cookie_origin_decisions_total")

  /** What a caller wrote, fit for a log line: printable ASCII only, without a quote or a backslash, and short. */
  private[edge] def printable(value: String): String =
    value.take(MaxOriginLength).map(c => if c >= ' ' && c <= '~' && c != '\'' && c != '\\' then c else '?')
