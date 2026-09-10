package versola.loadgen.metrics

import org.HdrHistogram.Histogram
import zio.json.JsonCodec
import zio.{Chunk, Duration}

import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.zip.DataFormatException

/** One measurement's histogram as it travels from a driver to the coordinator.
  *
  * `encoding` is HdrHistogram's own compressed V2 payload, base64url'd so it survives JSON and a
  * `text` column unchanged. Re-summarising into quantiles before the handoff was the alternative
  * and is wrong for the same reason the Prometheus histograms are not the report: quantiles do
  * not add. The bucket counts do, so the buckets are what gets shipped.
  *
  * `unit` and `count` are redundant with the payload on purpose. They are the cheap end of a
  * decode check -- a truncated or re-encoded payload is otherwise perfectly capable of decoding
  * into a plausible-looking histogram of the wrong size, and a campaign verdict is not a good
  * place to discover that.
  */
case class EncodedHistogram(
    id: MeasurementId,
    unit: String,
    count: Long,
    encoding: String,
) derives JsonCodec

/** What one driver hands over per snapshot interval (§11: every 60 s), and what `GET
  * /report/{campaign}` consumes N of (§12).
  *
  * `driverId` is on the envelope rather than in the histogram labels because it must not become a
  * dimension of the report: the campaign's p99 is over the whole fleet, and a per-driver
  * breakdown is a debugging view for when one pod is the outlier. `capturedAtEpochMillis` is what
  * lets the coordinator tell a stalled driver from a quiet one.
  */
case class DriverHistogramReport(
    version: Int,
    campaign: String,
    driverId: String,
    capturedAtEpochMillis: Long,
    histograms: List[EncodedHistogram],
) derives JsonCodec

/** Quantiles of a merged histogram, in microseconds -- the shape `GET /report/{campaign}` and
  * `GET /status` publish. Microseconds, not `Duration`, because these numbers are read by
  * dashboards and by whoever is arguing about the capacity plan, and a serialised `Duration`
  * round-trips as an ISO-8601 string nobody can sort.
  */
case class LatencySummary(
    id: MeasurementId,
    count: Long,
    minMicros: Long,
    maxMicros: Long,
    meanMicros: Double,
    p50Micros: Long,
    p90Micros: Long,
    p95Micros: Long,
    p99Micros: Long,
    p999Micros: Long,
) derives JsonCodec

object HistogramWire:

  /** Bumped whenever [[DriverHistogramReport]]'s shape changes. Decoding rejects anything else
    * rather than guessing: a campaign spans days, and a rolling driver upgrade mid-run is exactly
    * when a silently misread payload would be most expensive.
    */
  val version: Int = 1

  val unit: String = "microseconds"

  def encode(sample: HistogramSample): EncodedHistogram =
    val buffer = ByteBuffer.allocate(sample.histogram.getNeededByteBufferCapacity)
    val written = sample.histogram.encodeIntoCompressedByteBuffer(buffer)
    val bytes = new Array[Byte](written)
    buffer.rewind()
    buffer.get(bytes)
    EncodedHistogram(
      id = sample.id,
      unit = unit,
      count = sample.histogram.getTotalCount,
      encoding = Base64.getUrlEncoder.encodeToString(bytes),
    )

  def decode(encoded: EncodedHistogram): Either[String, HistogramSample] =
    if encoded.unit != unit then Left(s"unsupported histogram unit '${encoded.unit}' (expected '$unit')")
    else
      for
        bytes <- decodeBase64(encoded.encoding)
        histogram <- decodeHistogram(bytes)
        _ <- Either.cond(
          histogram.getTotalCount == encoded.count,
          (),
          s"histogram count mismatch: envelope says ${encoded.count}, payload holds ${histogram.getTotalCount}",
        )
      yield HistogramSample(encoded.id, histogram)

  def report(
      campaign: String,
      driverId: String,
      capturedAt: Instant,
      samples: Chunk[HistogramSample],
  ): DriverHistogramReport =
    DriverHistogramReport(
      version = version,
      campaign = campaign,
      driverId = driverId,
      capturedAtEpochMillis = capturedAt.toEpochMilli,
      histograms = samples.map(encode).toList,
    )

  def decodeReport(report: DriverHistogramReport): Either[String, Chunk[HistogramSample]] =
    if report.version != version then
      Left(s"unsupported histogram report version ${report.version} (this build reads $version)")
    else
      report.histograms.foldLeft[Either[String, Chunk[HistogramSample]]](Right(Chunk.empty)):
        case (Left(error), _)          => Left(error)
        case (Right(accumulated), one) => decode(one).map(accumulated :+ _)

  /** Lossless merge across drivers and across snapshot intervals.
    *
    * Every histogram is built with the same bounds and precision, so `add` is plain addition of
    * bucket counts: the merged histogram is bit-for-bit what a single histogram fed the union of
    * the samples would hold, and its quantiles are the campaign's quantiles. Merging into a fresh
    * histogram rather than into the first input keeps the inputs usable afterwards -- the
    * coordinator holds them for the per-driver debugging view.
    */
  def merge(samples: Iterable[HistogramSample]): Map[MeasurementId, Histogram] =
    samples.foldLeft(Map.empty[MeasurementId, Histogram]): (merged, sample) =>
      val target = merged.getOrElse(sample.id, LatencyRecorder.emptyHistogram)
      target.add(sample.histogram)
      merged.updated(sample.id, target)

  def summarise(id: MeasurementId, histogram: Histogram): LatencySummary =
    LatencySummary(
      id = id,
      count = histogram.getTotalCount,
      minMicros = histogram.getMinValue,
      maxMicros = histogram.getMaxValue,
      meanMicros = histogram.getMean,
      p50Micros = histogram.getValueAtPercentile(50.0),
      p90Micros = histogram.getValueAtPercentile(90.0),
      p95Micros = histogram.getValueAtPercentile(95.0),
      p99Micros = histogram.getValueAtPercentile(99.0),
      p999Micros = histogram.getValueAtPercentile(99.9),
    )

  def summarise(merged: Map[MeasurementId, Histogram]): List[LatencySummary] =
    merged.toList.map { case (id, histogram) => summarise(id, histogram) }

  def micros(duration: Duration): Long =
    duration.toNanos / 1000L

  private def decodeBase64(encoding: String): Either[String, Array[Byte]] =
    try Right(Base64.getUrlDecoder.decode(encoding))
    catch case ex: IllegalArgumentException => Left(s"histogram payload is not base64url: ${ex.getMessage}")

  private def decodeHistogram(bytes: Array[Byte]): Either[String, Histogram] =
    try Right(Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(bytes), LatencyRecorder.highestTrackableMicros))
    catch
      case ex: DataFormatException =>
        Left(s"histogram payload is not a valid HdrHistogram encoding: ${ex.getMessage}")
      case ex: IllegalArgumentException =>
        Left(s"histogram payload is not a valid HdrHistogram encoding: ${ex.getMessage}")
