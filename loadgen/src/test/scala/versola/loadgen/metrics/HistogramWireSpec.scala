package versola.loadgen.metrics

import zio.json.*
import zio.test.*
import zio.{Chunk, Duration}

import java.time.Instant

object HistogramWireSpec extends ZIOSpecDefault:

  private val proxyStep = MeasurementId.Step("mobile-otp", "proxy-accounts")
  private val loginFlow = MeasurementId.Flow("full-login")

  private def histogramOf(values: (Long, Long)*): org.HdrHistogram.Histogram =
    val histogram = LatencyRecorder.emptyHistogram
    values.foreach { case (value, count) => histogram.recordValueWithCount(value, count) }
    histogram

  /** Two drivers, one measurement, and an answer that does not depend on this implementation:
    * 100 samples of exactly 1,000 µs and 100 of exactly 2,000 µs. Both values are below 2,048, so
    * with 3 significant digits (2,048 sub-buckets) every bucket in that range is one microsecond
    * wide and HdrHistogram stores them exactly -- no quantisation to reason about.
    *
    * The merged set of 200 samples therefore has count 200, min 1,000, max 2,000, mean 1,500, and
    * a median of 1,000 (the 100th of 200 ordered samples is the last of the 1,000s). p90 and p99
    * fall in the upper half, so both are 2,000.
    */
  private val driverOne = HistogramSample(proxyStep, histogramOf(1000L -> 100L))
  private val driverTwo = HistogramSample(proxyStep, histogramOf(2000L -> 100L))

  def spec = suite("HistogramWire")(
    test("merges two drivers' histograms into the union of their samples") {
      val merged = HistogramWire.merge(List(driverOne, driverTwo))
      val histogram = merged(proxyStep)
      assertTrue(
        merged.keySet == Set(proxyStep),
        histogram.getTotalCount == 200L,
        histogram.getCountAtValue(1000L) == 100L,
        histogram.getCountAtValue(2000L) == 100L,
        histogram.getMinValue == 1000L,
        histogram.getMaxValue == 2000L,
        histogram.getMean == 1500.0,
        histogram.getValueAtPercentile(50.0) == 1000L,
        histogram.getValueAtPercentile(90.0) == 2000L,
        histogram.getValueAtPercentile(99.0) == 2000L,
      )
    },
    test("merging leaves the inputs intact for the per-driver view") {
      val _ = HistogramWire.merge(List(driverOne, driverTwo))
      assertTrue(
        driverOne.histogram.getTotalCount == 100L,
        driverOne.histogram.getMaxValue == 1000L,
        driverTwo.histogram.getTotalCount == 100L,
        driverTwo.histogram.getMinValue == 2000L,
      )
    },
    test("merges measurements independently") {
      val merged = HistogramWire.merge(
        List(driverOne, driverTwo, HistogramSample(loginFlow, histogramOf(7000L -> 3L))),
      )
      assertTrue(
        merged.keySet == Set(proxyStep, loginFlow),
        merged(proxyStep).getTotalCount == 200L,
        merged(loginFlow).getTotalCount == 3L,
      )
    },
    test("summarises the merged histogram in microseconds") {
      val summary = HistogramWire.summarise(proxyStep, HistogramWire.merge(List(driverOne, driverTwo))(proxyStep))
      assertTrue(
        summary == LatencySummary(
          id = proxyStep,
          count = 200L,
          minMicros = 1000L,
          maxMicros = 2000L,
          meanMicros = 1500.0,
          p50Micros = 1000L,
          p90Micros = 2000L,
          p95Micros = 2000L,
          p99Micros = 2000L,
          p999Micros = 2000L,
        ),
      )
    },
    test("a snapshot round-trips through the wire format unchanged") {
      val sample = HistogramSample(proxyStep, histogramOf(1000L -> 100L, 2000L -> 100L, 47_000L -> 5L))
      val encoded = HistogramWire.encode(sample)
      val decoded = HistogramWire.decode(encoded)
      assertTrue(
        encoded.unit == "microseconds",
        encoded.count == 205L,
        decoded.map(_.id) == Right(proxyStep),
        decoded.map(_.histogram.getTotalCount) == Right(205L),
        decoded.map(_.histogram.getCountAtValue(1000L)) == Right(100L),
        decoded.map(_.histogram.getValueAtPercentile(99.0)) == Right(sample.histogram.getValueAtPercentile(99.0)),
        decoded.map(_.histogram.equals(sample.histogram)) == Right(true),
      )
    },
    test("a full driver report round-trips through JSON and merges to the same numbers") {
      val report = HistogramWire.report(
        campaign = "c3-10m-steady",
        driverId = "loadgen-driver-2",
        capturedAt = Instant.parse("2026-09-10T18:00:00Z"),
        samples = Chunk(driverOne, HistogramSample(loginFlow, histogramOf(7000L -> 3L))),
      )
      val json = report.toJson
      val parsed = json.fromJson[DriverHistogramReport]
      val samples = parsed.left.map(_.toString).flatMap(HistogramWire.decodeReport)
      val merged = samples.map(HistogramWire.merge)
      assertTrue(
        report.version == HistogramWire.version,
        parsed.map(_.campaign) == Right("c3-10m-steady"),
        parsed.map(_.driverId) == Right("loadgen-driver-2"),
        parsed.map(_.capturedAtEpochMillis) == Right(Instant.parse("2026-09-10T18:00:00Z").toEpochMilli),
        merged.map(_(proxyStep).getTotalCount) == Right(100L),
        merged.map(_(proxyStep).getValueAtPercentile(50.0)) == Right(1000L),
        merged.map(_(loginFlow).getTotalCount) == Right(3L),
      )
    },
    test("rejects a report from a future wire version instead of guessing at it") {
      val report = HistogramWire
        .report("c3", "driver-0", Instant.EPOCH, Chunk(driverOne))
        .copy(version = HistogramWire.version + 1)
      assertTrue(HistogramWire.decodeReport(report).isLeft)
    },
    test("rejects a payload whose count disagrees with its envelope") {
      val tampered = HistogramWire.encode(driverOne).copy(count = 99L)
      assertTrue(HistogramWire.decode(tampered).isLeft)
    },
    test("rejects an unknown unit and an unreadable payload") {
      val encoded = HistogramWire.encode(driverOne)
      assertTrue(
        HistogramWire.decode(encoded.copy(unit = "milliseconds")).isLeft,
        HistogramWire.decode(encoded.copy(encoding = "not-a-histogram")).isLeft,
      )
    },
    test("rejects a payload whose range or precision is not the one merge assumes") {
      // `decodeFromCompressedByteBuffer` takes a *floor* on the highest trackable value, so a
      // wider or differently-quantised histogram -- a driver on another build -- decodes happily
      // and only fails later: `add` throws on a value past the fixed merge target's range, and a
      // precision mismatch re-buckets the counts, so the merged quantiles stop being the measured
      // ones while still being reported as lossless.
      def encodeOf(histogram: org.HdrHistogram.Histogram) =
        HistogramWire.encode(HistogramSample(proxyStep, histogram))

      val wider = org.HdrHistogram.Histogram(
        LatencyRecorder.lowestDiscernibleMicros,
        LatencyRecorder.highestTrackableMicros * 10L,
        LatencyRecorder.significantDigits,
      )
      wider.recordValue(LatencyRecorder.highestTrackableMicros * 5L)

      val coarser = org.HdrHistogram.Histogram(
        LatencyRecorder.lowestDiscernibleMicros,
        LatencyRecorder.highestTrackableMicros,
        LatencyRecorder.significantDigits - 1,
      )
      coarser.recordValue(1000L)

      assertTrue(
        HistogramWire.decode(encodeOf(wider)).isLeft,
        HistogramWire.decode(encodeOf(coarser)).isLeft,
        // The geometry this build writes still round-trips.
        HistogramWire.decode(HistogramWire.encode(driverOne)).isRight,
      )
    },
    test("summing consecutive interval snapshots equals one histogram over the whole run") {
      val values = (1L to 200L).toList
      val whole = LatencyRecorder.emptyHistogram
      values.foreach(whole.recordValue)
      val firstInterval = histogramOf(values.take(120).map(_ -> 1L)*)
      val secondInterval = histogramOf(values.drop(120).map(_ -> 1L)*)
      val merged = HistogramWire.merge(
        List(HistogramSample(proxyStep, firstInterval), HistogramSample(proxyStep, secondInterval)),
      )(proxyStep)
      assertTrue(
        merged.getTotalCount == whole.getTotalCount,
        merged.getValueAtPercentile(50.0) == whole.getValueAtPercentile(50.0),
        merged.getValueAtPercentile(99.0) == whole.getValueAtPercentile(99.0),
        merged.equals(whole),
      )
    },
    test("converts durations to the microseconds the report is stated in") {
      assertTrue(
        HistogramWire.micros(Duration.fromMillis(120)) == 120_000L,
        HistogramWire.micros(Duration.fromMillis(15)) == 15_000L,
      )
    },
  )
