package versola.edge.dpop

import zio.*
import zio.test.*

import java.time.Instant

object DpopReplayGuardSpec extends ZIOSpecDefault:

  private val Jkt = "thumbprint-1"
  private val Now = Instant.parse("2026-01-01T12:00:00Z")

  def spec = suite("DpopReplayGuard")(
    test("admits a jti once and refuses it after") {
      val guard = DpopReplayGuard.Impl()
      for
        first <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        second <- guard.recordIfAbsent(Jkt, "jti-1", Now)
      yield assertTrue(first, !second)
    },
    // The pair is what has to be unique: two clients picking the same jti is allowed, and
    // one of them must not lock the other out.
    test("keeps the same jti from two keys apart") {
      val guard = DpopReplayGuard.Impl()
      for
        mine <- guard.recordIfAbsent(Jkt, "jti-1", Now)
        theirs <- guard.recordIfAbsent("thumbprint-2", "jti-1", Now)
      yield assertTrue(mine, theirs)
    },
    // A replay carries the proof's own iat, so it lands in the slot the original was filed
    // in however long after the fact it arrives.
    test("refuses a replay whose iat falls in a slot other than the current one") {
      val guard = DpopReplayGuard.Impl()
      val iat = Now.minusSeconds(DpopReplayGuard.SlotWidth.toSeconds * 2)
      for
        first <- guard.recordIfAbsent(Jkt, "jti-1", iat)
        second <- guard.recordIfAbsent(Jkt, "jti-1", iat)
      yield assertTrue(first, !second)
    },
    test("holds records still inside the iat window across an eviction pass") {
      val guard = DpopReplayGuard.Impl()
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
      val guard = DpopReplayGuard.Impl()
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
  )
