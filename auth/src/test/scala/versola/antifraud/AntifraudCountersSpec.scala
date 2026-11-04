package versola.antifraud

import versola.util.UnitSpecBase
import versola.util.audit.AuditEvent
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudCountersSpec extends UnitSpecBase:

  import VelocityCountersSpec.{instance, repository, rounds}
  import VelocityKey.*
  import VelocityWindow.*

  private val t0 = Instant.parse("2026-11-02T10:00:00Z")
  private val on = AntifraudTestSettings.velocity.copy(enabled = true)
  private val limits = AntifraudTestSettings.velocityLimits.copy(shareFrom = 1)
  private val pollInterval = 5.seconds
  // What the layer makes of it: two polls, a load interval and a flush interval.
  private val settleAfter = pollInterval * 2 + on.loadInterval + on.flushInterval

  // What RecordingAntifraudEvents makes of a number.
  private val number = "+77011234567"
  private val numberKey = s"hash:$number"

  /** The log of resets, in memory. */
  private class InMemoryResets(rows: Ref[List[CounterReset]]) extends AntifraudCounterResetRepository:
    override def record(reset: CounterReset.Resolved, requestedBy: String, at: Instant, audit: AuditEvent): Task[Long] =
      rows.modify: all =>
        val generation = all.size + 1L
        (generation, all :+ CounterReset(generation, reset.scope, reset.valueType, reset.valueHash, reset.destPrefix, requestedBy, at))

    override def latest: Task[Long] = rows.get.map(_.size.toLong)

    override def since(generation: Long, limit: Int): Task[List[CounterReset]] = rows.get.map(_.filter(_.generation > generation).take(limit))

    def all: UIO[List[CounterReset]] = rows.get

  private val resets: UIO[InMemoryResets] = Ref.make(List.empty[CounterReset]).map(InMemoryResets(_))

  /** One instance: its counters, its own events (for the budget) and the reset service over the shared state. */
  private def node(shared: VelocityRepository, log: AntifraudCounterResetRepository) =
    for
      counters <- instance(shared, on, limits)
      events = RecordingAntifraudEvents()
      service = AntifraudCounters.Impl(log, shared, counters, events, pollInterval, settleAfter)
      // Where the log stands now: what is reset from here on is what this instance applies.
      _ <- service.pollOnce
    yield (counters, events, service)

  def spec = suite("AntifraudCounters")(
    test("a reset reaches every instance: memory and rows are forgotten, and counting goes on from nothing") {
      for
        (repo, _) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, _, serviceA) <- node(repo, log)
        (b, _, serviceB) <- node(repo, log)
        _ <- ZIO.succeed:
          (1 to 7).foreach(_ => a.record(phone, numberKey, t0))
          (1 to 5).foreach(_ => b.record(phone, numberKey, t0))
          (1 to 3).foreach(_ => a.record(ip, "net", t0))
        _ <- rounds(6)
        before = List(a, b).map(_.count(phone, numberKey, TenMinutes, t0))
        generation <- serviceA.reset(CounterScope.velocity, Some(CounterValueType.phone), Some(number), "alice")
        // The instance that took the request has forgotten at once; the other has not yet.
        justAfter = List(a, b).map(_.count(phone, numberKey, TenMinutes, t0))
        _ <- serviceB.pollOnce
        applied = List(a, b).map(_.count(phone, numberKey, TenMinutes, t0))
        // Whatever either flushes next must not bring the count back.
        _ <- rounds(6)
        settled = List(a, b).map(_.count(phone, numberKey, TenMinutes, t0))
        // The other keys are left alone.
        others = List(a, b).map(_.count(ip, "net", TenMinutes, t0))
        stored <- repo.all
        // And the number is counted again from nothing.
        _ <- ZIO.succeed((1 to 2).foreach(_ => a.record(phone, numberKey, t0)))
        _ <- rounds(6)
        again = List(a, b).map(_.count(phone, numberKey, TenMinutes, t0))
        log <- log.all
      yield assertTrue(
        before == List(12, 12),
        generation == 1L,
        // The other instance still holds its own 5 and the 7 it loaded of the first.
        justAfter == List(0, 12),
        applied == List(0, 0),
        settled == List(0, 0),
        others == List(3, 3),
        stored.keys.forall(_._3 != numberKey),
        stored.keys.exists(_._3 == "net"),
        again == List(2, 2),
        log.map(r => (r.generation, r.scope, r.valueType, r.requestedBy)) ==
          List((1L, CounterScope.velocity, Some(CounterValueType.phone), "alice")),
      )
    },
    test("an instance that wrote its counts to the database after the reset was asked for has them forgotten when it applies it") {
      for
        (repo, _) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, _, serviceA) <- node(repo, log)
        (b, _, serviceB) <- node(repo, log)
        _ <- ZIO.succeed((1 to 5).foreach(_ => b.record(phone, numberKey, t0)))
        _ <- serviceA.reset(CounterScope.velocity, Some(CounterValueType.phone), Some(number), "alice")
        // b flushes before it has read the reset: the row is back, and a loads it.
        _ <- rounds(4)
        resurrected = a.count(phone, numberKey, TenMinutes, t0)
        // b applies it: its memory and its own rows go, and a forgets what it loaded once more after a settle time.
        _ <- serviceB.pollOnce
        _ <- TestClock.adjust(settleAfter)
        _ <- serviceA.pollOnce
        _ <- rounds(4)
        left <- repo.all
      yield assertTrue(resurrected == 5, a.count(phone, numberKey, TenMinutes, t0) == 0, b.count(phone, numberKey, TenMinutes, t0) == 0, left.isEmpty)
    },
    test("the second pass waits for a flush and a load after the reset, not for two polls alone") {
      for
        (repo, _) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, _, serviceA) <- node(repo, log)
        (b, _, serviceB) <- node(repo, log)
        _ <- ZIO.succeed((1 to 5).foreach(_ => b.record(phone, numberKey, t0)))
        _ <- serviceA.reset(CounterScope.velocity, Some(CounterValueType.phone), Some(number), "alice")
        // b flushes before it has read the reset, and a loads what it flushed.
        _ <- rounds(3)
        loaded = a.count(phone, numberKey, TenMinutes, t0)
        // Two polls on (the old moment of the second pass) it has not run: the loaded rows are still held.
        _ <- serviceA.pollOnce
        stillHeld = a.count(phone, numberKey, TenMinutes, t0)
        // b applies the reset and deletes its rows; once the settle time has passed a forgets what it loaded.
        _ <- serviceB.pollOnce
        _ <- TestClock.adjust(settleAfter)
        _ <- serviceA.pollOnce
      yield assertTrue(pollInterval * 2 < on.loadInterval, loaded == 5, stillHeld == 5, a.count(phone, numberKey, TenMinutes, t0) == 0)
    },
    test("the whole scope, an address, a range of numbers and the budget are each reset as asked") {
      for
        (repo, _) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, eventsA, service) <- node(repo, log)
        _ <- ZIO.succeed:
          a.record(ip, "x", t0)
          a.record(prefix, "tenant/491512", t0)
          a.record(prefix, "tenant/337000", t0)
          a.record(phone, "other", t0)
        _ <- service.reset(CounterScope.velocity, Some(CounterValueType.prefix), Some("4915"), "alice")
        afterRange = (a.count(prefix, "tenant/491512", OneDay, t0), a.count(prefix, "tenant/337000", OneDay, t0))
        // A phone touches the phone's velocity key and never the budget: the range is reopened by its prefix.
        _ <- service.reset(CounterScope.all, Some(CounterValueType.phone), Some(number), "alice")
        _ <- service.reset(CounterScope.budget, Some(CounterValueType.prefix), Some("4915"), "alice")
        _ <- service.reset(CounterScope.budget, None, None, "alice")
        // An address has no budget, so for scope all only the velocity counters are reset.
        _ <- service.reset(CounterScope.all, Some(CounterValueType.ip), Some("203.0.113.7"), "alice")
        afterIp = a.count(ip, "x", OneDay, t0)
        _ <- service.reset(CounterScope.all, None, None, "alice")
        afterAll = (a.size, a.count(phone, "other", OneDay, t0))
        recorded <- log.all
      yield assertTrue(
        afterRange == (0, 1),
        // 203.0.113.7 is another network than the key "x", which is not an address at all: nothing to forget there.
        afterIp == 1,
        afterAll == (0, 0),
        eventsA.budgetResetCalls == List(Some("4915"), None, None),
        recorded.map(_.generation) == List(1L, 2L, 3L, 4L, 5L, 6L),
        recorded(1).destPrefix.isEmpty,
        recorded(2).destPrefix.contains("4915"),
      )
    },
    test("a request that is not valid is refused and records nothing") {
      for
        (repo, _) <- repository
        log <- resets
        (_, _, service) <- node(repo, log)
        results <- ZIO.foreach(
          List(
            (CounterScope.all, Some(CounterValueType.phone), None),
            (CounterScope.all, None, Some("4915")),
            (CounterScope.velocity, Some(CounterValueType.phone), Some("not a number")),
            // a phone has no budget of its own
            (CounterScope.budget, Some(CounterValueType.phone), Some(number)),
            (CounterScope.velocity, Some(CounterValueType.ip), Some("example.com")),
            (CounterScope.velocity, Some(CounterValueType.prefix), Some("49151299")),
            (CounterScope.velocity, Some(CounterValueType.prefix), Some("49%")),
            (CounterScope.budget, Some(CounterValueType.ip), Some("203.0.113.7")),
            (CounterScope.velocity, Some(CounterValueType.prefix), Some("   ")),
          ),
        )((scope, valueType, value) => service.reset(scope, valueType, value, "alice").exit)
        recorded <- log.all
      yield assertTrue(
        results.forall(_.causeOption.exists(_.squash.isInstanceOf[InvalidCounterReset])),
        recorded.isEmpty,
      )
    },
    test("a reset made before an instance started is not applied by it, and one made after is, once") {
      for
        (repo, _) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, _, serviceA) <- node(repo, log)
        _ <- serviceA.reset(CounterScope.all, None, None, "alice")
        (b, _, serviceB) <- node(repo, log)
        _ <- ZIO.succeed(b.record(ip, "k", t0))
        _ <- serviceB.pollOnce
        beforeSecond = b.count(ip, "k", OneDay, t0)
        _ <- serviceA.reset(CounterScope.all, None, None, "alice")
        _ <- serviceB.pollOnce
        afterSecond = b.count(ip, "k", OneDay, t0)
        _ <- ZIO.succeed(b.record(ip, "k", t0))
        _ <- serviceB.pollOnce
        afterNothingNew = b.count(ip, "k", OneDay, t0)
      yield assertTrue(beforeSecond == 1, afterSecond == 0, afterNothingNew == 1)
    },
    test("rows that cannot be deleted do not stop the memory from being forgotten") {
      for
        (repo, failing) <- repository
        log <- resets
        _ <- TestClock.setTime(t0)
        (a, _, serviceA) <- node(repo, log)
        (b, _, serviceB) <- node(repo, log)
        _ <- ZIO.succeed(b.record(ip, "k", t0))
        _ <- serviceA.reset(CounterScope.all, None, None, "alice")
        // The reset log itself is up; deleting the rows of the velocity table fails, and is only counted and logged.
        _ <- failing.set(true)
        _ <- serviceB.pollOnce
        applied = b.count(ip, "k", OneDay, t0)
      yield assertTrue(applied == 0)
    },
  ) @@ TestAspect.sequential @@ TestAspect.silentLogging
