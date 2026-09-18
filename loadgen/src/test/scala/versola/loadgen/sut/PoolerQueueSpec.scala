package versola.loadgen.sut

import zio.ZIO
import zio.json.*
import zio.test.*

/** The arithmetic behind §4's sampled half, without a PgBouncer, for [[PoolerStatsDeltaSpec]]'s
  * reason.
  *
  * The behaviour worth pinning down here is which readings are allowed into the accumulation and
  * which are dropped. A peak and a quantile are both statements about a *span*, so a reading
  * folded in from outside the campaign is not a rounding error -- it is a number about a span
  * nobody asked about, and for the quantiles specifically it is one that always drags them
  * towards zero, because the pooler an idle coordinator reads is an empty one.
  */
object PoolerQueueSpec extends ZIOSpecDefault:

  private val campaign = "c3-10m-steady"

  private def pools(clientsWaiting: Long*): List[PoolerPoolStats] =
    clientsWaiting.toList.map(PoolerStatsFixture.pool("auth", "versola", _))

  private def recordAll(recorder: PoolerQueueRecorder, clientsWaiting: Long*) =
    ZIO.foreachDiscard(clientsWaiting)(waiting => recorder.record("auth-pooler", pools(waiting)))

  def spec = suite("PoolerQueueRecorder")(
    test("keeps nothing until the campaign's opening boundary arms it") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recordAll(recorder, 7L, 9L)
        before <- recorder.peaks(campaign)
        _ <- recorder.arm(campaign)
        armed <- recorder.peaks(campaign)
        _ <- recordAll(recorder, 3L)
        after <- recorder.peaks(campaign)
      yield assertTrue(
        before.isEmpty,
        armed.isEmpty,
        after.map(_.peakClientsWaiting) == List(3L),
        after.map(_.samples) == List(1L),
      )
    },
    test("a second campaign's arming discards the first one's readings") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        _ <- recordAll(recorder, 40L)
        _ <- recorder.arm("c4-1m-burst")
        _ <- recordAll(recorder, 2L)
        first <- recorder.peaks(campaign)
        second <- recorder.peaks("c4-1m-burst")
      yield assertTrue(first.isEmpty, second.map(_.peakClientsWaiting) == List(2L))
    },
    // The one reading `PoolerStatsDelta` cannot produce at all, and the reason this exists: the
    // queue opens and drains inside the run, so a bracket of it reads zero at both ends.
    test("the peak is the worst reading, not the last one") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        _ <- recordAll(recorder, 0L, 12L, 48L, 5L, 0L)
        peaks <- recorder.peaks(campaign)
      yield assertTrue(
        peaks.map(_.peakClientsWaiting) == List(48L),
        peaks.map(_.peakWaitMicros) == List(48_000L),
        peaks.map(_.samples) == List(5L),
        peaks.map(_.queuedSamples) == List(3L),
      )
    },
    test("the wait quantiles are nearest-rank over every sample, empty readings included") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        // Ninety-nine empty readings and one 40 ms wait: the p99 is the empty reading and the
        // peak is the wait. A distribution that dropped the empty samples would report a pooler
        // queued at 40 ms for the whole run on the evidence of one percent of it.
        _ <- recordAll(recorder, (List.fill(99)(0L) :+ 40L)*)
        peaks <- recorder.peaks(campaign)
      yield assertTrue(
        peaks.map(_.waitP50Micros) == List(0L),
        peaks.map(_.waitP99Micros) == List(0L),
        peaks.map(_.peakWaitMicros) == List(40_000L),
      )
    },
    test("a pool that queued in most readings carries it into the quantiles") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        _ <- recordAll(recorder, (List.fill(90)(20L) ++ List.fill(10)(0L))*)
        peaks <- recorder.peaks(campaign)
      yield assertTrue(
        peaks.map(_.waitP50Micros) == List(20_000L),
        peaks.map(_.waitP90Micros) == List(20_000L),
        peaks.map(_.queuedSamples) == List(90L),
      )
    },
    test("accumulates one entry per pooler, database and user, in that order") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        _ <- recorder.record(
          "edge-pooler",
          List(PoolerStatsFixture.pool("edge", "versola", 1L)),
        )
        _ <- recorder.record(
          "auth-pooler",
          List(PoolerStatsFixture.pool("auth", "versola", 2L), PoolerStatsFixture.pool("auth", "loadgen", 3L)),
        )
        peaks <- recorder.peaks(campaign)
      yield assertTrue(
        peaks.map(peak => (peak.pooler, peak.database, peak.user)) == List(
          ("auth-pooler", "auth", "loadgen"),
          ("auth-pooler", "auth", "versola"),
          ("edge-pooler", "edge", "versola"),
        ),
        peaks.map(_.peakClientsWaiting) == List(3L, 2L, 1L),
      )
    },
    test("round-trips through JSON, which is how it reaches the report") {
      for
        recorder <- PoolerQueueRecorder.make
        _ <- recorder.arm(campaign)
        _ <- recordAll(recorder, 0L, 17L)
        peaks <- recorder.peaks(campaign)
      yield assertTrue(peaks.toJson.fromJson[List[PoolerQueuePeak]] == Right(peaks))
    },
  )
