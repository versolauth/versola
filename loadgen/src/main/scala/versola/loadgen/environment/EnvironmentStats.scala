package versola.loadgen.environment

import zio.json.JsonCodec

/** One measurement's distribution over the report window, sampled at the VictoriaMetrics query
  * step. A `p95` of sixty-odd samples is a description of the run, not a statistical claim; the
  * peak is the number a capacity decision leans on.
  */
case class SampleStat(peak: Double, mean: Double, p95: Double) derives JsonCodec

object SampleStat:
  def of(samples: Seq[Double]): Option[SampleStat] =
    val finite = samples.filter(sample => !sample.isNaN && !sample.isInfinite)
    Option.when(finite.nonEmpty):
      val sorted = finite.sorted
      SampleStat(
        peak = sorted.last,
        mean = sorted.sum / sorted.size,
        p95 = sorted(math.min(sorted.size - 1, math.ceil(sorted.size * 0.95).toInt - 1)),
      )

/** What one SUT application (every pod of one Deployment or StatefulSet) used over the window.
  *
  * `cpuCores`, `memoryBytes` and the network rates are sums over the app's pods at each instant,
  * so they describe the app; `podPeakCpuCores`/`podPeakMemoryBytes` are the single busiest pod,
  * which is what a limit has to cover. Limits are per pod, and absent when the container has none.
  */
case class AppResources(
    app: String,
    pods: Int,
    images: List[String],
    cpuCores: Option[SampleStat],
    podPeakCpuCores: Option[Double],
    cpuLimitCoresPerPod: Option[Double],
    cpuThrottledRatio: Option[SampleStat],
    memoryBytes: Option[SampleStat],
    podPeakMemoryBytes: Option[Double],
    memoryLimitBytesPerPod: Option[Double],
    networkReceiveBytesPerSecond: Option[SampleStat],
    networkTransmitBytesPerSecond: Option[SampleStat],
) derives JsonCodec

/** Requests per second one route served, summed over every pod that served it. */
case class RouteRate(route: String, requestsPerSecond: SampleStat) derives JsonCodec

/** Connections of the apps' pools by state (`used`, `idle`), summed over pods. */
case class PoolConnections(state: String, connections: SampleStat) derives JsonCodec

/** What the monitoring stack observed of the SUT between `fromEpochMillis` and `toEpochMillis`
  * (the span the latency quantiles cover). A section whose query returned nothing is empty or
  * absent rather than zero: no series is not the same as an idle one.
  */
case class EnvironmentStats(
    fromEpochMillis: Long,
    toEpochMillis: Long,
    stepSeconds: Long,
    namespace: String,
    apps: List[AppResources],
    routes: List[RouteRate],
    pools: List[PoolConnections],
    /** Queries that failed or timed out, by name, so a missing section can be told from an idle one. */
    unavailable: List[String],
) derives JsonCodec
