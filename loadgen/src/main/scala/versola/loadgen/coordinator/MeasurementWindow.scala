package versola.loadgen.coordinator

import versola.loadgen.config.CampaignPhaseConfig
import versola.loadgen.metrics.DriverHistogramReport

import java.time.Instant

/** Which of a campaign's latency snapshots count toward its quantiles (`measured` on
  * [[CampaignPhaseConfig]]).
  *
  * A snapshot is the interval that ended at its `capturedAt`, so it belongs to the phase that
  * instant falls in; one that straddles a boundary is judged by where it ended, which blurs the
  * edge by at most one snapshot interval (`SnapshotPublisher.interval`). The phases are laid out
  * from the campaign's start in the order configured, as `CampaignSchedule` lays them out, and an
  * unknown start -- a coordinator restarted mid-campaign, which lost it with the rest of its
  * memory -- excludes nothing: a report with the warm-up in it is wrong in a way that shows, one
  * that silently dropped intervals is wrong in a way that does not.
  */
object MeasurementWindow:

  /** The spans `(from, until]` of the run that are not measured, in order. */
  def excluded(phases: List[CampaignPhaseConfig], startedAt: Option[Instant]): List[(Instant, Instant)] =
    startedAt.fold(List.empty[(Instant, Instant)]): start =>
      phases
        .foldLeft((start, List.empty[(Instant, Instant)])):
          case ((from, spans), phase) =>
            val until = from.plusMillis(phase.duration.toMillis)
            (until, if phase.measured then spans else spans :+ (from, until))
        ._2

  /** The snapshots outside every excluded span. */
  def measured(
      reports: List[DriverHistogramReport],
      excluded: List[(Instant, Instant)],
  ): List[DriverHistogramReport] =
    if excluded.isEmpty then reports
    else
      reports.filterNot: report =>
        val at = Instant.ofEpochMilli(report.capturedAtEpochMillis)
        excluded.exists((from, until) => at.isAfter(from) && !at.isAfter(until))
