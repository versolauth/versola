package versola.loadgen.metrics

import org.HdrHistogram.{Histogram, Recorder}
import zio.{Chunk, Ref, UIO, ULayer, ZIO, ZLayer}

/** One [[MeasurementId]]'s worth of recorded latencies, in microseconds. */
case class HistogramSample(id: MeasurementId, histogram: Histogram)

/** The driver's HdrHistogram sink: the source of every quantile the campaign's verdict rests on.
  *
  * §11's range and precision, 1 µs to 60 s at 3 significant digits, is what makes the merge
  * lossless -- two drivers' histograms have identical bucket geometry, so
  * `AbstractHistogram.add` is exact addition of counts and the merged p99 is the p99 of the
  * union of the samples. That property is the reason the report does not use the Prometheus
  * histograms, whose per-pod bucket sums cannot be combined into a campaign-wide quantile.
  */
trait LatencyRecorder:
  def record(id: MeasurementId, latency: IntendedLatency): UIO[Unit]

  /** Takes and resets the interval for every measurement recorded so far.
    *
    * Resetting matters for the handoff: §11 has each driver writing a snapshot every 60 s, and
    * the coordinator summing them. Consecutive snapshots therefore have to *partition* the
    * recorded values -- a cumulative snapshot would be counted once per interval it appears in,
    * inflating the campaign's sample count by the number of snapshots and dragging the merged
    * quantiles toward whatever the early intervals looked like.
    */
  def snapshot: UIO[Chunk[HistogramSample]]

  /** How many samples exceeded [[LatencyRecorder.highestTrackableMicros]] and were recorded at it,
    * cumulative for this process.
    *
    * `loadgen_latency_clamped_total` publishes the same figure, but the campaign's verdict has to
    * read it too (`CampaignHealth.latencyClampedTotal`): a clamped sample is a tail the report is
    * understating, and a report that understates its own tail without saying so is the one
    * outcome the health block exists to prevent. Prometheus counters cannot be read back in
    * process, so the recorder keeps its own.
    */
  def clampedTotal: UIO[Long]

object LatencyRecorder:
  val lowestDiscernibleMicros: Long = 1L
  val highestTrackableMicros: Long = 60L * 1000L * 1000L
  val significantDigits: Int = 3

  def emptyHistogram: Histogram =
    Histogram(lowestDiscernibleMicros, highestTrackableMicros, significantDigits)

  val make: UIO[LatencyRecorder] =
    for
      recorders <- Ref.Synchronized.make(Map.empty[MeasurementId, Recorder])
      clamped <- Ref.make(0L)
    yield Live(recorders, clamped)

  val layer: ULayer[LatencyRecorder] =
    ZLayer.fromZIO(make)

  /** `Recorder` rather than a bare `Histogram` guarded by a lock: it is HdrHistogram's own
    * multi-writer/single-reader structure, so the thousands of fibers recording per second never
    * contend with each other, only with the once-a-minute snapshot.
    */
  private final class Live(recorders: Ref.Synchronized[Map[MeasurementId, Recorder]], clamped: Ref[Long])
    extends LatencyRecorder:

    override def record(id: MeasurementId, latency: IntendedLatency): UIO[Unit] =
      val micros = latency.micros
      val bounded = math.max(lowestDiscernibleMicros, math.min(micros, highestTrackableMicros))
      for
        recorder <- recorderFor(id)
        _ <- ZIO.succeed(recorder.recordValue(bounded))
        _ <- (clamped.update(_ + 1L) *> LoadgenMetrics.latencyClamped).when(micros > highestTrackableMicros)
      yield ()

    override def clampedTotal: UIO[Long] = clamped.get

    override def snapshot: UIO[Chunk[HistogramSample]] =
      recorders.get.flatMap: current =>
        ZIO.succeed:
          Chunk.fromIterable(current).map { case (id, recorder) =>
            HistogramSample(id, recorder.getIntervalHistogram)
          }

    private def recorderFor(id: MeasurementId): UIO[Recorder] =
      recorders.get.flatMap:
        case current if current.contains(id) => ZIO.succeed(current(id))
        case _ =>
          recorders.modifyZIO: current =>
            ZIO.succeed:
              current.get(id) match
                case Some(existing) => (existing, current)
                case None =>
                  val created = Recorder(lowestDiscernibleMicros, highestTrackableMicros, significantDigits)
                  (created, current.updated(id, created))
