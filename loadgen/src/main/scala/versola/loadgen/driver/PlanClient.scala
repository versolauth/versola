package versola.loadgen.driver

import versola.loadgen.coordinator.{DriverReport, LoadPlan}
import zio.*
import zio.http.*
import zio.json.{DecoderOps, EncoderOps}

/** The driver's side of the two calls it makes to the coordinator: `GET /plan` and
  * `POST /drivers/report` (§12).
  *
  * Deliberately not a [[versola.loadgen.protocol]] client. Those measure the SUT -- every hop
  * they make lands in a histogram and in the error taxonomy -- and a poll of the coordinator is
  * the emulator talking to itself. Counting it would put the emulator's own control plane inside
  * the campaign's error budget, which is exactly the confusion §4 exists to prevent.
  */
trait PlanClient:
  def fetch: Task[LoadPlan]

  def report(report: DriverReport): Task[Unit]

object PlanClient:

  /** Short against the poll interval: the plan is polled every `coordinator.poll-interval`, and a
    * request still outstanding when the next poll is due is one whose answer is already stale.
    * A driver keeps running on the last plan it fetched (§12), so a timed-out poll costs nothing
    * but the freshness of that one interval.
    */
  val requestTimeout: Duration = 5.seconds

  def make(client: Client, baseUrl: String, requestTimeout: Duration): Task[PlanClient] =
    for
      base <- ZIO
        .fromEither(URL.decode(baseUrl))
        .mapError(error => InvalidCoordinatorUrl(s"coordinator.url '$baseUrl' is not a URL: ${error.getMessage}"))
      plan <- resolve(base, "/plan")
      reports <- resolve(base, "/drivers/report")
    yield HttpPlanClient(client, plan, reports, requestTimeout)

  private def resolve(base: URL, path: String): Task[URL] =
    ZIO
      .fromEither(URL.decode(base.encode + path))
      .mapError(error => InvalidCoordinatorUrl(s"coordinator.url cannot address $path: ${error.getMessage}"))

private final class HttpPlanClient(
    client: Client,
    planUrl: URL,
    reportUrl: URL,
    requestTimeout: Duration,
) extends PlanClient:

  /** Taken once, as `HttpExchange` takes it: a batched client per request builds a `ZLayer` per
    * request and defeats the shared connection pool.
    */
  private val http = client.batched

  override def fetch: Task[LoadPlan] =
    for
      response <- send(Request.get(planUrl))
      body <- response.body.asString
      _ <- ZIO
        .fail(CoordinatorRefusedPoll(response.status.code, body))
        .when(response.status != Status.Ok)
      plan <- ZIO.fromEither(body.fromJson[LoadPlan]).mapError(MalformedPlan(_))
    yield plan

  /** A refused report is logged and swallowed by the caller, not retried here: the next poll
    * carries the same cumulative counters, so one lost report costs the coordinator an interval
    * of freshness rather than any of the campaign's tallies.
    */
  override def report(report: DriverReport): Task[Unit] =
    for
      response <- send(Request.post(reportUrl, Body.fromString(report.toJson)))
      _ <- response.body.asString
        .flatMap(body => ZIO.fail(CoordinatorRefusedReport(response.status.code, body)))
        .when(response.status.code >= 300)
    yield ()

  private def send(request: Request): Task[Response] =
    http.request(request).timeoutFail(CoordinatorUnreachable)(requestTimeout)

case class InvalidCoordinatorUrl(reason: String) extends RuntimeException(reason)

case object CoordinatorUnreachable extends RuntimeException("the coordinator did not answer within the poll timeout")

case class MalformedPlan(reason: String) extends RuntimeException(s"the coordinator's plan did not decode: $reason")

case class CoordinatorRefusedPoll(status: Int, body: String)
  extends RuntimeException(s"the coordinator answered $status to GET /plan: $body")

case class CoordinatorRefusedReport(status: Int, body: String)
  extends RuntimeException(s"the coordinator answered $status to POST /drivers/report: $body")
