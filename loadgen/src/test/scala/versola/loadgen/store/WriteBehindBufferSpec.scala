package versola.loadgen.store

import versola.loadgen.config.WriteBehindConfig
import zio.*
import zio.test.*

import java.time.Instant

/** Covers the batching, timing and drop behaviour of the deferred write path (dev spec §7.5)
  * against a recording sink. The Postgres side of the store cannot be exercised here -- there
  * is no database in this build -- so everything below is about what the buffer decides to
  * hand the sink and when, not about what the sink then writes.
  */
object WriteBehindBufferSpec extends ZIOSpecDefault:

  private val t0 = Instant.parse("2026-09-10T20:00:00Z")

  private final class RecordingSink(batches: Queue[DeferredBatch], failing: Ref[Boolean]) extends DeferredWriteSink:
    override def write(batch: DeferredBatch): Task[Unit] =
      failing.get.flatMap:
        case true => ZIO.fail(RuntimeException("store unavailable"))
        case false => batches.offer(batch).unit

  private def recordingSink(failing: Ref[Boolean]): UIO[(Queue[DeferredBatch], DeferredWriteSink)] =
    Queue.unbounded[DeferredBatch].map(queue => (queue, RecordingSink(queue, failing)))

  /** Takes batches until `count` user touches have arrived. Blocking rather than polling: the
    * background loop and an explicit flush may split the same updates differently, and this
    * asserts on what reached the sink, not on how it was cut up.
    */
  private def takeUserTouches(batches: Queue[DeferredBatch], count: Int): UIO[Chunk[UserTouch]] =
    def loop(acc: Chunk[UserTouch]): UIO[Chunk[UserTouch]] =
      if acc.size >= count then ZIO.succeed(acc)
      else batches.take.flatMap(batch => loop(acc ++ batch.userTouches))
    loop(Chunk.empty)

  def spec = suite("WriteBehindBuffer")(
    test("holds a partial batch until the flush interval elapses, then writes it") {
      for
        failing <- Ref.make(false)
        (batches, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.second, 500), 1000, sink)
            _ <- ZIO.foreachDiscard(1L to 3L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            _ <- ZIO.yieldNow
            beforeTick <- batches.takeAll
            _ <- TestClock.adjust(1.second)
            touches <- takeUserTouches(batches, 3)
          yield assertTrue(
            beforeTick.isEmpty,
            touches.map(_.userId).toSet == Set(1L, 2L, 3L),
          )
      yield result
    },
    test("flushes as soon as batch-size updates are queued, without waiting for the interval") {
      for
        failing <- Ref.make(false)
        (batches, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            // An interval long enough that a flush within this test can only be the doorbell.
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 4), 1000, sink)
            _ <- ZIO.foreachDiscard(1L to 4L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            touches <- takeUserTouches(batches, 4)
            pending <- buffer.pending
          yield assertTrue(touches.size == 4, pending == 0)
      yield result
    },
    test("coalesces a window down to one write per user before handing it to the sink") {
      for
        failing <- Ref.make(false)
        (batches, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.second, 500), 1000, sink)
            _ <- buffer.enqueue(DeferredUpdate.UserSeen(1L, t0))
            _ <- buffer.enqueue(DeferredUpdate.UserSeen(1L, t0.plusSeconds(3)))
            _ <- TestClock.adjust(1.second)
            batch <- batches.take
          yield assertTrue(batch.userTouches == Chunk(UserTouch(1L, t0.plusSeconds(3))))
      yield result
    },
    test("drops on overflow and counts it, instead of making the caller wait") {
      for
        failing <- Ref.make(false)
        (_, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 100), 4, sink)
            // Ten enqueues into a queue of four, with no flush possible: the interval is an
            // hour away on a clock nothing advances, and the doorbell needs 100.
            _ <- ZIO.foreachDiscard(1L to 10L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            onEnqueue <- buffer.droppedOnEnqueue
            onFlush <- buffer.droppedOnFlush
            dropped <- buffer.droppedTotal
            pending <- buffer.pending
          yield assertTrue(onEnqueue == 6L, onFlush == 0L, dropped == 6L, pending == 4)
      yield result
    },
    test("counts a batch the store rejected as dropped rather than retrying it") {
      for
        failing <- Ref.make(true)
        (_, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 100), 1000, sink)
            _ <- buffer.enqueue(DeferredUpdate.UserSeen(1L, t0))
            _ <- buffer.enqueue(DeferredUpdate.UserSeen(2L, t0))
            _ <- buffer.flush
            onFlush <- buffer.droppedOnFlush
            onEnqueue <- buffer.droppedOnEnqueue
            dropped <- buffer.droppedTotal
            pending <- buffer.pending
          yield assertTrue(onFlush == 2L, onEnqueue == 0L, dropped == 2L, pending == 0)
      yield result
    },
    test("an explicit flush drains more than one batch-size window") {
      for
        failing <- Ref.make(false)
        (batches, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 2), 1000, sink)
            _ <- ZIO.foreachDiscard(1L to 5L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            _ <- buffer.flush
            touches <- takeUserTouches(batches, 5)
            pending <- buffer.pending
            dropped <- buffer.droppedTotal
          yield assertTrue(
            touches.map(_.userId).toSet == Set(1L, 2L, 3L, 4L, 5L),
            pending == 0,
            dropped == 0L,
          )
      yield result
    },
    test("drains what is still queued when its scope closes") {
      for
        failing <- Ref.make(false)
        (batches, sink) <- recordingSink(failing)
        _ <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 100), 1000, sink)
            _ <- buffer.enqueue(DeferredUpdate.EventSampled(EventRow(t0, 1L, "mobile-otp", "token", "ok", 9)))
          yield ()
        flushed <- batches.take
      yield assertTrue(flushed.events.map(_.userId) == Chunk(1L))
    },
    test("capacity is sixty-four flush windows of the configured batch size") {
      assertTrue(WriteBehindBuffer.capacityFor(WriteBehindConfig(200.millis, 500)) == 32_000)
    },
  )
