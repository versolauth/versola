package versola.edge.dpop

import zio.*
import zio.test.*

import java.time.Instant

object DpopReplayGuardSpec extends ZIOSpecDefault:

  private val Jkt = "thumbprint-1"
  private val Now = Instant.parse("2026-01-01T12:00:00Z")

  def spec = suite("DpopReplayGuard")(
    test("admits a jti once and refuses it after") {
      val guard = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
      for
        first <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        second <- guard.recordIfAbsent(Jkt, "jti-1", Now)
      yield assertTrue(first, !second)
    },
    // The pair is what has to be unique: two clients picking the same jti is allowed, and
    // one of them must not lock the other out.
    test("keeps the same jti from two keys apart") {
      val guard = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
      for
        mine <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        theirs <- guard.recordIfAbsent("thumbprint-2", "jti-1", Now)
      yield assertTrue(mine, theirs)
    },
    // A replay carries the proof's own iat, so it lands in the slot the original was filed
    // in however long after the fact it arrives.
    test("refuses a replay whose iat falls in a slot other than the current one") {
      val guard = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
      val iat = Now.minusSeconds(DpopReplayGuard.SlotWidth.toSeconds * 2)
      for
        first <- guard.recordIfAbsent(Jkt, "jti-1", iat)
        second <- guard.recordIfAbsent(Jkt, "jti-1", iat)
      yield assertTrue(first, !second)
    },
    test("holds records still inside the iat window across an eviction pass") {
      val guard = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
      val leeway = 60.seconds
      for
        _ <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        _ <- guard.evictStaleSlot(Now, leeway)
        replay <- guard.recordIfAbsent(Jkt, "jti-1", Now)
      yield assertTrue(!replay)
    },
    // Eviction only reclaims memory. A record it drops belongs to a proof whose iat is
    // already too old for `Dpop.verify` to accept, so nothing it forgets can be replayed.
    test("clears the slot that has fallen out of reach of any acceptable iat") {
      val guard = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
      val leeway = 60.seconds
      val stale = Now.minus(leeway).minusSeconds(DpopReplayGuard.SlotWidth.toSeconds)
      for
        _ <- guard.recordIfAbsent(Jkt, "jti-stale", stale)
        before <- guard.size
        _ <- guard.evictStaleSlot(Now, leeway)
        after <- guard.size
      yield assertTrue(before == 1, after == 0)
    },
    // The live layer's bound: a leeway wider than the ring can hold would let a slot be
    // reused while proofs routed to it are still acceptable, so it's refused at startup
    // rather than silently weakening replay detection.
    test("the ring can hold both directions of its own maximum leeway") {
      val lap = DpopReplayGuard.SlotWidth.multipliedBy(DpopReplayGuard.SlotCount.toLong)
      val needed = DpopReplayGuard.MaxIatLeeway.plus(DpopReplayGuard.SlotWidth).multipliedBy(2)
      assertTrue(needed.compareTo(lap) <= 0)
    },
    suite("bounding a slot's memory")(
      // What the ceiling exists to prevent: without it, this loop would grow the slot's set by
      // one entry per distinct jti forever. With it, size stops climbing once the ceiling is
      // reached, however much more traffic a slot sees.
      test("stops a slot from growing past its ceiling") {
        val guard = DpopReplayGuard.Impl(maxSlotEntries = 4)
        for
          _ <- ZIO.foreachDiscard(1 to 10)(i => guard.recordIfAbsent(Jkt, s"jti-$i", Now))
          size <- guard.size
        yield assertTrue(size == 4)
      },
      // Reaching the ceiling doesn't forget what the slot was already holding: a replay of one
      // of those entries is still caught by `contains`, not misreported as fresh.
      test("still catches a replay of an entry recorded before the slot filled") {
        val guard = DpopReplayGuard.Impl(maxSlotEntries = 2)
        for
          _ <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          _ <- guard.recordIfAbsent(Jkt, "jti-2", Now)
          replay <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        yield assertTrue(!replay)
      },
      // A digest that arrives once the slot is already full is neither recorded nor rejected
      // here -- it's the one case this ring hands to the fleet-wide record undecided, exactly
      // as it would for a proof a different pod had already seen.
      test("reports a digest it had no room left for as unseen, not as a replay") {
        val guard = DpopReplayGuard.Impl(maxSlotEntries = 1)
        for
          _ <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          overflow <- guard.recordIfAbsent(Jkt, "jti-2", Now)
          outcome <- guard.check(Jkt, "jti-2", Now)
          size <- guard.size
        yield assertTrue(
          overflow,
          // What the boolean above cannot say, and what `Shared` needs: no record was made,
          // so this ring will not catch the replay of it either.
          outcome == DpopReplayGuard.LocalOutcome.Unrecorded,
          size == 1,
        )
      },
      // Reading a slot's size and adding to it are two operations, so a ceiling checked that
      // way is only a ceiling for one caller at a time: a burst of distinct proofs all read
      // the same under-ceiling size and all add. This is the case that would overshoot.
      test("holds the ceiling when far more proofs arrive at once than the slot can hold") {
        val ceiling = 50
        val guard = DpopReplayGuard.Impl(maxSlotEntries = ceiling)
        for
          _ <- ZIO.foreachParDiscard(1 to 1000)(i => guard.recordIfAbsent(Jkt, s"jti-$i", Now))
            .withParallelism(64)
          size <- guard.size
        yield assertTrue(size == ceiling)
      },
      // A replay recorded while the slot still had room stays catchable once it fills, however
      // much unrelated traffic arrived in between -- the reservation released for it is space
      // for a *different* digest, not a hole in the record of this one.
      test("keeps rejecting a replay while the slot it sits in is under concurrent pressure") {
        val guard = DpopReplayGuard.Impl(maxSlotEntries = 10)
        for
          first <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          _ <- ZIO.foreachParDiscard(1 to 200)(i => guard.recordIfAbsent(Jkt, s"other-$i", Now))
            .withParallelism(32)
          replay <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          size <- guard.size
        yield assertTrue(first, !replay, size == 10)
      },
      // Eviction hands a slot's space back as well as its entries. Without that the ceiling
      // would be permanent rather than per-window: a slot that filled once would stay full
      // forever, refusing to record anything again after its first clear.
      test("records again at full rate once a filled slot has been cleared") {
        val guard = DpopReplayGuard.Impl(maxSlotEntries = 2)
        val leeway = 60.seconds
        val stale = Now.minus(leeway).minusSeconds(DpopReplayGuard.SlotWidth.toSeconds)
        for
          _ <- ZIO.foreachDiscard(1 to 5)(i => guard.recordIfAbsent(Jkt, s"jti-$i", stale))
          filled <- guard.size
          _ <- guard.evictStaleSlot(Now, leeway)
          afterEviction <- guard.check(Jkt, "jti-later", stale)
          size <- guard.size
        yield assertTrue(filled == 2, afterEviction == DpopReplayGuard.LocalOutcome.Fresh, size == 1)
      },
    ),
    suite("in front of the fleet-wide record")(
      // A local hit is already conclusive -- records are only ever added -- so the round trip
      // is skipped. This is the only traffic the local ring saves the database: a fresh proof
      // carries a fresh jti, so every legitimate request misses it and is asked about below.
      test("answers a replay it has seen itself without consulting the shared record") {
        val repository = CountingRepository(answer = ZIO.succeed(true))
        val guard = DpopReplayGuard.Shared(DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries), repository)
        for
          first <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          second <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          consulted <- repository.calls
        yield assertTrue(first, !second, consulted == 1)
      },
      // The case the whole change exists for: this pod has never seen the proof, another one
      // has, and only the shared record knows.
      test("rejects a proof another replica recorded, which its own ring admits") {
        val repository = CountingRepository(answer = ZIO.succeed(false))
        val local = DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries)
        val guard = DpopReplayGuard.Shared(local, repository)
        for
          shared <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          locally <- local.recordIfAbsent(Jkt, "jti-2", Now)
        yield assertTrue(!shared, locally)
      },
      // Fail-degraded. The fallback is the local ring's answer, which is what this guard
      // would have returned before the shared record existed -- an outage cannot make the
      // check worse than not having built it, and must not fail the request either.
      test("falls back to its own ring when the shared record is unreachable") {
        val repository = CountingRepository(answer = ZIO.fail(RuntimeException("no route to host")))
        val guard = DpopReplayGuard.Shared(DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries), repository)
        for
          first <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          replay <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        yield assertTrue(first, !replay)
      },
      // The one case the fallback has no ring to fall back to. A full slot records nothing, so
      // a proof that lands in one is unknown to this pod now and will still be unknown when it
      // is replayed -- with the shared record also down, admitting it admits every replay of it
      // too. Refusing is the only answer that does not depend on a record nobody holds.
      test("refuses a proof it could not record when the shared record is unreachable too") {
        val repository = CountingRepository(answer = ZIO.fail(RuntimeException("no route to host")))
        val guard = DpopReplayGuard.Shared(DpopReplayGuard.Impl(maxSlotEntries = 1), repository)
        for
          recorded <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          overflow <- guard.recordIfAbsent(Jkt, "jti-2", Now)
          overflowReplay <- guard.recordIfAbsent(Jkt, "jti-2", Now)
        yield assertTrue(
          // A proof the ring did record still rides out the outage on the local answer.
          recorded,
          !overflow,
          !overflowReplay,
        )
      },
      // Only the outage turns an unrecorded proof into a refusal. While the fleet-wide record
      // answers, a full local slot costs the round trip it was meant to save and nothing else.
      test("lets the shared record settle a proof the local ring had no room for") {
        val repository = CountingRepository(answer = ZIO.succeed(true))
        val guard = DpopReplayGuard.Shared(DpopReplayGuard.Impl(maxSlotEntries = 1), repository)
        for
          _ <- guard.recordIfAbsent(Jkt, "jti-1", Now)
          overflow <- guard.recordIfAbsent(Jkt, "jti-2", Now)
          consulted <- repository.calls
        yield assertTrue(overflow, consulted == 2)
      },
      // Every unreachable-record case here logs the warning the metric is paired with, which
      // is the behaviour under test rather than noise worth printing.
    ) @@ TestAspect.silentLogging,
  )

  private class CountingRepository(answer: Task[Boolean]) extends DpopProofRepository:
    private val consulted = java.util.concurrent.atomic.AtomicInteger(0)

    def calls: UIO[Int] = ZIO.succeed(consulted.get)

    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): Task[Boolean] =
      ZIO.succeed(consulted.incrementAndGet()) *> answer
