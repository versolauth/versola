package versola.oauth.dpop

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import org.apache.commons.codec.digest.Blake3
import versola.util.postgres.BasicCodecs
import versola.util.{CoreConfig, EdgeAssertion}
import zio.{Clock, Duration, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Records proofs into a fixed ring of partitions keyed by the proof's own `iat`, so that expiry
  * is a truncate of a whole slot rather than a delete per proof. See
  * `V0014__dpop_proofs_table.sql` for why the slot is derived from `iat` and not from arrival
  * time.
  */
class PostgresDpopProofRepository(xa: TransactorZIO) extends DpopProofRepository, BasicCodecs:
  import PostgresDpopProofRepository.{EvictionLockTimeout, MaxClockSkew, SlotWidth, digestOf, slotOf}

  override def recordIfAbsent(jkt: String, jti: String, iat: Instant): Task[Boolean] =
    xa.connectMeasured("record-dpop-proof-if-absent"):
      // No expiry is compared here. A slot is only reclaimed once everything it holds has left
      // the `iat` window, so a proof whose record is gone has already been rejected by
      // `Dpop.verify` before reaching this point -- a surviving record can never be stale.
      sql"""
        INSERT INTO dpop_proofs (slot, digest)
        VALUES (${slotOf(iat)}, ${digestOf(jkt, jti)})
        ON CONFLICT (slot, digest) DO NOTHING
        RETURNING 1
      """.query[Int].run()
        .nonEmpty

  /** Truncates the one slot that can no longer hold an acceptable proof, reclaiming its space
    * before the ring wraps around onto it.
    *
    * Only running early is dangerous: dropping the record of a proof still inside the `iat`
    * window would let that proof be replayed. Running late costs disk and nothing else, since a
    * record left behind can only ever reject, never admit. That asymmetry is why this is a
    * background job whose failure is logged rather than something the request path depends on.
    *
    * Every instance runs it, but each picks its slot from *its own* clock, not a shared one --
    * so the anchor is pushed a further `2 * MaxClockSkew` into the past. Without that, an
    * instance running ahead of its peers truncates at the exact instant its own acceptance
    * window ends, which at an unlucky slot phase is still inside a slower peer's window: the
    * proofs in that slot would be replayable against the slower instance. Twice the skew
    * because the two clocks can be off in opposite directions.
    *
    * The redundant runs find the slot already empty, and none of them contend with the request
    * path: the slot being truncated is, by the bound on `MaxIatLeeway`, never one that a proof
    * arriving at any instance could be routed to. They do contend with *each other* -- every
    * instance targets the same partition in the same tick -- but that's a single-table
    * `TRUNCATE` queuing behind others of the same kind, measured at low milliseconds even
    * under a full fleet.
    *
    * `lock_timeout` bounds the one case that isn't cheap: something other than a peer's
    * routine truncate holding a conflicting lock on this partition (a stuck backend, a manual
    * `VACUUM FULL`, ...). The slot number above is computed from `now` before this can block,
    * so a truncate left waiting long enough executes a decision that's gone stale by the time
    * it runs -- indistinguishable from running early, which is the one direction this method's
    * own contract calls dangerous. Timing out hands the failure to the same `catchAllCause` +
    * `repeat` in `live` that already covers every other way this can fail, which recomputes
    * the slot from a fresh `now` on the next tick rather than act on a stale one. `SET LOCAL`
    * keeps the change scoped to this transaction, so the pooled connection carries no
    * leftover session state once it's returned.
    */
  def evictStaleSlot(now: Instant, iatLeeway: Duration): Task[Unit] =
    val slot = slotOf(
      now.minus(iatLeeway)
        .minusSeconds(SlotWidth.toSeconds)
        .minus(MaxClockSkew.multipliedBy(2)),
    )
    xa.transactMeasured("evict-dpop-proof-slot"):
      sql"SET LOCAL lock_timeout = ${SqlLiteral(EvictionLockTimeout.toMillis.toString)}".update.run()
      sql"TRUNCATE TABLE ${SqlLiteral(s"dpop_proofs_$slot")}".update.run()
    .unit

object PostgresDpopProofRepository:
  /** Ring geometry. Must match the partitions created in `V0014__dpop_proofs_table.sql`. */
  val SlotCount = 12
  val SlotWidth: Duration = 30.seconds

  /** How far apart two instances' clocks may be before the ring stops being safe.
    *
    * Nothing here can be derived from a shared clock: a proof is routed to a slot by an `iat`
    * the issuing client stamped, accepted against the receiving instance's clock, and evicted
    * against the evicting instance's clock. Any `iat`-partitioned ring therefore rests on a
    * bound like this one; the alternative is a per-row expiry and the delete-per-insert churn
    * this design exists to avoid. 30s is far past what a synchronised fleet drifts to, and an
    * instance further out than this has a broken clock -- which breaks token lifetimes and
    * `iat` acceptance long before it breaks eviction.
    */
  val MaxClockSkew: Duration = 30.seconds

  /** How long `evictStaleSlot` waits for its `TRUNCATE` before giving up and letting the next
    * tick retry with a freshly computed slot, rather than risk acting on the one computed
    * `SlotWidth` or more ago. Sized well above what contending peers cost each other (single
    * digits of milliseconds even fleet-wide) and well below `SlotWidth` itself, so a timeout
    * still leaves room to retry before the ring would have wrapped onto the slot anyway. */
  val EvictionLockTimeout: Duration = 2.seconds

  /** Dropping a slot is safe at any geometry -- everything in it is already outside every
    * instance's `iat` window, whatever the ring looks like. What the geometry has to guarantee
    * is the other direction: that the slot being truncated is not one still being written to.
    *
    * Reading the ring as a timeline, the span that must fit in one lap of `SlotCount *
    * SlotWidth` runs from the start of the slot being truncated to the newest `iat` any
    * instance would accept:
    *   - `2 * leeway`, the width of the acceptance window itself;
    *   - `2 * SlotWidth`, since each end of that span sits at an arbitrary phase within its
    *     own slot;
    *   - `4 * MaxClockSkew`: `2 *` for the eviction anchor's own margin (see `evictStaleSlot`)
    *     and `2 *` because the evicting and the writing instance can be skewed apart.
    */
  val MaxIatLeeway: Duration = Duration.fromSeconds(
    (SlotCount * SlotWidth.toSeconds - 2 * SlotWidth.toSeconds - 4 * MaxClockSkew.toSeconds) / 2,
  )

  /** The eviction anchor never runs closer to `now` than this, however low an admin sets
    * `dpop.iat-leeway`.
    *
    * This ring is also where `EdgeAssertionService` records an edge assertion's `jti` --
    * see there. An assertion's own window is one-sided (`[issuedAt, issuedAt+Ttl]`), not the
    * `[iat-L, iat+L]` shape eviction is proven safe for above; `EdgeAssertionService` squares
    * that by recording the assertion under a timestamp centred on its real window rather than
    * `issuedAt` itself, which makes `[centred-L, centred+L]` exactly equal to
    * `[issuedAt, issuedAt+Ttl]` at `L = Ttl/2`. Flooring eviction's own leeway at that same
    * `Ttl/2` is what then lets it rely on the already-proven symmetric-window guarantee for
    * that centred record too, rather than a fresh argument for a one-sided window.
    */
  val EdgeAssertionEvictionLeeway: Duration = EdgeAssertion.Ttl.dividedBy(2)

  require(
    EdgeAssertionEvictionLeeway.compareTo(MaxIatLeeway) <= 0,
    s"EdgeAssertionEvictionLeeway of $EdgeAssertionEvictionLeeway exceeds $MaxIatLeeway -- " +
      "EdgeAssertion.Ttl grew past what this ring's geometry can floor iat-leeway to",
  )

  def live: ZLayer[TransactorZIO & CoreConfig & Scope, Throwable, DpopProofRepository] =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        iatLeeway <- ZIO.serviceWith[CoreConfig](_.dpopOrDefault.iatLeeway)
        _ <- ZIO.fail(
          IllegalArgumentException(
            s"dpop.iat-leeway of $iatLeeway exceeds $MaxIatLeeway, the longest window the " +
              s"$SlotCount-slot dpop_proofs ring can hold without reusing a slot that still " +
              "guards an acceptable proof",
          ),
          // Compared whole, not in seconds: truncation would admit a leeway of 90.5s under a
          // 90s bound, which is exactly the geometry this guard exists to keep inviolable.
        ).when(iatLeeway.compareTo(MaxIatLeeway) > 0)

        repository = PostgresDpopProofRepository(xa)
        _ <- Clock.instant
          .flatMap(repository.evictStaleSlot(_, effectiveEvictionLeeway(iatLeeway)))
          .catchAllCause(cause => ZIO.logWarningCause("failed to evict a dpop_proofs slot", cause))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
      yield repository

  /** What `live` actually schedules eviction against: not `iatLeeway` alone.
    *
    * An admin is free to tune `iatLeeway` well under `EdgeAssertionEvictionLeeway`, and this
    * ring is shared with `EdgeAssertionService`'s replay guard -- see
    * `EdgeAssertionEvictionLeeway`. Flooring here closes that regardless of how tight
    * `iatLeeway` is configured for ordinary DPoP proofs.
    */
  private[dpop] def effectiveEvictionLeeway(iatLeeway: Duration): Duration =
    if iatLeeway.compareTo(EdgeAssertionEvictionLeeway) < 0 then EdgeAssertionEvictionLeeway else iatLeeway

  /** Which slot a proof created at `iat` belongs to. Laps the ring, so two proofs a full lap
    * apart share a slot -- by then the earlier one's slot has been truncated.
    */
  private def slotOf(iat: Instant): Int =
    Math.floorMod(Math.floorDiv(iat.getEpochSecond, SlotWidth.toSeconds), SlotCount.toLong).toInt

  /** RFC 9449 §11.1: store only a hash of the `jti`. The `jkt` is folded in so the pair, not the
    * `jti` alone, is what has to be unique; the separator keeps the two fields from running
    * together.
    */
  private def digestOf(jkt: String, jti: String): Array[Byte] =
    Blake3.initHash()
      .update(jkt.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(jti.getBytes(StandardCharsets.UTF_8))
      .doFinalize(16)
