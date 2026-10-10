package versola.loadgen.environment

import zio.*
import zio.json.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

/** One labelled series of a `query_range` answer: seconds-resolution instants and their values. */
case class MetricSeries(labels: Map[String, String], samples: Vector[(Long, Double)])

trait VictoriaMetricsClient:
  def range(expression: String, from: Instant, to: Instant, step: Duration): Task[List[MetricSeries]]

object VictoriaMetricsClient:

  private case class Answer(status: String, data: Option[Data], error: Option[String]) derives JsonDecoder
  private case class Data(result: List[Result]) derives JsonDecoder
  private case class Result(metric: Map[String, String], values: List[(Double, String)]) derives JsonDecoder

  /** The Prometheus HTTP API's `query_range` answer, decoded. Public for the spec. */
  def decode(body: String): Either[String, List[MetricSeries]] =
    body.fromJson[Answer].flatMap:
      case Answer("success", Some(data), _) =>
        Right(data.result.map: result =>
          MetricSeries(
            result.metric,
            result.values.toVector.flatMap: (ts, value) =>
              value.toDoubleOption.filterNot(_.isNaN).map(parsed => (ts.toLong, parsed)),
          ))
      case Answer(status, _, error) => Left(s"status '$status': ${error.getOrElse("no data")}")

  def http(baseUrl: String, timeout: Duration): VictoriaMetricsClient =
    val client = HttpClient.newHttpClient()
    val base = baseUrl.stripSuffix("/")
    (expression, from, to, step) =>
      ZIO
        .attemptBlocking:
          val query = List(
            "query" -> expression,
            "start" -> from.getEpochSecond.toString,
            "end" -> to.getEpochSecond.toString,
            "step" -> s"${math.max(1L, step.getSeconds)}s",
          ).map((key, value) => s"$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}").mkString("&")
          val request = HttpRequest
            .newBuilder(URI.create(s"$base/api/v1/query_range?$query"))
            .timeout(java.time.Duration.ofMillis(timeout.toMillis))
            .GET()
            .build()
          client.send(request, HttpResponse.BodyHandlers.ofString())
        .flatMap: response =>
          if response.statusCode() / 100 != 2 then
            ZIO.fail(RuntimeException(s"HTTP ${response.statusCode()} from VictoriaMetrics"))
          else ZIO.fromEither(decode(response.body())).mapError(RuntimeException(_))
