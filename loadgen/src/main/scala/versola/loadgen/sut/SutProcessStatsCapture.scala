package versola.loadgen.sut

import versola.loadgen.config.SutProcessServiceConfig
import versola.loadgen.store.{SutProcessSnapshotRepository, SutProcessSnapshotRow, SutStatPhase}
import zio.http.{Client, Request, Response, Status, URL}
import zio.{Clock, Duration, Task, UIO, ZIO}

/** The process half of the campaign report: a scrape of every SUT service's `/metrics` at the
  * start of the run and another at the end, and the difference between them.
  *
  * The report already states how many operations of each kind the run completed, and what they
  * cost each database. This is what the service in between cost, which is the half that turns
  * both of the others into a cost per login.
  *
  * Shaped after [[SutStatsCapture]] throughout -- two boundaries, fail-open, differences read
  * back from the store rather than from memory -- because it is the same procedure against a
  * different instrument, and a coordinator that took over mid-campaign has to be able to report
  * a run its predecessor started in exactly the same way.
  */
trait SutProcessStatsCapture:

  /** Takes one boundary's scrape of every configured service.
    *
    * Cannot fail, for [[SutStatsCapture.capture]]'s reason and with less to weigh: a service
    * whose diagnostics port is unreachable, or which runs a build without
    * `VersolaApp.jvmRuntimeMetrics`, must cost the campaign this section and nothing else.
    * Refusing to start a ten-hour run over an HTTP GET would be absurd.
    */
  def capture(campaign: String, phase: SutStatPhase): UIO[Unit]

  /** The differences for every service that has both of its boundaries recorded. */
  def deltas(campaign: String): Task[List[SutProcessStatsDelta]]

final class HttpSutProcessStatsCapture(
    services: List[SutProcessServiceConfig],
    snapshots: SutProcessSnapshotRepository,
    client: Client,
) extends SutProcessStatsCapture:

  /** Taken once rather than per request, as `HttpPlanClient` does: a batched client per request
    * builds a `ZLayer` per request and defeats the shared connection pool.
    */
  private val http = client.batched

  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    ZIO.foreachDiscard(services): target =>
      captureOne(campaign, phase, target).catchAllCause: cause =>
        ZIO.logWarningCause(
          s"Could not scrape /metrics from the SUT's '${target.name}' service " +
            s"${SutProcessStatsCapture.label(phase)} campaign '$campaign'; the report will have no section for it",
          cause,
        )

  override def deltas(campaign: String): Task[List[SutProcessStatsDelta]] =
    snapshots.loadCampaign(campaign).map(SutProcessStatsDelta.from)

  private def captureOne(campaign: String, phase: SutStatPhase, target: SutProcessServiceConfig): Task[Unit] =
    for
      url <- ZIO
        .fromEither(URL.decode(target.metricsUrl))
        .mapError(error =>
          InvalidMetricsUrl(s"sut-process-stats.services['${target.name}'].metrics-url is not a URL: ${error.getMessage}"),
        )
      response <- scrape(url)
      body <- response.body.asString
      _ <- ZIO
        .fail(MetricsRefused(target.name, response.status.code))
        .when(response.status != Status.Ok)
      reading <- ZIO
        .fromOption(SutProcessStatsReader.parse(body))
        .orElseFail(NoRuntimeMetrics(target.name))
      now <- Clock.instant
      _ <- snapshots.append(
        SutProcessSnapshotRow(
          campaign = campaign,
          service = target.name,
          phase = phase,
          capturedAt = now,
          startedAtEpochSeconds = reading.startedAtEpochSeconds,
          statistics = reading.stats,
        ),
      )
      _ <- ZIO.logInfo(
        s"Scraped /metrics from the SUT's '${target.name}' service ${SutProcessStatsCapture.label(phase)} " +
          s"campaign '$campaign' (${reading.stats.counters.cpuSeconds} CPU-seconds since process start)",
      )
    yield ()

  /** Bounded for [[SutStatsCapture.connect]]'s reason: a campaign boundary waits on this, and a
    * service that accepts the connection and then stops answering would hold the transition for
    * as long as it cared to.
    */
  private def scrape(url: URL): Task[Response] =
    http.request(Request.get(url)).timeoutFail(MetricsUnreachable(url.encode))(SutProcessStatsCapture.scrapeTimeout)

object SutProcessStatsCapture:

  /** Shorter than [[SutStatsCapture.socketTimeoutSeconds]]: serving this endpoint is reading
    * already-collected gauges out of memory, so a service that has not answered in five seconds
    * is not slow, it is in trouble -- and the campaign's boundary is waiting.
    */
  val scrapeTimeout: Duration = Duration.fromSeconds(5)

  private[sut] def label(phase: SutStatPhase): String = phase match
    case SutStatPhase.Before => "before"
    case SutStatPhase.After => "after"

case class InvalidMetricsUrl(reason: String) extends RuntimeException(reason)

case class MetricsUnreachable(url: String) extends RuntimeException(s"$url did not answer within the scrape timeout")

case class MetricsRefused(service: String, status: Int)
  extends RuntimeException(s"the '$service' service answered $status to GET /metrics")

/** The endpoint answered, but with no `process_cpu_seconds_total` in it -- a service running a
  * build from before `VersolaApp.jvmRuntimeMetrics`. Distinct from an unreachable endpoint
  * because the fix is different and an operator reading the log needs to know which it is.
  */
case class NoRuntimeMetrics(service: String)
  extends RuntimeException(
    s"the '$service' service publishes no JVM runtime metrics; it is running a build without them",
  )
