package versola.mockapi

import zio.test.*

/** Asserts on the precomputed table rather than on live draws: the table is built by
  * inverse-CDF stratification, so it is byte-for-byte the same on every run and these
  * assertions cannot flake in CI. The startup self-check is what exercises the sampling path.
  */
object DelaySamplerSpec extends ZIOSpecDefault:

  private val read = DelaySampler.make(MixtureWeights.read)
  private val write = DelaySampler.make(MixtureWeights.write)

  private def withinPercent(achieved: Double, target: Double, tolerance: Double): Boolean =
    math.abs(achieved - target) <= tolerance * target

  def spec = suite("DelaySampler")(
    suite("table")(
      test("has the 65,536 entries the dev spec requires") {
        assertTrue(read.size == DelaySampler.TableSize, write.size == DelaySampler.TableSize)
      },
      test("respects the 1 ms floor and the 50 ms clamp") {
        val readEntries = (0 until read.size).map(read.microsAt)
        val writeEntries = (0 until write.size).map(write.microsAt)
        assertTrue(
          readEntries.min >= 1000,
          readEntries.max <= 50000,
          writeEntries.min >= 1000,
          writeEntries.max <= 50000,
        )
      },
      test("durations agree with the microsecond entries") {
        val mismatches = (0 until read.size).count: i =>
          read.durationAt(i).toNanos != read.microsAt(i).toLong * 1000L
        assertTrue(mismatches == 0)
      },
    ),
    suite("read mixture")(
      test("meets its own targets within the self-check's relative tolerance") {
        val targets = DelaySampler.readTargets
        val p50 = read.tableQuantileMicros(0.50) / 1000.0
        val p95 = read.tableQuantileMicros(0.95) / 1000.0
        val p99 = read.tableQuantileMicros(0.99) / 1000.0
        assertTrue(
          withinPercent(p50, targets.p50Millis, 0.10),
          withinPercent(p95, targets.p95Millis, 0.10),
          withinPercent(p99, targets.p99Millis, 0.10),
        )
      },
      // The gap this closes: the self-check above only ever checked a *relative* tolerance, so
      // it could not have caught the p99 the calibration gate actually rejects -- the gate's own
      // tolerance is a 2 ms absolute budget (`CalibrationVerdict.tolerance`), never a percentage,
      // because the same 10% reads very differently at 6 ms and at 46 ms (versolauth/versola#376).
      test("meets its own targets within the calibration gate's absolute 2 ms tolerance") {
        val targets = DelaySampler.readTargets
        def withinMillis(achievedMillis: Double, targetMillis: Double): Boolean =
          math.abs(achievedMillis - targetMillis) <= 2.0
        assertTrue(
          withinMillis(read.tableQuantileMicros(0.50) / 1000.0, targets.p50Millis),
          withinMillis(read.tableQuantileMicros(0.99) / 1000.0, targets.p99Millis),
        )
      },
      // Not the design doc's rounded composite -- the doc's own p50≈6/p95≈30/p99≈46 line was
      // never reproducible from the branch parameters under any floor rule, and is corrected to
      // these branch-derived numbers instead (versolauth/versola#376). p90/mean have no
      // acceptance criterion of their own; asserted here only so a future change to the branch
      // parameters or the floor rule cannot silently drift without a test noticing.
      test("has the branch-derived p90 and mean") {
        assertTrue(
          withinPercent(read.tableQuantileMicros(0.90) / 1000.0, 24.238, 0.15),
          withinPercent(read.tableMeanMicros / 1000.0, 10.597, 0.10),
        )
      },
      test("keeps the bulk of its mass in the sub-10 ms cache branch") {
        val fastShare = (0 until read.size).count(i => read.microsAt(i) <= 10000).toDouble / read.size
        val tailShare = (0 until read.size).count(i => read.microsAt(i) >= 41000).toDouble / read.size
        assertTrue(fastShare > 0.66, fastShare < 0.70, tailShare > 0.02, tailShare < 0.05)
      },
    ),
    suite("write mixture")(
      test("is slower than the read mixture at every quantile that matters") {
        assertTrue(
          write.tableQuantileMicros(0.50) > read.tableQuantileMicros(0.50),
          write.tableQuantileMicros(0.95) > read.tableQuantileMicros(0.95),
          write.tableMeanMicros > read.tableMeanMicros,
        )
      },
      test("meets its own targets within the self-check's relative tolerance") {
        val targets = DelaySampler.writeTargets
        assertTrue(
          withinPercent(write.tableQuantileMicros(0.50) / 1000.0, targets.p50Millis, 0.10),
          withinPercent(write.tableQuantileMicros(0.95) / 1000.0, targets.p95Millis, 0.10),
          withinPercent(write.tableQuantileMicros(0.99) / 1000.0, targets.p99Millis, 0.10),
        )
      },
      // See the read-mixture suite's identical test for why this tolerance exists alongside the
      // relative one above (versolauth/versola#376).
      test("meets its own targets within the calibration gate's absolute 2 ms tolerance") {
        val targets = DelaySampler.writeTargets
        def withinMillis(achievedMillis: Double, targetMillis: Double): Boolean =
          math.abs(achievedMillis - targetMillis) <= 2.0
        assertTrue(
          withinMillis(write.tableQuantileMicros(0.50) / 1000.0, targets.p50Millis),
          withinMillis(write.tableQuantileMicros(0.99) / 1000.0, targets.p99Millis),
        )
      },
      test("carries the heavier core-banking tail the write weights ask for") {
        val writeTail = (0 until write.size).count(i => write.microsAt(i) >= 41000).toDouble / write.size
        val readTail = (0 until read.size).count(i => read.microsAt(i) >= 41000).toDouble / read.size
        assertTrue(writeTail > 0.10, writeTail < 0.14, writeTail > 3 * readTail)
      },
    ),
    suite("sampling")(
      test("only ever returns values that are in the table") {
        val allowed = (0 until read.size).map(read.microsAt).toSet
        val drawn = (0 until 20000).map(_ => read.microsAt(read.drawIndex()))
        assertTrue(drawn.forall(allowed.contains), drawn.distinct.size > 1000)
      },
      test("a 1M-draw self-check passes the tolerance it is configured with") {
        val achieved = DelaySampler.sampleQuantiles(read, 1000000)
        assertTrue(
          DelaySampler.deviations(achieved, DelaySampler.readTargets, 0.10).isEmpty,
          DelaySampler
            .deviations(
              DelaySampler.sampleQuantiles(write, 1000000),
              DelaySampler.writeTargets,
              0.10,
            )
            .isEmpty,
        )
      },
      test("reports the quantiles that miss an unattainable target") {
        val achieved = DelaySampler.sampleQuantiles(read, 100000)
        val impossible = QuantileTargets(p50Millis = 1.0, p95Millis = 30.0, p99Millis = 1.0)
        val failures = DelaySampler.deviations(achieved, impossible, 0.10)
        assertTrue(
          failures.size == 2,
          failures.exists(_.startsWith("p50")),
          failures.exists(_.startsWith("p99")),
        )
      },
    ),
  )
