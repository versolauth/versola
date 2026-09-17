package versola.loadgen.sut

import zio.json.JsonCodec
import zio.{Duration, Ref, UIO, durationInt}

/** The non-cumulative half of §4, for one `(pooler, database, user)` pool over one campaign: the
  * queue's peak and the distribution of its wait (runbook 05-report-spec.md §4).
  *
  * `PoolerStatsDelta` cannot carry either figure and says so where the field is. `SHOW POOLS` is
  * the queue at the instant of the query, so the two readings bracketing a run are two instants
  * and the run's worst queue is in neither of them. 08-report-data-gaps.md's remedy was a
  * PgBouncer exporter; this is the same answer without one -- the coordinator already has the
  * admin console open, so it reads `SHOW POOLS` on a timer for the length of the run and keeps
  * what the series says.
  *
  * What that does and does not entitle the report to claim:
  *
  *   - [[peakClientsWaiting]] is the worst queue *seen*, not the worst queue. A spike that opens
  *     and drains between two samples leaves no trace at all, so this is a lower bound on the
  *     campaign's peak and never an upper one.
  *   - the wait quantiles are quantiles over the samples, not over the clients that waited.
  *     `maxwait` reports the oldest currently-waiting client, so one sample is one observation of
  *     the queue's head and a client whose whole wait fell between two samples is not in the
  *     distribution. `waitP99Micros` is "the wait at the head of the queue in the worst 1% of
  *     observations", which is a statement about the pooler over the run, and it is not the p99
  *     of a client's wait.
  *   - [[samples]] is how many readings actually landed, not how many were attempted. A pooler
  *     that was unreachable for half the run reports the half it answered for, and the count is
  *     the only evidence of that -- see [[PoolerStatsCapture.sample]] on why a failed reading is
  *     not logged at warning level.
  *
  * @param queuedSamples
  *   how many of [[samples]] found anybody waiting. The one figure here that is a rate rather
  *   than an extreme, and the one an operator reads first: a pool whose peak queue is 40 but
  *   which was empty in 99% of readings is a pool that absorbed a burst, and one that was queued
  *   in every reading is a pool that is too small for the run.
  */
case class PoolerQueuePeak(
    pooler: String,
    database: String,
    user: String,
    samples: Long,
    queuedSamples: Long,
    peakClientsWaiting: Long,
    peakClientsActive: Long,
    peakServersActive: Long,
    peakWaitMicros: Long,
    waitP50Micros: Long,
    waitP90Micros: Long,
    waitP99Micros: Long,
) derives JsonCodec

/** Accumulates [[PoolerQueuePeak]] for every pool of every configured pooler, in the
  * coordinator's memory, for exactly the span between the campaign's two boundaries.
  *
  * In memory and not in a table, unlike the bracket in `vu_pooler_stat_snapshots`. The bracket is
  * persisted because a delta needs both of its ends and the "before" end cannot be reconstructed
  * once it is lost; a peak needs no pairing, and the cost of losing it is the peak of one
  * coordinator's share of the run rather than a section that cannot be computed at all. A
  * coordinator restarted mid-campaign therefore reports the peak since its own start, and
  * `samples` is what shows that it did.
  *
  * Armed by the "before" capture and never disarmed. `record` before the campaign opens is
  * dropped rather than accumulated -- a coordinator idles for however long the operator takes to
  * start, and folding an empty pool's readings into the distribution would move every quantile
  * towards zero by an amount that depends on nothing but that wait.
  */
final class PoolerQueueRecorder private (state: Ref[Option[PoolerQueueRecorder.Armed]]):

  /** Resets to an empty accumulation for a new campaign. */
  def arm(campaign: String): UIO[Unit] =
    state.set(Some(PoolerQueueRecorder.Armed(campaign, Map.empty)))

  /** Folds one pooler's `SHOW POOLS` reading in. A no-op before [[arm]]. */
  def record(pooler: String, pools: List[PoolerPoolStats]): UIO[Unit] =
    state.update(_.map(armed => armed.copy(pools = pools.foldLeft(armed.pools)(fold(pooler)))))

  /** Every pool that was sampled at least once, in `(pooler, database, user)` order for
    * [[PoolerStatsDelta.from]]'s reason.
    *
    * Empty for a campaign other than the armed one. The coordinator serves one campaign and
    * refuses a report for any other, so this cannot happen today; it is checked because the
    * consequence if it ever does is one run's peaks presented as another's, which is a wrong
    * number rather than a missing section.
    */
  def peaks(campaign: String): UIO[List[PoolerQueuePeak]] =
    state.get.map:
      case Some(armed) if armed.campaign == campaign =>
        armed.pools.toList
          .sortBy((key, _) => key)
          .map((key, accumulated) => accumulated.summarise(key))
      case _ => Nil

  private def fold(pooler: String)(
      pools: Map[(String, String, String), PoolerQueueRecorder.Accumulated],
      reading: PoolerPoolStats,
  ): Map[(String, String, String), PoolerQueueRecorder.Accumulated] =
    val key = (pooler, reading.database, reading.user)
    pools.updated(key, pools.getOrElse(key, PoolerQueueRecorder.Accumulated.empty).plus(reading))

