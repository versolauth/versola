package versola.loadgen.coordinator

import versola.loadgen.metrics.{CampaignHealth, ErrorTaxonomy}
import zio.json.JsonCodec
import zio.{Duration, IO, Ref, UIO, ZIO}

import java.time.Instant

/** One driver's own health, as `GET /status` and the report's verdict need it.
  *
  * A restatement of `DriverHealthSample` on the wire rather than a reuse of it, and for two
  * reasons: that type carries a `Map[String, Duration]` of per-scenario schedule lag, which is a
  * dashboard breakdown that the verdict has no use for and which would make this envelope's size
  * a function of the scenario count; and the verdict is stated against a *quantile* of schedule
  * lag (`scheduleLagP99`), which only the driver can compute from its own recorder.
  *
  * Every counter here is cumulative for the driver's lifetime, not per interval. Cumulative
  * survives a lost report: the campaign total is the sum of the latest reading from each driver,
  * so a report that never arrives costs the coordinator one interval's freshness rather than
  * that interval's errors.
  */
case class DriverVitals(
    busyUsers: Int,
    inflightRequests: Int,
    scheduleLagP99Micros: Option[Long],
    cpuRatio: Option[Double],
    refreshRejectedTotal: Long,
    storeFlushDroppedTotal: Long,
    latencyClampedTotal: Long,
) derives JsonCodec

/** What a driver POSTs to `/drivers/report` on every plan poll.
  *
  * This exists because the campaign's verdict needs two things that no other channel carries. The
  * latency histograms reach the coordinator through `vu_metric_snapshots` -- durable, and the
  * reason `GET /report/{campaign}` still works after a coordinator restart -- but the error
  * taxonomy and the driver-health readings are in no table, and `CampaignReport.assemble`
  * requires both. Neither is per-user state, so holding them here does not make the coordinator
  * stateful in the sense §12 forbids: a coordinator that dies loses the live fleet view and
  * rebuilds it from the next round of reports.
  *
  * @param arrivals
  *   arrivals *executed* per scenario, cumulative, which is what makes the achieved rate a
  *   measurement rather than a restatement of the plan. Differenced across two reports by
  *   [[DriverRegistry]].
  * @param epoch
  *   the shard map the driver is running. A driver still on an old epoch after its drain deadline
  *   has passed is the one failure mode of the rebalance protocol that matters, and it is visible
  *   only here.
  */
case class DriverReport(
    version: Int,
    campaign: String,
    driverId: String,
    atEpochMillis: Long,
    epoch: Long,
    shardIndex: Int,
    arrivals: Map[PlanScenario, Long],
    taxonomy: ErrorTaxonomy,
    vitals: DriverVitals,
) derives JsonCodec

object DriverReport:
  /** Bumped whenever this envelope's shape changes, and rejected rather than guessed at, for the
    * same reason `HistogramWire.version` is: a campaign spans days, so a rolling driver upgrade
    * mid-run is exactly when a silently misread report would be most expensive.
    */
  val version: Int = 1

/** The last two reports from one driver -- the minimum a rate needs. */
final case class DriverObservation(latest: DriverReport, previous: Option[DriverReport])

/** The fleet as one figure per question `GET /status` and the report ask. */
final case class FleetView(
    drivers: List[String],
    staleDrivers: List[String],
    achievedPerSecond: Map[PlanScenario, Double],
    taxonomy: ErrorTaxonomy,
    health: CampaignHealth,
)

/** The coordinator's view of its drivers: the latest report from each, and the one before it.
  *
  * Nothing is ever evicted. A campaign runs at most a couple of dozen drivers (design doc §6.5),
  * and a driver that has gone away still counts towards the campaign's error taxonomy -- the
  * steps it recorded happened. Forgetting it would shrink the error budget's numerator every time
  * a pod was replaced, which is the direction that mistake must not take.
  */
