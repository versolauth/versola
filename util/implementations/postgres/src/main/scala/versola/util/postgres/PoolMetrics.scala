package versola.util.postgres

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.{IMetricsTracker, MetricsTrackerFactory, PoolStats}
import zio.*

import java.util.concurrent.atomic.{AtomicLong, AtomicLongArray}

/** The connection-acquisition wait, accumulated where HikariCP reports it and read where ZIO can
  * publish it.
  *
  * HikariCP hands every acquisition's elapsed time to an [[IMetricsTracker]] synchronously, on the
  * thread that did the acquiring. A `zio.metrics` histogram cannot be updated from there: `update`
  * is an effect, its unsafe counterpart is `private[zio]`, and running a fiber per acquisition would
  * put a scheduler hop in front of every `getConnection` in every service on this pool.
  *
  * So the tracker writes into the same buckets the histogram uses -- two atomic adds, no allocation,
  * no ZIO -- and a fiber replays the difference since its last read into the real histogram. The
  * replay is exact rather than approximate, which is the whole reason the sums are kept alongside
  * the counts: a bucket holding `n` observations that total `s` is reproduced by observing `s / n`
  * exactly `n` times. Their mean lies strictly inside the bucket's own interval (every value in it
  * does, and an interval is convex), so it lands in the same bucket, and `n` of it contributes
  * exactly `s` to the histogram's sum. `_bucket`, `_count` and `_sum` all come out as if every
  * observation had been recorded individually; only `_min` and `_max` are lost, and neither is a
  * quantile.
  *
  * Counters are cumulative and are never reset, so a concurrent `record` during a `drain` is carried
  * into the next one rather than dropped.
  */
private[postgres] final class ConnectionWaitBuckets(boundaries: Chunk[Double]):

  private val bounds = boundaries.sorted.toArray
  private val counts = AtomicLongArray(bounds.length)
  private val sumNanos = AtomicLongArray(bounds.length)
  private val timeouts = AtomicLong()

  private val drainedCounts = Array.ofDim[Long](bounds.length)
  private val drainedSumNanos = Array.ofDim[Long](bounds.length)
  private var drainedTimeouts = 0L

  def record(elapsedNanos: Long): Unit =
    val bucket = indexOf(elapsedNanos.toDouble / 1e9)
    counts.getAndIncrement(bucket)
    sumNanos.getAndAdd(bucket, elapsedNanos)

  def recordTimeout(): Unit =
    timeouts.getAndIncrement()

  /** Everything observed since the previous call, as the observations to replay and the number of
    * timeouts to add. Single-reader: only the publishing fiber calls this.
    */
  def drain(): ConnectionWaitBuckets.Drained =
    val replays = Chunk.newBuilder[(Double, Long)]
    var i = 0
    while i < bounds.length do
      val count = counts.get(i) - drainedCounts(i)
      if count > 0L then
        val nanos = sumNanos.get(i) - drainedSumNanos(i)
        drainedCounts(i) += count
        drainedSumNanos(i) += nanos
        // Clamped to the bucket's upper bound so floating-point rounding of the mean cannot push a
        // replayed observation into the next bucket. Rounding it below the bucket's lower bound is
        // not possible: every value in the bucket is strictly above that bound, so their mean is too.
        replays += math.min(nanos.toDouble / count.toDouble / 1e9, bounds(i)) -> count
      i += 1

    val timeoutsNow = timeouts.get()
    val timeoutDelta = timeoutsNow - drainedTimeouts
    drainedTimeouts = timeoutsNow

    ConnectionWaitBuckets.Drained(replays.result(), timeoutDelta)

  /** The smallest boundary at or above `seconds`, matching how `zio.metrics` assigns a histogram
    * bucket. The boundary chunk's last element is `Double.MaxValue` (`Boundaries.fromChunk` appends
    * it), so the scan always terminates inside the array.
    */
  private def indexOf(seconds: Double): Int =
    var i = 0
    while i < bounds.length - 1 && bounds(i) < seconds do i += 1
    i

private[postgres] object ConnectionWaitBuckets:

  final case class Drained(replays: Chunk[(Double, Long)], timeouts: Long)

/** Publishes a HikariCP pool's own readings as the `db_client_connection_*` metrics of [[DbMetrics]].
  *
  * Two sources, because HikariCP splits them. Occupancy is a level and is pulled from
  * `HikariPoolMXBean` -- the interface is reachable straight off the `HikariDataSource`, so none of
  * this needs `registerMbeans` or a JMX exporter beside the pod. The acquisition wait and the
  * timeout count are events and exist only as [[IMetricsTracker]] callbacks, which is what
  * [[ConnectionWaitBuckets]] is for.
  */
private[postgres] object PoolMetrics:

  /** HikariCP asks for a factory and calls it once per pool, after construction. The tracker is
    * built before the pool so the buckets it writes into can be handed to the publishing fiber too;
    * `PoolStats` is ignored because the level readings come from the MXBean instead, which is not
    * behind `PoolStats`' own 1-second staleness window.
    */
  def trackerFactory(buckets: ConnectionWaitBuckets): MetricsTrackerFactory =
    new MetricsTrackerFactory:
      override def create(poolName: String, poolStats: PoolStats): IMetricsTracker =
        new IMetricsTracker:
          override def recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long): Unit =
            buckets.record(elapsedAcquiredNanos)

          override def recordConnectionTimeout(): Unit =
            buckets.recordTimeout()

  /** One reading. `getHikariPoolMXBean` is null before the pool is initialised and after it is
    * closed; occupancy is skipped in that case rather than published as zeroes, which would read on
    * a board as a pool that emptied instead of a pool that is gone. The waits are drained either
    * way -- they were observed by a pool that was alive at the time.
    */
  def publish(dataSource: HikariDataSource, buckets: ConnectionWaitBuckets): UIO[Unit] =
    val poolName = dataSource.getPoolName
    for
      pool <- ZIO.succeed(Option(dataSource.getHikariPoolMXBean))
      _ <- pool.fold(ZIO.unit): mxBean =>
        DbMetrics.poolOccupancy(
          poolName = poolName,
          used = mxBean.getActiveConnections,
          idle = mxBean.getIdleConnections,
          max = dataSource.getMaximumPoolSize,
          idleMin = dataSource.getMinimumIdle,
          pendingRequests = mxBean.getThreadsAwaitingConnection,
        )
      drained <- ZIO.succeed(buckets.drain())
      _ <- ZIO.foreachDiscard(drained.replays): (seconds, count) =>
        DbMetrics.connectionWait(poolName, seconds).repeatN((count - 1L).toInt)
      _ <- DbMetrics.connectionTimeouts(poolName, drained.timeouts)
    yield ()

  /** Publishes immediately and then every `interval`, on a fiber tied to the pool's scope.
    *
    * Immediately, and not after the first interval, so that a pool that dies during startup still
    * leaves its configured size and its first occupancy reading behind. Tied to the scope so the
    * fiber cannot outlive the pool it is reading and start publishing the `getHikariPoolMXBean`
    * null case forever.
    */
  def publishing(dataSource: HikariDataSource, buckets: ConnectionWaitBuckets, interval: Duration): ZIO[Scope, Nothing, Unit] =
    publish(dataSource, buckets).repeat(Schedule.spaced(interval)).forkScoped.unit
