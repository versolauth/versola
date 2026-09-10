package versola.loadgen.metrics

import zio.test.*
import zio.{Duration, ZIO}

import java.time.Instant

object LatencyRecorderSpec extends ZIOSpecDefault:

  private val step = MeasurementId.Step("mobile-otp", "token-refresh")
  private val flow = MeasurementId.Flow("refresh")

  private def latency(millis: Long): IntendedLatency =
    IntendedLatency.unsafe(Duration.fromMillis(millis))

  private def sampleFor(samples: zio.Chunk[HistogramSample], id: MeasurementId): org.HdrHistogram.Histogram =
    samples.collectFirst { case HistogramSample(`id`, histogram) => histogram }.get

  def spec = suite("LatencyRecorder")(
    test("records per measurement, in microseconds") {
      for
        recorder <- LatencyRecorder.make
        _ <- ZIO.foreachDiscard(List(10L, 10L, 20L))(millis => recorder.record(step, latency(millis)))
        _ <- recorder.record(flow, latency(300))
        samples <- recorder.snapshot
        stepHistogram = sampleFor(samples, step)
        flowHistogram = sampleFor(samples, flow)
      yield assertTrue(
        samples.size == 2,
        stepHistogram.getTotalCount == 3L,
        stepHistogram.getCountAtValue(10_000L) == 2L,
        // Compared through `valuesAreEquivalent` because 3 significant digits quantise anything
        // above 2,048 µs into a bucket a few microseconds wide; the bucket is the measurement.
        stepHistogram.valuesAreEquivalent(stepHistogram.getValueAtPercentile(100.0), 20_000L),
        flowHistogram.getTotalCount == 1L,
        flowHistogram.valuesAreEquivalent(flowHistogram.getValueAtPercentile(100.0), 300_000L),
      )
    },
    test("consecutive snapshots partition the recorded values rather than repeating them") {
      for
        recorder <- LatencyRecorder.make
        _ <- ZIO.foreachDiscard(1 to 5)(_ => recorder.record(step, latency(10)))
        first <- recorder.snapshot
        _ <- ZIO.foreachDiscard(1 to 3)(_ => recorder.record(step, latency(10)))
        second <- recorder.snapshot
        third <- recorder.snapshot
      yield assertTrue(
        sampleFor(first, step).getTotalCount == 5L,
        sampleFor(second, step).getTotalCount == 3L,
        sampleFor(third, step).getTotalCount == 0L,
      )
    },
    test("clamps a latency past the tracked range and counts the clamp") {
      val clamped = zio.metrics.Metric.counter("loadgen_latency_clamped_total")
      for
        recorder <- LatencyRecorder.make
        before <- clamped.value.map(_.count)
        _ <- recorder.record(step, IntendedLatency.unsafe(Duration.fromSeconds(120)))
        after <- clamped.value.map(_.count)
        samples <- recorder.snapshot
        histogram = sampleFor(samples, step)
      yield assertTrue(
        histogram.getTotalCount == 1L,
        histogram.valuesAreEquivalent(histogram.getMaxValue, LatencyRecorder.highestTrackableMicros),
        after - before == 1.0,
      )
    },
    test("records a sub-microsecond latency at the lowest discernible value") {
      for
        recorder <- LatencyRecorder.make
        _ <- recorder.record(step, IntendedLatency.unsafe(Duration.fromNanos(400)))
        samples <- recorder.snapshot
      yield assertTrue(sampleFor(samples, step).getMaxValue == LatencyRecorder.lowestDiscernibleMicros)
    },
    test("measures from the intended start, not from when the request actually went out") {
      val intendedStart = Instant.parse("2026-09-10T18:00:00Z")
      val actualStart = intendedStart.plusMillis(80)
      val completedAt = actualStart.plusMillis(45)
      assertTrue(
        IntendedLatency.between(intendedStart, completedAt).toDuration == Duration.fromMillis(125),
        IntendedLatency.between(intendedStart, completedAt).micros == 125_000L,
        IntendedLatency.between(intendedStart, completedAt).seconds == 0.125,
      )
    },
    test("a completion before its intended start reads as zero, not as a negative latency") {
      val intendedStart = Instant.parse("2026-09-10T18:00:00Z")
      assertTrue(IntendedLatency.between(intendedStart, intendedStart.minusMillis(5)).toDuration == Duration.Zero)
    },
    test("the tracked range and precision are the ones the spec fixes") {
      assertTrue(
        LatencyRecorder.lowestDiscernibleMicros == 1L,
        LatencyRecorder.highestTrackableMicros == 60_000_000L,
        LatencyRecorder.significantDigits == 3,
        LatencyRecorder.emptyHistogram.getNumberOfSignificantValueDigits == 3,
      )
    },
  )
