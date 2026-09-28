package versola.loadgen.sut

import versola.loadgen.store.{SutProcessSnapshotRow, SutStatPhase}
import zio.json.JsonCodec

/** What one SUT service's process did over one campaign: the difference between the two
  * `/metrics` readings bracketing the run.
  *
  * @param counters
  *   the difference, or `None` when the pair may not be differenced -- see [[processRestarted]].
  * @param processRestarted
  *   the service restarted between the two captures, so its counters count from the restart
  *   rather than from the campaign's start. Detected by `process_start_time_seconds` differing
  *   across the pair, and also asserted when either capture omitted it, because an unprovable
  *   restart and a proven one have the same consequence for a subtraction. The gauges survive
  *   it, as they do in [[SutStatsDelta]]: they were never cumulative.
  *
  *   Worth stating rather than silently voiding: a restart mid-campaign is not only a reason the
  *   CPU figure is missing, it is a fact about the run. A service that was OOM-killed at hour
  *   six did not serve the campaign the rest of the report describes.
  * @param before
  *   the gauges at the start against [[after]] at the end. `heapUsedBytes` on both is what says
  *   whether the heap settled or was still climbing when the run ended -- the question `Xms ==
  *   Xmx` was set to make answerable.
  */
case class SutProcessStatsDelta(
    service: String,
    beforeEpochMillis: Long,
    afterEpochMillis: Long,
    processRestarted: Boolean,
    counters: Option[SutProcessCounters],
    before: SutProcessGauges,
    after: SutProcessGauges,
) derives JsonCodec:

  /** Wall-clock seconds the two captures were apart, which is the denominator of the utilisation
    * below and the sanity check on it: a delta over a window this does not state cannot be told
    * from one over a window ten times longer.
    */
  def elapsedSeconds: Double = (afterEpochMillis - beforeEpochMillis).toDouble / 1000.0

  /** Mean CPU cores busy over the run -- `cpuSeconds / elapsedSeconds`, so 0.8 means the process
    * kept about eight tenths of one core busy throughout.
    *
    * Deliberately in cores rather than as a percentage of a limit: the coordinator scrapes the
    * process, and the process does not know what cgroup quota it was given (nothing in the
    * exposition states it). Dividing by the limit is the reader's job, and doing it here would
    * mean inventing the denominator.
    */
  def meanCoresBusy: Option[Double] =
    counters.map(_.cpuSeconds).filter(_ => elapsedSeconds > 0).map(_ / elapsedSeconds)

object SutProcessStatsDelta:

  /** Every service that has both of its boundaries recorded, in service order. One boundary
    * alone is left out for [[SutStatsDelta.from]]'s reason: a single cumulative reading is a
    * statement about the process's whole life, not about this campaign.
    */
  def from(rows: Iterable[SutProcessSnapshotRow]): List[SutProcessStatsDelta] =
    rows
      .groupBy(_.service)
      .toList
      .sortBy((service, _) => service)
      .flatMap: (_, ofService) =>
        for
          before <- ofService.find(_.phase == SutStatPhase.Before)
          after <- ofService.find(_.phase == SutStatPhase.After)
        yield between(before, after)

  def between(before: SutProcessSnapshotRow, after: SutProcessSnapshotRow): SutProcessStatsDelta =
    val restarted = (before.startedAtEpochSeconds, after.startedAtEpochSeconds) match
      case (Some(started), Some(stillStarted)) => started != stillStarted
      // Unprovable rather than proven, and treated as a restart: a CPU delta that silently spans
      // one is the failure this check exists to prevent, and the cost of being wrong the other
      // way is one absent section.
      case _ => true
    SutProcessStatsDelta(
      service = after.service,
      beforeEpochMillis = before.capturedAt.toEpochMilli,
      afterEpochMillis = after.capturedAt.toEpochMilli,
      processRestarted = restarted,
      counters = Option.unless(restarted)(
        SutProcessCounters(
          cpuSeconds = after.statistics.counters.cpuSeconds - before.statistics.counters.cpuSeconds,
          gcSeconds = after.statistics.counters.gcSeconds - before.statistics.counters.gcSeconds,
          gcCollections = after.statistics.counters.gcCollections - before.statistics.counters.gcCollections,
        ),
      ),
      before = before.statistics.gauges,
      after = after.statistics.gauges,
    )
