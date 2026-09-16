package versola.loadgen.driver

import org.HdrHistogram.Recorder
import versola.loadgen.metrics.LatencyRecorder
import versola.loadgen.scheduler.ScheduleLag
import zio.*

/** The p99 of this driver's schedule lag over the interval just reported.
  *
  * [[ScheduleLag]] holds the head of the dispatch queue, which is a gauge: it answers "how late
  * is the driver right now" and is what `loadgen_schedule_lag_seconds` publishes. The campaign's
  * verdict is stated against a *quantile* instead (`CampaignHealth.scheduleLagP99`), for the
  * reason every other criterion is: a driver that is on time except for one second in every
  * sixty is not a trustworthy instrument, and the gauge that happens to be scraped in between
  * says it is.
  *
  * Sampled rather than recorded per arrival, because the quantity is a property of the driver
  * over time and not of any one arrival -- and because the dispatcher fiber is the hot path the
  * whole measurement rests on.
  *
  * Reset on every read, so each report carries the interval it describes. A cumulative p99 would
  * keep reporting the worst minute of the campaign for the rest of it, which is the same mistake
  * as never resetting the latency recorders, and here it would be one an operator acts on.
  */
final class ScheduleLagQuantile private (recorder: Recorder):

  def sample(lag: Duration): UIO[Unit] =
    ZIO.succeed:
      val micros = math.max(LatencyRecorder.lowestDiscernibleMicros, lag.toNanos / 1000L)
      recorder.recordValue(math.min(micros, LatencyRecorder.highestTrackableMicros))

  /** `None` when nothing was sampled in the interval, which is a driver that was idle rather than
    * one that was on time -- and `DriverVitals` carries the distinction for the same reason
    * `cpuRatio` does.
    */
  def intervalP99Micros: UIO[Option[Long]] =
    ZIO.succeed:
      val interval = recorder.getIntervalHistogram
      if interval.getTotalCount <= 0L then None else Some(interval.getValueAtPercentile(99.0))

  /** Samples on a timer, forked into the caller's scope. */
  def run(lag: ScheduleLag, interval: Duration): ZIO[Scope, Nothing, Unit] =
    lag.current.flatMap(sample).repeat(Schedule.spaced(interval)).forkScoped.unit

object ScheduleLagQuantile:

  /** How often the lag is sampled. A second against a 10 s report interval gives the quantile ten
    * samples to be a quantile of -- coarse, but the reading it feeds is a threshold check, not a
    * distribution anybody plots.
    */
  val sampleInterval: Duration = 1.second

  val make: UIO[ScheduleLagQuantile] =
    ZIO.succeed:
      ScheduleLagQuantile(
        Recorder(
          LatencyRecorder.lowestDiscernibleMicros,
          LatencyRecorder.highestTrackableMicros,
          LatencyRecorder.significantDigits,
        ),
      )
