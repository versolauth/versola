package versola.oauth.clientauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import org.apache.commons.codec.digest.Blake3
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Records client assertions into a fixed ring of partitions keyed by the assertion's own
  * `exp`, so that expiry is a truncate of a whole slot rather than a delete per assertion.
  * See `V0016__client_assertions_table.sql`, and `PostgresDpopProofRepository` for the same
  * design argued in full.
  */
class PostgresClientAssertionRepository(xa: TransactorZIO) extends ClientAssertionRepository, BasicCodecs:
  import PostgresClientAssertionRepository.{EvictionLockTimeout, MaxClockSkew, SlotWidth, digestOf, slotOf}

  override def recordIfAbsent(clientId: String, jti: String, expiresAt: Instant): Task[Boolean] =
    xa.connectMeasured("record-client-assertion-if-absent"):
      // No expiry is compared here. A slot is only reclaimed once every assertion it holds has
      // passed its `exp`, and `ClientAssertion.verify` has already refused an expired one
      // before this point -- so a surviving record can never be stale.
      sql"""
        INSERT INTO client_assertions (slot, digest)
        VALUES (${slotOf(expiresAt)}, ${digestOf(clientId, jti)})
        ON CONFLICT (slot, digest) DO NOTHING
        RETURNING 1
      """.query[Int].run()
        .nonEmpty

  /** Truncates the one slot that can no longer hold a valid assertion.
    *
    * The asymmetry `PostgresDpopProofRepository.evictStaleSlot` describes holds here too:
    * running early would drop the record of an assertion that is still acceptable and so
    * still replayable, while running late costs disk and nothing else. Hence the same
    * background job whose failure is logged rather than failing a request, the same
    * `2 * MaxClockSkew` margin on the anchor because each instance evicts against its own
    * clock, and the same `lock_timeout` so a truncate that had to wait is abandoned rather
    * than executed against a slot number that has gone stale.
    *
    * No configured lifetime is consulted: a record sits under its assertion's `exp`, so a
    * slot is stale once `now` is past every `exp` it could hold, whatever lifetime produced
    * those. What the configured lifetime bounds is the other end -- how far ahead of `now`
    * records reach, and so whether the ring is long enough not to lap onto them, which is
    * what [[MaxLifetime]] settles once for the whole ring.
    */
  def evictStaleSlot(now: Instant): Task[Unit] =
    val slot = slotOf(
      now.minusSeconds(SlotWidth.toSeconds)
        .minus(MaxClockSkew.multipliedBy(2)),
    )
    xa.transactMeasured("evict-client-assertion-slot"):
      sql"SET LOCAL lock_timeout = ${SqlLiteral(EvictionLockTimeout.toMillis.toString)}".update.run()
      sql"TRUNCATE TABLE ${SqlLiteral(s"client_assertions_$slot")}".update.run()
    .unit

object PostgresClientAssertionRepository:
  /** Ring geometry. Must match the partitions created in `V0016__client_assertions_table.sql`.
    *
    * Wider slots than the DPoP ring's and the same count: the window here is a whole assertion
    * lifetime rather than an `iat` leeway, so the lap has to be minutes rather than seconds,
    * and buying that with fewer, wider slots keeps the partition count -- and the per-tick
    * truncate contention -- unchanged.
    */
  val SlotCount = 12
  val SlotWidth: Duration = 120.seconds

  /** How far apart two instances' clocks may be before the ring stops being safe; see
    * `PostgresDpopProofRepository.MaxClockSkew`, which this matches. */
  val MaxClockSkew: Duration = 30.seconds

  /** See `PostgresDpopProofRepository.EvictionLockTimeout`. */
  val EvictionLockTimeout: Duration = 2.seconds

  /** The longest assertion lifetime this geometry can hold without reusing a slot that still
    * guards a valid assertion.
    *
    * An assertion is acceptable over `[exp - maxLifetime, exp]`, a one-sided window of width
    * `maxLifetime`. Recording it under `exp` puts it at the *end* of that window rather than
    * at its centre, so the span that must fit in one lap of `SlotCount * SlotWidth` is:
    *   - `maxLifetime`, the width of the window itself;
    *   - `2 * SlotWidth`, since each end sits at an arbitrary phase within its own slot;
    *   - `4 * MaxClockSkew`, `2 *` for the eviction anchor's own margin and `2 *` because the
    *     evicting and the recording instance can be skewed apart.
    */
  val MaxLifetime: Duration = Duration.fromSeconds(
    SlotCount * SlotWidth.toSeconds - 2 * SlotWidth.toSeconds - 4 * MaxClockSkew.toSeconds,
  )

  /** The ceiling central enforces on a tenant's `clientAssertionMaxLifetimeSeconds`, repeated
    * here because this is the side whose geometry has to hold it. Mirrors
    * `ChallengeSettingsRecord.MaxClientAssertionMaxLifetimeSeconds`; the `require` below is
    * what keeps the two from drifting apart unnoticed.
    */
  val ConfigurableMaxLifetime: Duration = Duration.fromSeconds(900)

  require(
    ConfigurableMaxLifetime.compareTo(MaxLifetime) <= 0,
    s"ConfigurableMaxLifetime of $ConfigurableMaxLifetime exceeds $MaxLifetime -- central lets a " +
      s"tenant configure an assertion lifetime longer than the $SlotCount-slot client_assertions " +
      "ring can hold without reusing a slot that still guards a valid assertion",
  )

  def live: ZLayer[TransactorZIO & Scope, Throwable, ClientAssertionRepository] =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresClientAssertionRepository(xa)
        _ <- Clock.instant
          .flatMap(repository.evictStaleSlot)
          .catchAllCause(cause => ZIO.logWarningCause("failed to evict a client_assertions slot", cause))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
      yield repository

  /** Which slot an assertion expiring at `exp` belongs to. Laps the ring, so two assertions a
    * full lap apart share a slot -- by then the earlier one's slot has been truncated.
    */
  private def slotOf(expiresAt: Instant): Int =
    Math.floorMod(Math.floorDiv(expiresAt.getEpochSecond, SlotWidth.toSeconds), SlotCount.toLong).toInt

  /** Store only a digest of the `jti`, as RFC 9449 §11.1 asks of the equivalent DPoP record
    * and for the same reasons. The client id is folded in so the pair has to be unique; the
    * separator keeps the two fields from running together.
    */
  private def digestOf(clientId: String, jti: String): Array[Byte] =
    Blake3.initHash()
      .update(clientId.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(jti.getBytes(StandardCharsets.UTF_8))
      .doFinalize(16)
