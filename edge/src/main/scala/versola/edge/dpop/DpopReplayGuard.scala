package versola.edge.dpop

import org.apache.commons.codec.digest.Blake3
import versola.edge.EdgeConfig
import zio.{Clock, Duration, Schedule, Scope, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** RFC 9449 §4.3 step 12 / §11.1: rejects a proof whose `jti` has already been seen inside the
  * `iat` window.
  *
  * The in-memory ring below was the first cut, and on a single instance it is correct. Edge
  * runs as a fleet with no session affinity, so on its own it answers "have I seen this?"
  * rather than "has anyone?": the same proof sent to two replicas inside the window misses
  * both rings and is admitted twice, for an exposure of `2 * iat-leeway`. §11.1 names that
  * situation exactly -- a strict single-use check "may not always be feasible in practice,
  * e.g., when multiple servers behind a single endpoint have no shared state" -- and the
  * conclusion drawn here is to give the servers shared state, not to accept the gap.
  *
  * [[DpopReplayGuard.Shared]] is therefore what runs in production: the local ring first, and
  * [[DpopProofRepository]] behind it for everything the local ring cannot answer. The local
  * ring is not a cache of the shared one -- a fresh proof carries a fresh `jti`, so legitimate
  * traffic misses it by construction -- it is the part that keeps working when the database
  * does not, and it removes the round trip for a replay that lands back on the pod that saw
  * the original.
  *
  * The two rings keep different geometries on purpose: this one is written and evicted by the
  * same pod, so one clock governs both admission and eviction, while the shared ring is
  * evicted by whichever replica's timer fires first and has to hold each slot open across the
  * tolerated skew. That is the whole difference between 8 slots here and 12 there, and
  * unifying the constants would silently reopen the cross-replica gap.
  */
trait DpopReplayGuard:
  /** True when this `(jkt, jti)` had not been seen; false when it is a replay. */
  def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean]