object PoolerQueueRecorder:

  /** How often the queue is read.
    *
    * Five seconds rather than the one second `ScheduleLagQuantile` samples the driver's dispatch
    * lag at, because a sample here is a JDBC connection and a query against the pooler in front
    * of the SUT rather than a read of a `Ref`: the instrument has to stay cheap against the thing
    * it measures for the whole of
    * a ten-hour run. It buys 7200 samples over such a run, which is enough for a p99 to mean
    * something and few enough that the raw series fits in memory -- see [[Accumulated.waits]].
    */
  val sampleInterval: Duration = 5.seconds

  val make: UIO[PoolerQueueRecorder] = Ref.make(Option.empty[Armed]).map(PoolerQueueRecorder(_))

  private[sut] case class Armed(campaign: String, pools: Map[(String, String, String), Accumulated])

  /** One pool's running maxima and the wait series behind its quantiles.
    *
    * @param waits
    *   every `maxwait` sample, kept rather than folded into an HdrHistogram as every other
    *   quantile in this module is. The histogram is there because a driver's latency is millions
    *   of samples that have to merge across a fleet; this is one process reading one pooler every
    *   five seconds, so the whole series is a few thousand longs per pool and an exact quantile
    *   costs a sort at report time. A campaign long enough for that to matter -- a week -- would
    *   have outgrown a coordinator's memory for other reasons first.
    */
  private[sut] case class Accumulated(
      samples: Long,
      queuedSamples: Long,
      peakClientsWaiting: Long,
      peakClientsActive: Long,
      peakServersActive: Long,
      waits: Vector[Long],
  ):

    def plus(reading: PoolerPoolStats): Accumulated =
      Accumulated(
        samples = samples + 1L,
        queuedSamples = queuedSamples + (if reading.clientsWaiting > 0L then 1L else 0L),
        peakClientsWaiting = math.max(peakClientsWaiting, reading.clientsWaiting),
        peakClientsActive = math.max(peakClientsActive, reading.clientsActive),
        peakServersActive = math.max(peakServersActive, reading.serversActive),
        waits = waits :+ reading.maxWaitMicros,
      )

    def summarise(key: (String, String, String)): PoolerQueuePeak =
      val (pooler, database, user) = key
      val sorted = waits.sorted
      PoolerQueuePeak(
        pooler = pooler,
        database = database,
        user = user,
        samples = samples,
        queuedSamples = queuedSamples,
        peakClientsWaiting = peakClientsWaiting,
        peakClientsActive = peakClientsActive,
        peakServersActive = peakServersActive,
        peakWaitMicros = sorted.lastOption.getOrElse(0L),
        waitP50Micros = quantile(sorted, 50.0),
        waitP90Micros = quantile(sorted, 90.0),
        waitP99Micros = quantile(sorted, 99.0),
      )

    /** Nearest-rank, the definition HdrHistogram's `getValueAtPercentile` uses, so §4's quantiles
      * and the latency quantiles beside them in the same report mean the same thing. No
      * interpolation: a wait of 0 and a wait of 8 ms are both readings that happened, and the
      * 4 ms between them is not one.
      */
    private def quantile(sorted: IndexedSeq[Long], percentile: Double): Long =
      if sorted.isEmpty then 0L
      else
        val rank = math.ceil(percentile / 100.0 * sorted.size.toDouble).toInt
        sorted(math.min(math.max(rank, 1), sorted.size) - 1)

  private[sut] object Accumulated:
    val empty: Accumulated = Accumulated(0L, 0L, 0L, 0L, 0L, Vector.empty)
