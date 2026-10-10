package versola.loadgen.environment

import versola.loadgen.config.EnvironmentConfig
import zio.*

import java.time.Instant

/** Reads the SUT's resource use over the report window out of VictoriaMetrics, where cAdvisor,
  * the JVM and the HTTP/pool instrumentation of every pod already put it. Only `container_*`
  * series and the SUT's own `http_server_*`/`db_client_*` families are used, so the section
  * needs nothing the stack does not already scrape.
  */
trait EnvironmentReader:
  def read(from: Instant, to: Instant): UIO[EnvironmentStats]

/** Remembers the last complete answer: a report endpoint is polled, and the same window always
  * gives the same figures (samples are never added inside a window that has passed), so only a
  * window that moved needs the monitoring stack again. An answer with a failed query is not kept,
  * so the next request retries what failed.
  */
final class VictoriaMetricsEnvironmentReader private (
    config: EnvironmentConfig,
    client: VictoriaMetricsClient,
    last: Ref[Option[((Instant, Instant), EnvironmentStats)]],
) extends EnvironmentReader:
  import EnvironmentReader.*

  private val ns = config.namespace
  private val rate = s"${math.max(1L, config.rateWindow.getSeconds)}s"
  private val containers = s"""namespace="$ns",container!="",container!="POD""""

  override def read(from: Instant, to: Instant): UIO[EnvironmentStats] =
    last.get.flatMap:
      case Some((window, stats)) if window == (from, to) => ZIO.succeed(stats)
      case _ =>
        collect(from, to).tap: stats =>
          last.set(Some(((from, to), stats))).when(stats.unavailable.isEmpty)

  private def collect(from: Instant, to: Instant): UIO[EnvironmentStats] =
    def query(name: String, expression: String): UIO[(String, Option[List[MetricSeries]])] =
      client
        .range(expression, from, to, config.step)
        .timeoutFail(RuntimeException(s"no answer in ${config.timeout.render}"))(config.timeout)
        .map(series => name -> Some(series))
        .catchAllCause: cause =>
          ZIO.logWarningCause(s"Environment query '$name' failed; the report will carry no such figure", cause)
            .as(name -> None)

    ZIO
      .foreachPar(
        List(
          "cpu" -> s"sum by (pod) (rate(container_cpu_usage_seconds_total{$containers}[$rate]))",
          // Summed over the pod's containers that have a limit, as usage is summed over all of
          // them, so a sidecar does not make the limit read as the largest container's alone.
          // A container without a limit reports a quota of 0 and contributes nothing.
          "cpu-limit" -> s"sum by (pod) ((container_spec_cpu_quota{$containers} > 0) / container_spec_cpu_period{$containers})",
          "throttled" -> (
            s"sum by (pod) (rate(container_cpu_cfs_throttled_periods_total{$containers}[$rate])) / " +
              s"sum by (pod) (rate(container_cpu_cfs_periods_total{$containers}[$rate]))"
          ),
          "memory" -> s"sum by (pod) (container_memory_working_set_bytes{$containers})",
          "memory-limit" -> s"sum by (pod) (container_spec_memory_limit_bytes{$containers} > 0)",
          "network-receive" -> s"""sum by (pod) (rate(container_network_receive_bytes_total{namespace="$ns"}[$rate]))""",
          "network-transmit" -> s"""sum by (pod) (rate(container_network_transmit_bytes_total{namespace="$ns"}[$rate]))""",
          "images" -> s"""max by (pod, image) (container_last_seen{$containers})""",
          "routes" -> s"""sum by (route) (rate(http_server_requests_total{namespace="$ns"}[$rate]))""",
          "pools" -> s"""sum by (state) (db_client_connection_count{namespace="$ns"})""",
        ),
      )(query.tupled)
      .map: results =>
        val byName = results.toMap
        def series(name: String): List[MetricSeries] = byName.getOrElse(name, None).getOrElse(Nil)
        val apps = (series("cpu").flatMap(_.labels.get("pod")) ++ series("memory").flatMap(_.labels.get("pod")))
          .distinct
          .groupBy(appOf)
          .toList
          .sortBy(_._1)
          .map: (app, pods) =>
            def mine(name: String) = series(name).filter(_.labels.get("pod").exists(pods.contains))
            def total(name: String) = SampleStat.of(sumPerInstant(mine(name)))
            def podPeak(name: String) = mine(name).flatMap(_.samples.map(_._2)).filterNot(_.isNaN).maxOption
            def limit(name: String) = mine(name).flatMap(_.samples.map(_._2)).filter(_ > 0).maxOption
            AppResources(
              app = app,
              pods = pods.size,
              images = mine("images").flatMap(_.labels.get("image")).distinct.sorted,
              cpuCores = total("cpu"),
              podPeakCpuCores = podPeak("cpu"),
              cpuLimitCoresPerPod = limit("cpu-limit"),
              cpuThrottledRatio = SampleStat.of(meanPerInstant(mine("throttled"))),
              memoryBytes = total("memory"),
              podPeakMemoryBytes = podPeak("memory"),
              memoryLimitBytesPerPod = limit("memory-limit"),
              networkReceiveBytesPerSecond = total("network-receive"),
              networkTransmitBytesPerSecond = total("network-transmit"),
            )
        EnvironmentStats(
          fromEpochMillis = from.toEpochMilli,
          toEpochMillis = to.toEpochMilli,
          stepSeconds = config.step.getSeconds,
          namespace = ns,
          apps = apps,
          routes = series("routes").flatMap: one =>
            for
              route <- one.labels.get("route")
              stat <- SampleStat.of(one.samples.map(_._2))
            yield RouteRate(route, stat)
          .sortBy(-_.requestsPerSecond.peak),
          pools = series("pools").flatMap: one =>
            for
              state <- one.labels.get("state")
              stat <- SampleStat.of(one.samples.map(_._2))
            yield PoolConnections(state, stat),
          unavailable = results.collect { case (name, None) => name }.sorted,
        )

object VictoriaMetricsEnvironmentReader:
  def make(config: EnvironmentConfig, client: VictoriaMetricsClient): UIO[VictoriaMetricsEnvironmentReader] =
    Ref.make(Option.empty[((Instant, Instant), EnvironmentStats)]).map(VictoriaMetricsEnvironmentReader(config, client, _))

object EnvironmentReader:

  /** `versola-auth-6545c6896c-tgdrw` and `loadgen-driver-3` both name the app they belong to. */
  private val podSuffix = """^(.*?)(-[a-f0-9]{8,10}-[a-z0-9]{5}|-\d+)$""".r

  def appOf(pod: String): String = pod match
    case podSuffix(app, _) => app
    case other => other

  /** The sum over series at each instant, instants some series lack counted as zero. */
  def sumPerInstant(series: List[MetricSeries]): Seq[Double] =
    series.flatMap(_.samples).groupMapReduce(_._1)(_._2)(_ + _).toSeq.sortBy(_._1).map(_._2)

  def meanPerInstant(series: List[MetricSeries]): Seq[Double] =
    series.flatMap(_.samples).filterNot(_._2.isNaN).groupMap(_._1)(_._2).toSeq.sortBy(_._1).map: (_, values) =>
      values.sum / values.size
