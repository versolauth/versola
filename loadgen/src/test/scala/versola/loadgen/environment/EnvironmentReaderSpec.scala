package versola.loadgen.environment

import versola.loadgen.config.EnvironmentConfig
import zio.*
import zio.test.*

import java.time.Instant

object EnvironmentReaderSpec extends ZIOSpecDefault:

  private val from = Instant.parse("2026-10-09T17:32:00Z")
  private val to = from.plusSeconds(90)
  private val config = EnvironmentConfig("http://vm", "versola")

  private def series(labels: (String, String)*)(values: Double*) =
    MetricSeries(labels.toMap, values.zipWithIndex.map((value, index) => (from.getEpochSecond + index * 30L, value)).toVector)

  /** Answers by a fragment of the expression, as a real VictoriaMetrics would by its data. */
  private def client(answers: (String, Task[List[MetricSeries]])*): VictoriaMetricsClient =
    (expression, _, _, _) => answers.collectFirst { case (key, answer) if expression.contains(key) => answer }.getOrElse(ZIO.succeed(Nil))

  private val pods = "versola-auth-6545c6896c-tgdrw"
  private val other = "versola-auth-6545c6896c-7kj7q"

  def spec = suite("EnvironmentReader")(
    test("names the app a pod belongs to, for deployments and stateful sets alike") {
      assertTrue(
        EnvironmentReader.appOf("versola-auth-6545c6896c-tgdrw") == "versola-auth",
        EnvironmentReader.appOf("loadgen-driver-3") == "loadgen-driver",
        EnvironmentReader.appOf("bare") == "bare",
      )
    },
    test("sums an app's pods at each instant and keeps the busiest pod's own peak") {
      val reader = VictoriaMetricsEnvironmentReader.make(
        config,
        client(
          "container_cpu_usage_seconds_total" -> ZIO.succeed(
            List(series("pod" -> pods)(0.1, 0.3, 0.2), series("pod" -> other)(0.1, 0.1, 0.1)),
          ),
          "container_memory_working_set_bytes" -> ZIO.succeed(List(series("pod" -> pods)(800.0, 810.0, 820.0))),
          "container_spec_memory_limit_bytes" -> ZIO.succeed(List(series("pod" -> pods)(1024.0, 1024.0, 1024.0))),
          "container_spec_cpu_quota" -> ZIO.succeed(List(series("pod" -> pods)(2.0, 2.0, 2.0))),
          "container_last_seen" -> ZIO.succeed(List(MetricSeries(Map("pod" -> pods, "image" -> "registry/auth:fapi2"), Vector.empty))),
        ),
      )
      for
        r <- reader
        stats <- r.read(from, to)
      yield
        val app = stats.apps.head
        assertTrue(
          stats.apps.size == 1,
          app.app == "versola-auth",
          app.pods == 2,
          app.images == List("registry/auth:fapi2"),
          app.cpuCores.map(_.peak) == Some(0.4),
          app.podPeakCpuCores == Some(0.3),
          app.cpuLimitCoresPerPod == Some(2.0),
          app.memoryBytes.map(_.peak) == Some(820.0),
          app.memoryLimitBytesPerPod == Some(1024.0),
          stats.unavailable.isEmpty,
        )
    },
    test("orders routes by their peak and keeps the pool states apart") {
      val reader = VictoriaMetricsEnvironmentReader.make(
        config,
        client(
          "http_server_requests_total" -> ZIO.succeed(
            List(series("route" -> "/token")(0.5, 0.6, 0.4), series("route" -> "/userinfo")(1.0, 2.0, 1.5)),
          ),
          "db_client_connection_count" -> ZIO.succeed(
            List(series("state" -> "used")(1.0, 3.0, 2.0), series("state" -> "idle")(9.0, 7.0, 8.0)),
          ),
        ),
      )
      for
        r <- reader
        stats <- r.read(from, to)
      yield assertTrue(
        stats.routes.map(_.route) == List("/userinfo", "/token"),
        stats.routes.head.requestsPerSecond.peak == 2.0,
        stats.pools.map(_.state).toSet == Set("used", "idle"),
      )
    },
    // A query that failed must not be told from a quiet system: the report names it.
    test("a failed query is named as unavailable and costs the section, not the report") {
      val reader = VictoriaMetricsEnvironmentReader.make(
        config,
        client(
          "container_memory_working_set_bytes" -> ZIO.fail(RuntimeException("timeout")),
          "container_cpu_usage_seconds_total" -> ZIO.succeed(List(series("pod" -> pods)(0.1, 0.1, 0.1))),
        ),
      )
      for
        r <- reader
        stats <- r.read(from, to)
      yield assertTrue(
        stats.unavailable == List("memory"),
        stats.apps.head.cpuCores.isDefined,
        stats.apps.head.memoryBytes.isEmpty,
      )
    },
    // The report endpoint is polled; an identical window answers from memory, a failed one retries.
    test("an identical window is answered from memory, and an incomplete answer is not kept") {
      for
        calls <- Ref.make(0)
        failing <- Ref.make(true)
        counting = new VictoriaMetricsClient:
          override def range(expression: String, from: Instant, to: Instant, step: Duration) =
            calls.update(_ + 1) *> failing.get.flatMap: fails =>
              if fails && expression.contains("container_memory_working_set_bytes") then ZIO.fail(RuntimeException("down"))
              else ZIO.succeed(Nil)
        reader <- VictoriaMetricsEnvironmentReader.make(config, counting)
        first <- reader.read(from, to)
        afterFailure <- calls.get
        _ <- failing.set(false)
        second <- reader.read(from, to)
        afterRetry <- calls.get
        _ <- reader.read(from, to)
        afterCached <- calls.get
        _ <- reader.read(from, to.plusSeconds(30))
        afterMoved <- calls.get
      yield assertTrue(
        first.unavailable == List("memory"),
        second.unavailable.isEmpty,
        afterRetry == afterFailure * 2,
        afterCached == afterRetry,
        afterMoved == afterRetry + afterFailure,
      )
    },
    test("limits are summed over a pod's containers that have one, as usage is summed over all of them") {
      for
        seen <- Ref.make(List.empty[String])
        recording = new VictoriaMetricsClient:
          override def range(expression: String, from: Instant, to: Instant, step: Duration) =
            seen.update(expression :: _).as(Nil)
        reader <- VictoriaMetricsEnvironmentReader.make(config, recording)
        _ <- reader.read(from, to)
        expressions <- seen.get
      yield assertTrue(
        expressions.exists(_.startsWith("sum by (pod) ((container_spec_cpu_quota")),
        expressions.exists(_.startsWith("sum by (pod) (container_spec_memory_limit_bytes")),
        !expressions.exists(_.contains("max by (pod) (container_spec")),
      )
    },
    test("the sample statistic ignores NaN and takes the nearest-rank p95") {
      val stat = SampleStat.of((1 to 20).map(_.toDouble) :+ Double.NaN).get
      assertTrue(stat.peak == 20.0, stat.p95 == 19.0, stat.mean == 10.5, SampleStat.of(Seq(Double.NaN)).isEmpty)
    },
    test("decodes a query_range answer, dropping samples that are not numbers") {
      val body =
        """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"pod":"a"},"values":[[1760031120,"0.5"],[1760031150,"NaN"],[1760031180,"1"]]}]}}"""
      val decoded = VictoriaMetricsClient.decode(body)
      assertTrue(
        decoded.map(_.head.labels) == Right(Map("pod" -> "a")),
        decoded.map(_.head.samples.map(_._2)) == Right(Vector(0.5, 1.0)),
        VictoriaMetricsClient.decode("""{"status":"error","error":"bad query"}""").isLeft,
      )
    },
  )
