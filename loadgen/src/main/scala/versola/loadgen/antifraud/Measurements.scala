package versola.loadgen.antifraud

import org.HdrHistogram.ConcurrentHistogram
import zio.json.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** p50, p99 and the largest of the successful calls of one kind, in milliseconds, with how many failed.
  *
  * A failed call is counted and left out of the quantiles: a timeout recorded at the timeout would make the tail
  * a property of the timeout rather than of the system.
  */
case class Quantiles(count: Long, errors: Long, p50Ms: Double, p99Ms: Double, maxMs: Double) derives JsonCodec

object Quantiles:
  val none: Quantiles = Quantiles(0, 0, 0.0, 0.0, 0.0)

/** The latencies one scenario recorded, by the name of the call (`otp-request`, `otp-verify`, `token`, `refresh`). */
final class Measurements:
  private val histograms = ConcurrentHashMap[String, ConcurrentHistogram]()
  private val errors = ConcurrentHashMap[String, LongAdder]()

  def record(name: String, micros: Long): Unit =
    histograms
      .computeIfAbsent(name, _ => ConcurrentHistogram(1L, Measurements.highestMicros, 3))
      .recordValue(math.min(math.max(micros, 1L), Measurements.highestMicros))

  def failed(name: String): Unit = errors.computeIfAbsent(name, _ => LongAdder()).increment()

  def quantiles: Map[String, Quantiles] =
    (histograms.keySet.asScala ++ errors.keySet.asScala).toList.sorted.map: name =>
      val failures = Option(errors.get(name)).fold(0L)(_.sum)
      name -> Option(histograms.get(name)).fold(Quantiles.none.copy(errors = failures)): histogram =>
        Quantiles(
          count = histogram.getTotalCount,
          errors = failures,
          p50Ms = histogram.getValueAtPercentile(50.0) / 1000.0,
          p99Ms = histogram.getValueAtPercentile(99.0) / 1000.0,
          maxMs = histogram.getMaxValue / 1000.0,
        )
    .toMap

object Measurements:
  val highestMicros: Long = 60L * 1000L * 1000L
