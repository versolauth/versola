package versola.loadgen.store

import versola.loadgen.config.{StoreConfig, WriteBehindConfig}
import zio.*

/** Applies one coalesced flush window to the store. Separate from [[WriteBehindBuffer]] so the
  * buffer's batching, timing and drop behaviour are testable without a database.
  */
trait DeferredWriteSink:
  def write(batch: DeferredBatch): Task[Unit]

/** The deferred write path of dev spec §7.5: scenario fibers hand their bookkeeping over and
  * continue, and a single background fiber applies it every `flush-interval` or every
  * `batch-size` rows, whichever comes first.
  *
  * The queue is bounded and it **drops** on overflow instead of applying back-pressure. That is
  * the whole point of the component: the emulator is the measuring instrument, and a store that
  * made scenario fibers wait would distort the very load the campaign is measuring. What is
  * lost is `last_seen_at`, `access_expires_at` and forensic event samples -- never a credential
  * and never an `acr`, both of which go through the repositories' critical methods directly (see
  * [[SessionTouch]] on why `acr` left §7.5's deferred class).
  */
trait WriteBehindBuffer:
  def enqueue(update: DeferredUpdate): UIO[Unit]

  /** Drains everything currently queued, awaited. Used at shutdown and by tests; the ordinary
    * path is the background loop.
    */
  def flush: UIO[Unit]

  /** Updates refused at [[enqueue]] because the bounded queue was full: the store is not
    * keeping up with the drivers, so the emulator is undersized and the run is invalid.
    */
  def droppedOnEnqueue: UIO[Long]

  /** Updates lost because a flush the store rejected was not retried: Postgres returned an
    * error, which is a defect rather than a capacity problem.
    *
    * Separate from [[droppedOnEnqueue]] because the two call for different actions even though
    * both invalidate a campaign the same way -- one is answered by more store capacity or a
    * larger queue, the other by reading the log and fixing something.
    */
  def droppedOnFlush: UIO[Long]

  /** Source of `loadgen_store_flush_dropped_total` (§11), which must stay at 0. The sum of the
    * two counters above, so a reader who only wants "did we lose bookkeeping" has one number.
    */
  def droppedTotal: UIO[Long]

  /** Current queue depth: the leading indicator for the counter above. */
  def pending: UIO[Int]

object WriteBehindBuffer:

  /** @param capacity
    *   bounded queue depth, in updates. Explicit rather than derived inside, because the value
    *   that makes a test deterministic and the value that makes a driver survive a Postgres
    *   stall are not the same number.
    */
  def make(config: WriteBehindConfig, capacity: Int, sink: DeferredWriteSink): URIO[Scope, WriteBehindBuffer] =
    for
      queue <- Queue.dropping[DeferredUpdate](capacity)
      doorbell <- Queue.dropping[Unit](1)
      droppedEnqueue <- Ref.make(0L)
      droppedFlush <- Ref.make(0L)
      buffer = LiveWriteBehindBuffer(queue, doorbell, droppedEnqueue, droppedFlush, config.batchSize, sink)
      // Registered before the fork so it runs after it: scope finalizers run in reverse order,
      // so the loop fiber is interrupted first and this final drain then has the queue to
      // itself. The other way round, the drain would race a loop that is still adding to it.
      _ <- ZIO.addFinalizer(buffer.flush)
      _ <- buffer.flushLoop(config.flushInterval).forkScoped
    yield buffer

  def layer: ZLayer[StoreConfig & DeferredWriteSink, Nothing, WriteBehindBuffer] =
    ZLayer.scoped:
      for
        store <- ZIO.service[StoreConfig]
        sink <- ZIO.service[DeferredWriteSink]
        buffer <- make(store.writeBehind, capacityFor(store.writeBehind), sink)
      yield buffer

  /** Sixty-four flush windows of backlog -- ~13 s at the configured 200 ms. Long enough to ride
    * out a checkpoint or a brief stall on the store, short enough that a store which is
    * genuinely not keeping up starts reporting it within a quarter of a minute rather than
    * accumulating an unbounded queue in a driver's heap.
    */
  def capacityFor(config: WriteBehindConfig): Int = config.batchSize * 64

private final class LiveWriteBehindBuffer(
    queue: Queue[DeferredUpdate],
    doorbell: Queue[Unit],
    droppedEnqueue: Ref[Long],
    droppedFlush: Ref[Long],
    batchSize: Int,
    sink: DeferredWriteSink,
) extends WriteBehindBuffer:

  override def enqueue(update: DeferredUpdate): UIO[Unit] =
    queue.offer(update).flatMap:
      case true => ringDoorbellIfFull
      case false => droppedEnqueue.update(_ + 1)

  override def flush: UIO[Unit] =
    queue.takeUpTo(batchSize).flatMap: taken =>
      if taken.isEmpty then ZIO.unit
      // A full take means the queue may hold more than one window's worth; keep going rather
      // than leaving the remainder for the next tick, which under sustained load would let the
      // queue grow to its bound and start dropping while the flusher idles between ticks.
      else writeBatch(taken) *> ZIO.when(taken.size >= batchSize)(flush).unit

  override def droppedOnEnqueue: UIO[Long] = droppedEnqueue.get

  override def droppedOnFlush: UIO[Long] = droppedFlush.get

  override def droppedTotal: UIO[Long] =
    droppedEnqueue.get.zipWith(droppedFlush.get)(_ + _)

  override def pending: UIO[Int] = queue.size

  /** One signal, coalesced (a dropping queue of one): the doorbell only has to wake the loop,
    * and a second ring while the first is unread would say nothing new.
    */
  private def ringDoorbellIfFull: UIO[Unit] =
    queue.size.flatMap(size => ZIO.when(size >= batchSize)(doorbell.offer(()))).unit

  private def writeBatch(taken: Chunk[DeferredUpdate]): UIO[Unit] =
    sink.write(DeferredBatch.coalesce(taken)).catchAllCause: cause =>
      // Deliberately not retried: the batch is superseded by whatever the same users and
      // sessions do next, so re-sending stale values behind a store that is already struggling
      // buys nothing. It is counted, because a campaign whose store dropped writes cannot be
      // reconciled afterwards and the report has to say so.
      droppedFlush.update(_ + taken.size) *>
        ZIO.logWarningCause(s"Write-behind flush of ${taken.size} updates failed; dropped", cause)

  private[store] def flushLoop(interval: Duration): UIO[Nothing] =
    // Whichever comes first: the batch-size doorbell or the interval. The loser is interrupted,
    // which is safe for both -- the doorbell carries no data, and an interrupted sleep leaves
    // nothing behind. Taking the batch itself under a race would not be: a partially collected
    // `takeBetween` loses what it has already taken, which is exactly the silent loss the
    // dropped counter exists to make visible.
    (doorbell.take.race(ZIO.sleep(interval)) *> flush).forever
