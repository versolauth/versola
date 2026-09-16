package versola.loadgen.driver

import versola.loadgen.coordinator.{DriverReport, DriverVitals, PlanScenario}
import versola.loadgen.metrics.{FailedOutcome, LatencyRecorder, ProcessCpu, TokenObserver}
import versola.loadgen.model.Platform
import versola.loadgen.protocol.InflightRequests
import versola.loadgen.scenario.{ArrivalTally, BusyUsers, ScenarioRecorder}
import versola.loadgen.store.WriteBehindBuffer
import zio.*

/** Assembles and posts `POST /drivers/report` (§12).
  *
  * Two of the five criteria the campaign is judged on reach the coordinator only here: the
  * latency histograms travel through `vu_metric_snapshots`, but the error taxonomy and the
  * driver-health readings are in no table, and `CampaignReport.assemble` requires both.
  *
  * Everything counted is cumulative for this process's lifetime, never per interval, because the
  * coordinator differences two reports to get a rate and carries the tallies forward across a
  * restart (`DriverRegistry.restarted`). A per-interval envelope would lose that interval whole
  * every time a report failed to arrive.
  */
final class DriverReporter(
    campaign: String,
    driverId: String,
    shardIndex: Int,
    client: PlanClient,
    tally: ArrivalTally,
    recorder: ScenarioRecorder,
    latencies: LatencyRecorder,
    busy: BusyUsers,
    buffer: WriteBehindBuffer,
    lagQuantile: ScheduleLagQuantile,
    cpu: ProcessCpu,
):

  def assemble(epoch: Long): UIO[DriverReport] =
    for
      now <- Clock.instant
      arrivals <- tally.cumulative
      taxonomy <- recorder.taxonomy
      busyUsers <- busy.size
      inflight <- InflightRequests.current
      dropped <- buffer.droppedTotal
      clamped <- latencies.clampedTotal
      lagP99 <- lagQuantile.intervalP99Micros
      cpuRatio <- cpu.ratio
      observed <- TokenObserver.current
    yield DriverReport(
      version = DriverReport.version,
      campaign = campaign,
      driverId = driverId,
      atEpochMillis = now.toEpochMilli,
      epoch = epoch,
      shardIndex = shardIndex,
      arrivals = DriverReporter.arrivalsOf(arrivals),
      taxonomy = taxonomy,
      vitals = DriverVitals(
        busyUsers = busyUsers,
        inflightRequests = inflight,
        scheduleLagP99Micros = lagP99,
        cpuRatio = cpuRatio,
        // Already in the taxonomy, and restated here because `CampaignHealth` is what the verdict
        // reads and it must not have to know the taxonomy's shape to find the one outcome §7.4
        // fixes at ~0.
        refreshRejectedTotal = taxonomy.failedCount(FailedOutcome.RefreshRejected),
        storeFlushDroppedTotal = dropped,
        latencyClampedTotal = clamped,
      ),
      observed = observed,
    )

  /** Posts one report, and treats a refusal as a lost interval rather than as a driver fault.
    *
    * §12 is explicit that a coordinator that dies costs the fleet nothing it needs to keep
    * generating load. A driver that stopped, or that failed its own run, because the control
    * plane was briefly unreachable would make the coordinator the single point of failure the
    * whole plan-polling design exists to avoid.
    */
  def post(epoch: Long): UIO[Unit] =
    assemble(epoch)
      .flatMap(client.report)
      .catchAllCause(ZIO.logWarningCause("Driver report was not accepted; the next one carries the same totals", _))

object DriverReporter:

  /** [[Platform]] into the coordinator's arrival streams.
    *
    * `PlanScenario.Registration` is deliberately absent: the driver schedules session arrivals
    * only, and reporting a zero for a stream it never runs would read as a registration ramp that
    * is failing rather than one nothing is driving. See [[Driver]] for why that stream has no
    * driver-side implementation yet.
    */
  def arrivalsOf(byPlatform: Map[Platform, Long]): Map[PlanScenario, Long] =
    byPlatform.map:
      case (Platform.Mobile, count) => PlanScenario.MobileSession -> count
      case (Platform.Web, count) => PlanScenario.WebSession -> count
