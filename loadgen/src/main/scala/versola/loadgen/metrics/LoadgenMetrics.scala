package versola.loadgen.metrics

import versola.loadgen.protocol.RefreshRejection
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics.{Metric, MetricLabel}
import zio.{Chunk, Duration, UIO}

/** Which slice of the population a `loadgen_population` reading describes. `Broken` is the count
  * of virtual users the driver has retired -- a lost refresh rotation, a dead cookie session --
  * and it is the number that tells you whether a falling arrival rate is the SUT slowing down or
  * the emulator eating its own population.
  */
enum PopulationState(val label: String):
  case Planned extends PopulationState("planned")
  case Registered extends PopulationState("registered")
  case Broken extends PopulationState("broken")

/** The live Prometheus surface of §11, scraped off `VersolaApp`'s diagnostics port.
  *
  * These metrics are for dashboards and alerts, and nothing else. Their quantiles are computed
  * from fixed bucket boundaries per pod, and neither buckets nor quantiles can be merged across
  * pods into a correct campaign-wide quantile -- averaging pod p99s is not a p99 of anything. The
  * defensible number the report is judged on comes from the HdrHistograms of [[LatencyRecorder]],
  * merged losslessly by the coordinator. Both are recorded from the same measurement so they
  * cannot disagree about what happened, only about how precisely it is summarised.
  *
  * Every label value here is drawn from configuration or from a closed enum. §11's cardinality
  * budget is not a guideline: at ~5,300 rps across a fleet, one user id in a label value is a
  * dead Prometheus.
  */
object LoadgenMetrics:

  /** Exponential boundaries rather than the linear-ish set `Observability` uses for the SUT's own
    * HTTP metrics: the campaign spans 1 ms cache hits and multi-second Argon2 queueing, and a
    * fixed-width set cannot resolve both ends. 1 ms doubling to ~65 s, 17 buckets.
    */
  val latencyBoundaries: Boundaries = Boundaries.exponential(0.001, 2.0, 17)

  private val stepDuration = Metric.histogram("loadgen_step_duration_seconds", latencyBoundaries)
  private val flowDuration = Metric.histogram("loadgen_flow_duration_seconds", latencyBoundaries)
  private val arrivalsTotal = Metric.counter("loadgen_arrivals_total")
  private val outcomesTotal = Metric.counter("loadgen_outcomes_total")
  private val scheduleLagSeconds = Metric.gauge("loadgen_schedule_lag_seconds")
  private val busyUsersGauge = Metric.gauge("loadgen_busy_users")
  private val inflightRequestsGauge = Metric.gauge("loadgen_inflight_requests")
  private val refreshRejectedTotal = Metric.counter("loadgen_refresh_rejected_total")
  private val storeFlushDroppedTotal = Metric.counter("loadgen_store_flush_dropped_total")
  private val populationGauge = Metric.gauge("loadgen_population")

  /** Not in §11's table, but named by the definition of done ("driver CPU < 40%"). A fraction in
    * `[0, 1]` of the pod's own CPU allocation -- see [[ProcessCpu]] -- so the threshold can be
    * read off the graph without knowing the node's core count. `_ratio` rather than
    * `_utilisation` because that is Prometheus's suffix for a unitless `[0, 1]` gauge.
    */
  private val driverCpuGauge = Metric.gauge("loadgen_driver_cpu_ratio")

  /** Recorded latencies above [[LatencyRecorder.highestTrackableMicros]] are clamped before they
    * reach the HdrHistogram. That is invisible in the quantiles, so it is counted here: any
    * non-zero value means the report's tail is a floor, not a measurement.
    */
  private val latencyClampedTotal = Metric.counter("loadgen_latency_clamped_total")

  def arrival(scenario: String): UIO[Unit] =
    arrivalsTotal.tagged(labels("scenario" -> scenario)).increment

  /** Records one completed step: its duration and its taxonomy bucket.
    *
    * Both in one call deliberately. The two are the same event, and a step that is timed but not
    * counted (or the reverse) leaves the report's rate and its error budget describing different
    * populations -- a discrepancy that is very hard to spot after the fact and impossible to
    * repair.
    */
  def stepCompleted(scenario: String, step: String, outcome: StepOutcome, latency: IntendedLatency): UIO[Unit] =
    stepDuration
      .tagged(labels("scenario" -> scenario, "step" -> step, "outcome" -> outcome.label))
      .update(latency.seconds) *>
      outcomesTotal.tagged(labels("scenario" -> scenario, "outcome" -> outcome.label)).increment

  /** Records one completed flow (login, refresh, step-up, ...). Deliberately does not touch
    * `loadgen_outcomes_total`: the taxonomy counts steps, and counting flows there too would
    * inflate the denominator of the error budget with the steps they are made of.
    */
  def flowCompleted(flow: String, outcome: StepOutcome, latency: IntendedLatency): UIO[Unit] =
    flowDuration.tagged(labels("flow" -> flow, "outcome" -> outcome.label)).update(latency.seconds)

  def scheduleLag(scenario: String, lag: Duration): UIO[Unit] =
    scheduleLagSeconds.tagged(labels("scenario" -> scenario)).set(lag.toNanos.toDouble / 1e9)

  def busyUsers(count: Int): UIO[Unit] =
    busyUsersGauge.set(count.toDouble)

  def inflightRequests(count: Int): UIO[Unit] =
    inflightRequestsGauge.set(count.toDouble)

  def refreshRejected(rejection: RefreshRejection): UIO[Unit] =
    refreshRejectedTotal.tagged(labels("reason" -> RefreshRejectionLabel.of(rejection))).increment

  /** Adds newly dropped write-behind rows. The store (track C) holds a cumulative figure; this
    * takes the increment, because a Prometheus counter that is `set` rather than added to loses
    * every increase that happened between two scrapes.
    */
  def storeFlushDropped(added: Long): UIO[Unit] =
    if added <= 0L then zio.ZIO.unit else storeFlushDroppedTotal.incrementBy(added)

  def population(state: PopulationState, count: Long): UIO[Unit] =
    populationGauge.tagged(labels("state" -> state.label)).set(count.toDouble)

  def driverCpu(utilisation: Double): UIO[Unit] =
    driverCpuGauge.set(utilisation)

  def latencyClamped: UIO[Unit] =
    latencyClampedTotal.increment

  private def labels(pairs: (String, String)*): Set[MetricLabel] =
    Chunk.fromIterable(pairs).map { case (name, value) => MetricLabel(name, value) }.toSet
