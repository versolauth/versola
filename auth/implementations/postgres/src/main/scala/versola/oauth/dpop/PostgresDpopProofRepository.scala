package versola.oauth.dpop

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import org.apache.commons.codec.digest.Blake3
import versola.util.CoreConfig
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Records proofs into a fixed ring of partitions keyed by the proof's own `iat`, so that expiry
  * is a truncate of a whole slot rather than a delete per proof. See
  * `V0014__dpop_proofs_table.sql` for why the slot is derived from `iat` and not from arrival
  * time.
  */
class PostgresDpopProofRepository(xa: TransactorZIO) extends DpopProofRepository, BasicCodecs:
  import PostgresDpopProofRepository.{digestOf, slotOf, SlotWidth}

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
    * Every instance runs it, and they all pick the same slot from the same clock, so the
    * redundant runs find the slot already empty. They never contend with the request path
    * either: the slot being truncated is, by the bound on `MaxIatLeeway`, never one that a proof
    * arriving now could be routed to.
    */
  def evictStaleSlot(now: Instant, iatLeeway: Duration): Task[Unit] =
    val slot = slotOf(now.minus(iatLeeway).minusSeconds(SlotWidth.toSeconds))
    xa.connectMeasured("evict-dpop-proof-slot"):
      sql"TRUNCATE TABLE ${SqlLiteral(s"dpop_proofs_$slot")}".update.run()
    .unit

object PostgresDpopProofRepository:
  /** Ring geometry. Must match the partitions created in `V0014__dpop_proofs_table.sql`. */
  val SlotCount = 8
  val SlotWidth: Duration = 30.seconds

  /** Dropping a slot is safe at any geometry -- everything in it is already outside the `iat`
    * window, whatever the ring looks like. What the geometry has to guarantee is the other
    * direction: that the slot being truncated is not one still being written to. Writes land
    * anywhere in `[now - leeway, now + leeway]` and the truncation targets a window ending at
    * `now - leeway`, so the two together span `2 * (leeway + SlotWidth)`, which has to fit in a
    * lap of `SlotCount * SlotWidth`.
    */
  val MaxIatLeeway: Duration = Duration.fromSeconds(SlotWidth.toSeconds * (SlotCount - 2) / 2)

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
        ).when(iatLeeway.toSeconds > MaxIatLeeway.toSeconds)

        repository = PostgresDpopProofRepository(xa)
        _ <- Clock.instant
          .flatMap(repository.evictStaleSlot(_, iatLeeway))
          .catchAllCause(cause => ZIO.logWarningCause("failed to evict a dpop_proofs slot", cause))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
      yield repository

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
