package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import org.apache.commons.codec.digest.Blake3
import versola.edge.dpop.DpopProofRepository
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Records proofs into a fixed ring of partitions keyed by the proof's own `iat`, so that expiry
  * is a truncate of a whole slot rather than a delete per proof. See
  * `V2004__edge_dpop_proofs_table.sql` for why the slot is derived from `iat` and not from
  * arrival time.
  *
  * The table is `edge_dpop_proofs`, not `dpop_proofs` -- auth already owns that name for its own
  * ring, and `sbt test` from the repo root has both migrations land in the same test database
  * (Flyway auto-discovers every service's migrations directory there; see
  * `PostgresHikariDataSource.detectMigrationDirectories`).
  *
  * A deliberate copy of auth's repository of the same name rather than a shared abstraction:
  * the two rings guard different endpoints at different rates, and the next change to either --
  * batching this one, most likely -- should not be forced on the other. The geometry and the
  * statement are the same today, and the tests on both sides are what keep that honest.
  */
class PostgresDpopProofRepository(xa: TransactorZIO) extends DpopProofRepository, BasicCodecs:
  import PostgresDpopProofRepository.{digestOf, slotOf, EvictionLockTimeout, MaxClockSkew, SlotWidth}

  override def recordIfAbsent(jkt: String, jti: String, iat: Instant): Task[Boolean] =
    xa.connectMeasured("record-dpop-proof-if-absent"):
      // No expiry is compared here. A slot is only reclaimed once everything it holds has left
      // the `iat` window, so a proof whose record is gone has already been rejected by
      // `Dpop.verify` before reaching this point -- a surviving record can never be stale.
      sql"""
        INSERT INTO edge_dpop_proofs (slot, digest)
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
    */
  def evictStaleSlot(now: Instant, iatLeeway: Duration): Task[Unit] =
    val slot = slotOf(
      now.minus(iatLeeway)
        .minusSeconds(SlotWidth.toSeconds)
        .minus(MaxClockSkew.multipliedBy(2)),
    )
    // Every pod targets the same partition in the same tick, contending only with each other's
    // routine truncate -- cheap, measured at low milliseconds even under a full fleet.
    // `lock_timeout` bounds the one case that isn't: something else holding a conflicting lock
    // long enough that the slot number above, computed from `now` before the wait, goes stale
    // by the time the statement would run -- indistinguishable from the early-truncate hazard
    // this method's own contract calls dangerous. Timing out hands the failure to the
    // `catchAllCause` + `repeat` in `live`/`shared`, which recomputes the slot on the next tick
    // instead of acting on a stale one. `SET LOCAL` keeps the change scoped to this
    // transaction, so the pooled connection carries no leftover session state once returned.
    xa.transactMeasured("evict-dpop-proof-slot"):
      sql"SET LOCAL lock_timeout = ${SqlLiteral(EvictionLockTimeout.toMillis.toString)}".update.run()
      sql"TRUNCATE TABLE ${SqlLiteral(s"edge_dpop_proofs_$slot")}".update.run()
    .unit

object PostgresDpopProofRepository:
  /** Ring geometry. Must match the partitions created in `V2004__edge_dpop_proofs_table.sql`. */
  val SlotCount = 12
  val SlotWidth: Duration = 30.seconds

  /** How far apart two instances' clocks may be before the ring stops being safe.
    *
    * Nothing here can be derived from a shared clock: a proof is routed to a slot by an `iat`
    * the issuing client stamped, accepted against the receiving pod's clock, and evicted
    * against the evicting pod's clock. Any `iat`-partitioned ring shared across pods therefore
    * rests on a bound like this one; the alternative is a per-row expiry and the
    * delete-per-insert churn this design exists to avoid. 30s is far past what a synchronised
    * fleet drifts to, and a pod further out than this has a broken clock -- which breaks `iat`
    * acceptance itself long before it breaks eviction.
    */
  val MaxClockSkew: Duration = 30.seconds

  /** How long `evictStaleSlot` waits for its `TRUNCATE` before giving up and letting the next
    * tick retry with a freshly computed slot, rather than risk acting on the one computed
    * `SlotWidth` or more ago. Sized well above what contending pods cost each other (single
    * digits of milliseconds even fleet-wide) and well below `SlotWidth` itself, so a timeout
    * still leaves room to retry before the ring would have wrapped onto the slot anyway. */
  val EvictionLockTimeout: Duration = 2.seconds

  /** Dropping a slot is safe at any geometry -- everything in it is already outside every
    * pod's `iat` window, whatever the ring looks like. What the geometry has to guarantee is
    * the other direction: that the slot being truncated is not one still being written to.
    *
    * Reading the ring as a timeline, the span that must fit in one lap of `SlotCount *
    * SlotWidth` runs from the start of the slot being truncated to the newest `iat` any pod
    * would accept:
    *   - `2 * leeway`, the width of the acceptance window itself;
    *   - `2 * SlotWidth`, since each end of that span sits at an arbitrary phase within its
    *     own slot;
    *   - `4 * MaxClockSkew`: `2 *` for the eviction anchor's own margin (see `evictStaleSlot`)
    *     and `2 *` because the evicting and the writing pod can be skewed apart.
    *
    * This is why the shared ring needs 12 slots where [[versola.edge.dpop.DpopReplayGuard]]'s
    * in-memory ring carries the same leeway in 8: that one is written and evicted by the same
    * pod, so none of the skew terms apply to it.
    */
  val MaxIatLeeway: Duration = Duration.fromSeconds(
    (SlotCount * SlotWidth.toSeconds - 2 * SlotWidth.toSeconds - 4 * MaxClockSkew.toSeconds) / 2,
  )

  def live: ZLayer[TransactorZIO & EdgeConfig & Scope, Throwable, DpopProofRepository] =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        iatLeeway <- ZIO.serviceWith[EdgeConfig](
          _.dpop.map(_.iatLeeway).getOrElse(Duration.fromSeconds(60)),
        )
        _ <- ZIO.fail(
          IllegalArgumentException(
            s"dpop.iat-leeway of $iatLeeway exceeds $MaxIatLeeway, the longest window the " +
              s"$SlotCount-slot edge_dpop_proofs ring can hold without reusing a slot that still " +
              "guards an acceptable proof",
          ),
          // Compared whole, not in seconds: truncation would admit a leeway of 90.5s under a
          // 90s bound, which is exactly the geometry this guard exists to keep inviolable.
        ).when(iatLeeway.compareTo(MaxIatLeeway) > 0)

        repository = PostgresDpopProofRepository(xa)
        _ <- Clock.instant
          .flatMap(repository.evictStaleSlot(_, iatLeeway))
          .catchAllCause(cause => ZIO.logWarningCause("failed to evict an edge_dpop_proofs slot", cause))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
      yield repository

  /** Which slot a proof created at `iat` belongs to. Laps the ring, so two proofs a full lap
    * apart share a slot -- by then the earlier one's slot has been truncated.
    */
  private def slotOf(iat: Instant): Int =
    Math.floorMod(Math.floorDiv(iat.getEpochSecond, SlotWidth.toSeconds), SlotCount.toLong).toInt

  /** §11.1 recommends storing only a hash of the `jti`, which also bounds what an oversized
    * `jti` can cost. The `jkt` is folded in so the pair, not the `jti` alone, has to be
    * unique -- two clients are free to pick the same `jti`.
    */
  private def digestOf(jkt: String, jti: String): Array[Byte] =
    Blake3.initHash()
      .update(jkt.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(jti.getBytes(StandardCharsets.UTF_8))
      .doFinalize(16)
