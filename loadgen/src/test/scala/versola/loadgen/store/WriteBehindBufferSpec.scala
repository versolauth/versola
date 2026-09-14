package versola.loadgen.store

import versola.loadgen.config.WriteBehindConfig
import versola.loadgen.model.{DeviceSession, VirtualUser, VirtualUserState}
import versola.loadgen.protocol.{EdgeSession, RefreshToken, SsoSession}
import versola.util.Secret
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Covers the batching, timing and drop behaviour of the deferred write path (dev spec §7.5)
  * against a recording sink, and the accounting [[PostgresDeferredWriteSink]] and the buffer do
  * between them when part of a window fails. What the sink's statements then do to Postgres is
  * `PostgresDeviceSessionRepositorySpec`'s business, not this one's.
  */
object WriteBehindBufferSpec extends ZIOSpecDefault:

  private val t0 = Instant.parse("2026-09-10T20:00:00Z")

  private final class RecordingSink(batches: Queue[DeferredBatch], failing: Ref[Boolean]) extends DeferredWriteSink:
    override def write(batch: DeferredBatch): Task[Unit] =
      failing.get.flatMap:
        case true => ZIO.fail(RuntimeException("store unavailable"))
        case false => batches.offer(batch).unit

  /** Fails exactly the sections it is given, as a real sink does when one of its three
    * statements is rejected and the other two have already committed.
    */
  private final class PartiallyFailingSink(sections: Set[DeferredSection]) extends DeferredWriteSink:
    override def write(batch: DeferredBatch): Task[Unit] =
      ZIO.fail(DeferredWriteFailed(sections, RuntimeException("events table unavailable")))

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

  /** `touchAll`/`appendAll` are the only methods the deferred path calls; the rest of each
    * repository is on the critical path and never reaches a sink.
    */
  private def unused = ZIO.dieMessage("not part of the deferred write path")

  private final class StubUsers(applied: Ref[Chunk[DeferredSection]], fails: Set[DeferredSection])
    extends VirtualUserRepository:
    override def touchAll(touches: Chunk[UserTouch]): Task[Unit] =
      record(applied, fails, DeferredSection.Users)
    override def insertAll(users: Chunk[VirtualUser]): Task[Unit] = unused
    override def find(id: Long): Task[Option[VirtualUser]] = unused
    override def loadShardSlice(shard: Int, afterId: Option[Long], limit: Int): Task[Vector[VirtualUser]] = unused
    override def markRegistered(id: Long, sutUserId: UUID): Task[Unit] = unused
    override def markBroken(id: Long): Task[Unit] = unused
    override def recordPasskey(id: Long, key: Secret, credentialId: String): Task[Unit] = unused
    override def countByState: Task[Map[VirtualUserState, Long]] = unused

  private final class StubSessions(applied: Ref[Chunk[DeferredSection]], fails: Set[DeferredSection])
    extends DeviceSessionRepository:
    override def touchAll(touches: Chunk[SessionTouch]): Task[Unit] =
      record(applied, fails, DeferredSection.Sessions)
    override def insert(session: DeviceSession): Task[Unit] = unused
    override def find(id: Long): Task[Option[DeviceSession]] = unused
    override def listByUser(userId: Long): Task[Vector[DeviceSession]] = unused
    override def listLive(shard: Int, liveAt: Instant, limit: Int): Task[Vector[DeviceSession]] = unused
    override def listInterruptedRotations(shard: Int, limit: Int): Task[Vector[DeviceSession]] = unused
    override def bumpGeneration(id: Long): Task[Option[Int]] = unused
    override def storeRotatedRefresh(
        id: Long,
        expectedGeneration: Int,
        refreshToken: RefreshToken,
        refreshExpiresAt: Instant,
        accessExpiresAt: Instant,
        acr: Option[String],
        authTime: Instant,
    ): Task[Boolean] = unused
    override def storeEdgeCookie(id: Long, cookie: EdgeSession, accessExpiresAt: Instant): Task[Unit] = unused
    override def storeStepUp(
        id: Long,
        acr: String,
        authTime: Instant,
        accessExpiresAt: Instant,
        refreshToken: Option[RefreshToken],
        refreshExpiresAt: Option[Instant],
        ssoSession: Option[SsoSession],
    ): Task[Unit] = unused
    override def delete(id: Long): Task[Unit] = unused

  private final class StubEvents(applied: Ref[Chunk[DeferredSection]], fails: Set[DeferredSection])
    extends EventRepository:
    override def appendAll(events: Chunk[EventRow]): Task[Unit] =
      record(applied, fails, DeferredSection.Events)

  private def record(
      applied: Ref[Chunk[DeferredSection]],
      fails: Set[DeferredSection],
      section: DeferredSection,
  ): Task[Unit] =
    if fails(section) then ZIO.fail(RuntimeException(s"$section unavailable"))
    else applied.update(_ :+ section)

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
    test("counts only the updates the store says it did not apply, not the whole window") {
      // The sink writes one table per section and they commit independently, so a failure on
      // the last of the three does not undo the first two. Counting the window whole would put
      // durable bookkeeping into loadgen_store_flush_dropped_total, a metric whose only job is
      // to say a campaign cannot be reconciled.
      for
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(
              WriteBehindConfig(1.hour, 100),
              1000,
              PartiallyFailingSink(Set(DeferredSection.Events)),
            )
            _ <- ZIO.foreachDiscard(1L to 3L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            _ <- buffer.enqueue(DeferredUpdate.SessionTouched(SessionTouch(1L, t0)))
            _ <- buffer.enqueue(DeferredUpdate.EventSampled(EventRow(t0, 1L, "mobile-otp", "token", "ok", 9)))
            _ <- buffer.enqueue(DeferredUpdate.EventSampled(EventRow(t0, 2L, "mobile-otp", "token", "ok", 9)))
            _ <- buffer.flush
            onFlush <- buffer.droppedOnFlush
            dropped <- buffer.droppedTotal
          yield assertTrue(onFlush == 2L, dropped == 2L)
      yield result
    },
    test("a sink that fails without naming a section is still counted whole") {
      for
        failing <- Ref.make(true)
        (_, sink) <- recordingSink(failing)
        result <- ZIO.scoped:
          for
            buffer <- WriteBehindBuffer.make(WriteBehindConfig(1.hour, 100), 1000, sink)
            _ <- ZIO.foreachDiscard(1L to 3L)(id => buffer.enqueue(DeferredUpdate.UserSeen(id, t0)))
            _ <- buffer.flush
            onFlush <- buffer.droppedOnFlush
          yield assertTrue(onFlush == 3L)
      yield result
    },
    test("capacity is sixty-four flush windows of the configured batch size") {
      assertTrue(WriteBehindBuffer.capacityFor(WriteBehindConfig(200.millis, 500)) == 32_000)
    },
    suite("PostgresDeferredWriteSink")(
      test("applies every section it can and names only the ones that failed") {
        val batch = DeferredBatch(
          Chunk(UserTouch(1L, t0)),
          Chunk(SessionTouch(1L, t0)),
          Chunk(EventRow(t0, 1L, "mobile-otp", "token", "ok", 9)),
        )
        for
          applied <- Ref.make(Chunk.empty[DeferredSection])
          fails = Set(DeferredSection.Users)
          sink = PostgresDeferredWriteSink(
            StubUsers(applied, fails),
            StubSessions(applied, fails),
            StubEvents(applied, fails),
          )
          failure <- sink.write(batch).either
          written <- applied.get
        yield assertTrue(
          // The first statement failing must not abandon the two behind it: they carry
          // unrelated bookkeeping, which is why the sink is not one transaction.
          written == Chunk(DeferredSection.Sessions, DeferredSection.Events),
          failure.left.toOption.collect { case failed: DeferredWriteFailed => failed.sections }
            .contains(Set(DeferredSection.Users)),
        )
      },
      test("names every failed section, not just the first") {
        for
          applied <- Ref.make(Chunk.empty[DeferredSection])
          fails = Set(DeferredSection.Users, DeferredSection.Events)
          sink = PostgresDeferredWriteSink(
            StubUsers(applied, fails),
            StubSessions(applied, fails),
            StubEvents(applied, fails),
          )
          failure <- sink.write(DeferredBatch.empty).either
          written <- applied.get
        yield assertTrue(
          written == Chunk(DeferredSection.Sessions),
          failure.left.toOption.collect { case failed: DeferredWriteFailed => failed.sections }
            .contains(Set(DeferredSection.Users, DeferredSection.Events)),
        )
      },
    ),
  )