object DpopReplayGuard:

  /** Ring geometry. A proof is filed under the slot its own `iat` falls in, so a slot can be
    * discarded wholesale once everything it could hold has left the `iat` window -- the same
    * shape as auth's `dpop_proofs` partition ring, for the same reason: expiry becomes
    * dropping a bucket rather than scanning for individual entries.
    */
  val SlotCount = 8
  val SlotWidth: Duration = Duration.fromSeconds(30)

  /** The widest `iat` leeway this geometry can hold without a slot being reused while proofs
    * routed to it are still acceptable. Both directions of the leeway have to fit, plus the
    * arbitrary slot phase either end of that span sits at, inside one lap:
    * `2 * leeway + 2 * SlotWidth <= SlotCount * SlotWidth`.
    *
    * Auth's `dpop_proofs` ring budgets a further `4 * MaxClockSkew` here, because the
    * instance accepting a proof and the instance evicting its slot are different instances
    * on different clocks. This ring needs no such margin -- see below -- which is why 8
    * slots carry the same 90s leeway auth needs 12 for.
    */
  val MaxIatLeeway: Duration = Duration.fromSeconds(
    (SlotCount * SlotWidth.toSeconds - 2 * SlotWidth.toSeconds) / 2,
  )

  /** Per-slot ceiling on how many distinct `(jkt, jti)` digests a slot will hold.
    *
    * Nothing else here bounds slot size: a slot is cleared as a whole once it ages out, not
    * as entries are added, so its memory otherwise grows with however many distinct proofs
    * land in it before that clear -- request volume the guard has no say over. This is the
    * backstop against that, independent of the RFC 9449 §11.1 guidance already followed for
    * per-entry cost (a fixed-width hash, not the `jti` itself).
    *
    * Reaching it does not weaken the check, only its fast path: past the ceiling, [[Impl]]
    * stops adding new digests to that slot and answers as if it had not seen them (see
    * `recordIfAbsent`), which is exactly what it would answer about a proof a *different* pod
    * had recorded -- [[Shared]] already has to treat that as inconclusive and confirm against
    * the fleet-wide record. A sustained non-zero [[DpopMetrics.localRingAtCapacity]] means
    * that path is being taken for real traffic, not that anything is being admitted wrongly.
    */
  val MaxSlotEntries = 100000

  /** The only wiring offered: the local ring is not a guard on its own anywhere a fleet serves
    * the traffic, so it is not exposed as one. [[Impl]] stays public for the tests that pin
    * down its own behaviour.
    */
  def shared: ZLayer[EdgeConfig & DpopProofRepository & Scope, IllegalArgumentException, DpopReplayGuard] =
    ZLayer.fromZIO:
      for
        config <- ZIO.serviceWith[EdgeConfig](_.dpop)
        iatLeeway = config.map(_.iatLeeway).getOrElse(Duration.fromSeconds(60))
        _ <- ZIO.fail(
          IllegalArgumentException(
            s"dpop.iat-leeway of $iatLeeway exceeds $MaxIatLeeway, the longest window the " +
              s"$SlotCount-slot replay guard can hold without reusing a slot that still " +
              "admits proofs",
          ),
          // Compared whole rather than in seconds: truncating would admit a leeway of 90.5s
          // under a 90s bound, which is the one thing this guard's geometry depends on.
        ).when(iatLeeway.compareTo(MaxIatLeeway) > 0)
        local = Impl()
        _ <- Clock.instant
          .flatMap(now => local.evictStaleSlot(now, iatLeeway))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
        repository <- ZIO.service[DpopProofRepository]
      yield Shared(local, repository)

  /** Which slot a proof created at `iat` belongs to. Laps the ring, so two proofs a full lap
    * apart share a slot -- by then the earlier one's slot has been cleared.
    */
  private def slotOf(iat: Instant): Int =
    Math.floorMod(Math.floorDiv(iat.getEpochSecond, SlotWidth.toSeconds), SlotCount.toLong).toInt

  /** §11.1 recommends storing only a hash of the `jti`, which also bounds what an oversized
    * `jti` can cost. The `jkt` is folded in so the pair, not the `jti` alone, has to be
    * unique -- two clients are free to pick the same `jti`.
    */
  private def digestOf(jkt: String, jti: String): List[Byte] =
    val digest = Array.ofDim[Byte](16)
    Blake3.initHash()
      .update(jkt.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(jti.getBytes(StandardCharsets.UTF_8))
      .doFinalize(digest)
    digest.toList

  /** The local ring first, then the fleet-wide record for anything it could not settle alone.
    *
    * The order is what makes the shared write skippable: a local hit is already proof of a
    * replay -- records are only ever added -- so it is rejected without a round trip. A local
    * miss proves nothing, since the original may have been seen by a different pod, and is the
    * only case the database is asked about. For legitimate traffic that is every request, each
    * proof carrying a `jti` no pod has seen.
    *
    * Fail-degraded when the shared record is unreachable: the local ring's answer stands. That
    * reopens the cross-replica window for as long as the outage lasts, which is the same
    * exposure the local ring carried on its own, so an outage can never make this worse than
    * not having built it -- but it is invisible unless [[DpopMetrics.sharedRingUnavailable]]
    * is alerted on, not merely collected.
    */
  class Shared(local: Impl, repository: DpopProofRepository) extends DpopReplayGuard:
    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean] =
      local.recordIfAbsent(jkt, jti, iat).flatMap:
        case false => ZIO.succeed(false)
        case true =>
          repository.recordIfAbsent(jkt, jti, iat)
            .catchAllCause: cause =>
              ZIO.logWarningCause("dpop replay guard fell back to its local ring", cause)
                *> DpopMetrics.sharedRingUnavailable
                *> ZIO.succeed(true)

  /** @param maxSlotEntries overridable only for tests that need to reach the ceiling without
    *   actually filling a slot with [[MaxSlotEntries]] digests; production wiring always takes
    *   the default.
    */
  class Impl(maxSlotEntries: Int = MaxSlotEntries) extends DpopReplayGuard:
    private val slots: Array[java.util.Set[List[Byte]]] =
      Array.fill(SlotCount)(ConcurrentHashMap.newKeySet[List[Byte]]())

    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean] =
      val slot = slots(slotOf(iat))
      val digest = digestOf(jkt, jti)
      // No expiry is compared here. A slot is only cleared once everything it holds has left
      // the `iat` window, so a proof whose record is gone has already been rejected by the
      // `iat` check in `Dpop.verify` before reaching this point.
      if slot.size() < maxSlotEntries then ZIO.succeed(slot.add(digest))
      else if slot.contains(digest) then ZIO.succeed(false)
      else
        // Below the ceiling this never runs; at it, growing the slot further is refused, and
        // this digest is left for the fleet-wide record to settle rather than misreported as
        // fresh here. `contains` above still catches a replay of anything the slot was already
        // holding when it filled -- the ceiling stops the slot from growing, it doesn't forget
        // what's already in it.
        DpopMetrics.localRingAtCapacity *> ZIO.succeed(true)

    /** Clears the slot that has just fallen out of reach of any acceptable `iat`.
      *
      * Only running early is dangerous: dropping the record of a proof still inside the window
      * would let that proof be replayed. Running late costs memory and nothing else, since a
      * record left behind can only ever reject, never admit.
      */
    def evictStaleSlot(now: Instant, iatLeeway: Duration): UIO[Unit] =
      ZIO.succeed:
        slots(slotOf(now.minus(iatLeeway).minusSeconds(SlotWidth.toSeconds))).clear()

    /** Total records held, for the metric that sizes the window. */
    def size: UIO[Int] = ZIO.succeed(slots.map(_.size).sum)