final class DriverRegistry private (
    campaign: String,
    staleAfter: Duration,
    observations: Ref[Map[String, DriverObservation]],
):

  /** @return `Left` for a report this coordinator must not fold in: a different campaign (two
    *         campaigns' figures merged into one verdict is unrecoverable once merged) or an
    *         envelope version this build does not read.
    */
  def accept(report: DriverReport): IO[String, Unit] =
    if report.version != DriverReport.version then
      ZIO.fail(s"unsupported driver report version ${report.version} (this build reads ${DriverReport.version})")
    else if report.campaign != campaign then
      ZIO.fail(s"report for campaign '${report.campaign}' sent to the '$campaign' coordinator")
    else
      observations.update: current =>
        current.get(report.driverId) match
          // Out of order, so dropped rather than recorded: a retry or a clock that stepped
          // backwards would otherwise become a negative interval, and a negative interval divides
          // the achieved rate by a negative number.
          case Some(existing) if report.atEpochMillis <= existing.latest.atEpochMillis => current
          case Some(existing) => current.updated(report.driverId, DriverObservation(report, Some(existing.latest)))
          case None => current.updated(report.driverId, DriverObservation(report, None))

  def view(now: Instant): UIO[FleetView] =
    observations.get.map: current =>
      val reports = current.values.toList
      FleetView(
        drivers = current.keys.toList.sorted,
        staleDrivers = reports.filter(observation => isStale(observation.latest, now)).map(_.latest.driverId).sorted,
        achievedPerSecond = achieved(reports),
        // Stale drivers are counted here even though their rate is not: their steps happened, and
        // their latency is already in the merge. Only the *rate* is a statement about now.
        taxonomy = ErrorTaxonomy.mergeAll(reports.map(_.latest.taxonomy)),
        health = health(reports.map(_.latest.vitals)),
      )

  private def isStale(report: DriverReport, now: Instant): Boolean =
    now.toEpochMilli - report.atEpochMillis > staleAfter.toMillis

  /** Cumulative arrivals differenced over the interval between two reports, summed across
    * drivers.
    *
    * A driver contributes nothing until it has been seen twice, and nothing when its counter went
    * backwards -- which means the pod restarted, so the delta describes two different processes.
    * Re-baselining on the next report is the same discipline `DriverHealthReporter` applies to
    * its own counters, and for the same reason: an invented increase is worse than a gap.
    */
  private def achieved(reports: List[DriverObservation]): Map[PlanScenario, Double] =
    reports.foldLeft(Map.empty[PlanScenario, Double]): (rates, observation) =>
      observation.previous match
        case None => rates
        case Some(previous) =>
          val seconds = (observation.latest.atEpochMillis - previous.atEpochMillis).toDouble / 1000.0
          if seconds <= 0.0 then rates
          else
            observation.latest.arrivals.foldLeft(rates): (accumulated, entry) =>
              val (scenario, count) = entry
              val delta = count - previous.arrivals.getOrElse(scenario, 0L)
              if delta < 0L then accumulated
              else accumulated.updatedWith(scenario)(existing => Some(existing.getOrElse(0.0) + delta / seconds))

  /** Counters add, readings take the worst of the fleet.
    *
    * `CampaignHealth`'s own doc fixes that asymmetry: one saturated driver distorts the latencies
    * of its own shard, and a mean CPU or a mean schedule lag averages exactly that away.
    */
  private def health(vitals: List[DriverVitals]): CampaignHealth =
    CampaignHealth(
      refreshRejectedTotal = vitals.map(_.refreshRejectedTotal).sum,
      flushDroppedTotal = vitals.map(_.storeFlushDroppedTotal).sum,
      maxDriverCpu = vitals.flatMap(_.cpuRatio).maxOption,
      scheduleLagP99 = vitals.flatMap(_.scheduleLagP99Micros).maxOption.map(micros => Duration.fromNanos(micros * 1000L)),
      latencyClampedTotal = vitals.map(_.latencyClampedTotal).sum,
    )

object DriverRegistry:
  def make(campaign: String, staleAfter: Duration): UIO[DriverRegistry] =
    Ref.make(Map.empty[String, DriverObservation]).map(DriverRegistry(campaign, staleAfter, _))
