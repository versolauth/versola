package versola.edge.dpop

import org.apache.commons.codec.digest.Blake3
import versola.edge.EdgeConfig
import zio.{Clock, Duration, Schedule, Scope, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

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
    * stops adding new digests to that slot and reports them [[LocalOutcome.Unrecorded]],
    * which is exactly the answer it gives about a proof a *different* pod recorded --
    * [[Shared]] already has to treat that as inconclusive and settle it against the
    * fleet-wide record. A sustained non-zero [[DpopMetrics.localRingAtCapacity]] means that
    * path is being taken for real traffic, not that anything is being admitted wrongly.
    */
  val MaxSlotEntries = 100000

  /** What the local ring alone can establish about a digest.
    *
    * The distinction [[Shared]] turns on is between [[Fresh]] and [[Unrecorded]]: both mean
    * "no record of this here", but only [[Fresh]] means one has now been made. An
    * [[Unrecorded]] digest leaves the local ring with no memory of the proof at all, so this
    * ring cannot catch its replay however many times it comes back -- collapsing the two into
    * one boolean is what let an overflow digest be admitted repeatedly on a single pod while
    * the fleet-wide record was unreachable.
    */
  enum LocalOutcome:
    case Fresh, Replay, Unrecorded

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
        local = Impl(MaxSlotEntries)
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
    *
    * Falling back needs the local ring to have an answer to fall back *to*, which an
    * [[LocalOutcome.Unrecorded]] digest is precisely the case it does not: the slot was full,
    * so nothing was stored and no replay of that proof will ever hit this ring either. With
    * the fleet-wide record down as well, admitting it would admit every replay of it too, on
    * this very pod, for as long as both conditions hold. So that one combination -- a slot at
    * [[MaxSlotEntries]] *and* an unreachable database -- fails closed instead. It costs a
    * genuine first-time proof a rejection in a state where nothing can vouch for it, which is
    * the side to err on for a replay check, and the client's retry succeeds as soon as either
    * condition clears.
    */
  class Shared(local: Impl, repository: DpopProofRepository) extends DpopReplayGuard:
    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean] =
      local.check(jkt, jti, iat).flatMap:
        case LocalOutcome.Replay => ZIO.succeed(false)
        case outcome =>
          repository.recordIfAbsent(jkt, jti, iat)
            .catchAllCause: cause =>
              ZIO.logWarningCause("dpop replay guard fell back to its local ring", cause)
                *> DpopMetrics.sharedRingUnavailable
                *> ZIO.succeed(outcome == LocalOutcome.Fresh)

  /** @param maxSlotEntries what each slot is held to, [[MaxSlotEntries]] everywhere but the
    *   tests that need to reach the ceiling without first filling a slot with a hundred
    *   thousand digests.
    */
  class Impl(maxSlotEntries: Int) extends DpopReplayGuard:
    private val slots: Array[java.util.Set[List[Byte]]] =
      Array.fill(SlotCount)(ConcurrentHashMap.newKeySet[List[Byte]]())

    /** Reserved size per slot, which is what [[maxSlotEntries]] is actually enforced against.
      * A slot's own `size()` cannot be: reading it and adding are two operations, so under
      * concurrency every caller in a burst reads the same under-ceiling size and adds, and the
      * ceiling is overshot by however many happened to be in flight. Claiming the space before
      * taking it makes the bound hold whatever the concurrency.
      */
    private val reserved: Array[AtomicInteger] = Array.fill(SlotCount)(AtomicInteger(0))

    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean] =
      check(jkt, jti, iat).map(_ != LocalOutcome.Replay)

    /** What this ring can establish about the proof on its own, recording it when it has room.
      *
      * [[recordIfAbsent]] flattens this to the trait's boolean, which reads an
      * [[LocalOutcome.Unrecorded]] digest as admitted -- correct for a ring used on its own,
      * where there is nothing else to ask. [[Shared]] has somewhere to ask and so distinguishes
      * the two.
      */
    def check(jkt: String, jti: String, iat: Instant): UIO[LocalOutcome] =
      val index = slotOf(iat)
      val slot = slots(index)
      val digest = digestOf(jkt, jti)
      // No expiry is compared here. A slot is only cleared once everything it holds has left
      // the `iat` window, so a proof whose record is gone has already been rejected by the
      // `iat` check in `Dpop.verify` before reaching this point.
      if reserve(index) then
        if slot.add(digest) then ZIO.succeed(LocalOutcome.Fresh)
        else
          // A digest already held costs no space, so the reservation goes back. Until it does,
          // the slot is one entry short of its ceiling rather than one over it.
          release(index)
          ZIO.succeed(LocalOutcome.Replay)
      // Below the ceiling neither of these runs. At it, the slot stops growing but does not
      // forget: a replay of anything it was already holding is still caught here, and only a
      // digest it has no room to learn is handed on undecided.
      else if slot.contains(digest) then ZIO.succeed(LocalOutcome.Replay)
      else DpopMetrics.localRingAtCapacity.as(LocalOutcome.Unrecorded)

    /** Claims one entry's worth of space in a slot, or refuses at the ceiling. */
    @tailrec private def reserve(index: Int): Boolean =
      val held = reserved(index).get()
      if held >= maxSlotEntries then false
      else if reserved(index).compareAndSet(held, held + 1) then true
      else reserve(index)

    private def release(index: Int): Unit =
      reserved(index).decrementAndGet()
      ()

    /** Clears the slot that has just fallen out of reach of any acceptable `iat`.
      *
      * Only running early is dangerous: dropping the record of a proof still inside the window
      * would let that proof be replayed. Running late costs memory and nothing else, since a
      * record left behind can only ever reject, never admit.
      */
    def evictStaleSlot(now: Instant, iatLeeway: Duration): UIO[Unit] =
      ZIO.succeed:
        val index = slotOf(now.minus(iatLeeway).minusSeconds(SlotWidth.toSeconds))
        slots(index).clear()
        // Nothing can be routed to this slot while it is being cleared -- its `iat`s are past
        // the acceptance window `Dpop.verify` applies before this ring is reached -- so the
        // reservation count is settled from what the clear actually left behind rather than
        // assumed to be zero.
        reserved(index).set(slots(index).size())

    /** Total records held, for the metric that sizes the window. */
    def size: UIO[Int] = ZIO.succeed(slots.map(_.size).sum)
