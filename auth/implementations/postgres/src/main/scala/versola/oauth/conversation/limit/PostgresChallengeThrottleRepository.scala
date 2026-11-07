package versola.oauth.conversation.limit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.oauth.client.model.{RateLimit, TenantId}
import versola.util.postgres.BasicCodecs
import zio.{NonEmptyChunk, Task, ZLayer}

import java.time.Instant

class PostgresChallengeThrottleRepository(xa: TransactorZIO) extends ChallengeThrottleRepository, BasicCodecs:

  /** How many times `recordAttempt` re-runs its optimistic insert when the row is deleted out from
    * under the fallback lookup. Bounded so a pathological concurrent-delete loop fails loudly
    * instead of recursing until the stack (and the connection) give out.
    */
  private val MaxRecordAttemptRetries = 3

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ChallengeType] = DbCodec.StringCodec.biMap(ChallengeType.valueOf, _.toString)
  given SqlArrayCodec[ChallengeType] = new SqlArrayCodec[ChallengeType]:
    val jdbcTypeName: String = "VARCHAR"
    def readArray(array: Object): Array[ChallengeType] =
      array.asInstanceOf[Array[String]].map(ChallengeType.valueOf)
    def toArrayObj(entity: ChallengeType): Object = entity.toString
  given DbCodec[List[Long]] = jsonBCodec[List[Long]]
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[ChallengeThrottleRecord] = DbCodec.derived

  override def find(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Option[ChallengeThrottleRecord]] =
    xa.connectMeasured("find-challenge-throttle"):
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
            FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType"""
        .query[ChallengeThrottleRecord].run()
        .headOption

  override def findAll(
      tenantId: TenantId,
      subject: String,
      challengeTypes: List[ChallengeType],
  ): Task[List[ChallengeThrottleRecord]] =
    xa.connectMeasured("find-all-challenge-throttle"):
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
            FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = ANY($challengeTypes)"""
        .query[ChallengeThrottleRecord].run()
        .toList

  override def findAllForSubjects(
      tenantId: TenantId,
      subjects: List[String],
      challengeType: ChallengeType,
  ): Task[List[ChallengeThrottleRecord]] =
    xa.connectMeasured("find-all-challenge-throttle-for-subjects"):
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
            FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = ANY($subjects) AND challenge_type = $challengeType"""
        .query[ChallengeThrottleRecord].run()
        .toList

  override def upsert(record: ChallengeThrottleRecord): Task[Unit] =
    xa.connectMeasured("upsert-challenge-throttle"):
      sql"""
        INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at)
        VALUES (${record.subject}, ${record.tenantId}, ${record.challengeType}, ${record.attempts}, ${record.bannedUntil}, ${record.expiresAt})
        ON CONFLICT (subject, tenant_id, challenge_type) DO UPDATE SET
          attempts = EXCLUDED.attempts,
          banned_until = EXCLUDED.banned_until,
          expires_at = EXCLUDED.expires_at
      """.update.run()
    .unit

  override def recordAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): Task[LimitStatus] =
    xa.transactMeasured("record-challenge-throttle-attempt"):
      if alreadyBanned(tenantId, subject, challengeType, now) then LimitStatus.Banned
      else
        readModifyWrite(tenantId, subject, challengeType): existing =>
          val (updated, status) =
            ThrottlePolicy.nextState(existing, tenantId, subject, challengeType, now, wLimits, banDurationSeconds)
          Some(updated) -> status

  override def reserveAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): Task[Reservation] =
    xa.transactMeasured("reserve-challenge-throttle-attempt"):
      deniedWithoutLock(tenantId, subject, challengeType, now, wLimits) match
        case Some(denied) => Reservation.Denied(denied)
        case None =>
          readModifyWrite(tenantId, subject, challengeType): existing =>
            ThrottlePolicy.reserve(existing, tenantId, subject, challengeType, now, wLimits, banDurationSeconds) match
              case Left(denied) => None -> Reservation.Denied(denied)
              case Right((applied, status)) =>
                Some(applied) -> Reservation.Granted(
                  tenantId = tenantId,
                  subject = subject,
                  challengeType = challengeType,
                  after = status,
                  reservedAt = now,
                  previous = existing,
                  applied = applied,
                )

  override def refundAttempt(reservation: Reservation.Granted): Task[Unit] =
    xa.transactMeasured("refund-challenge-throttle-attempt"):
      val existing =
        sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
              FROM challenge_throttle
              WHERE tenant_id = ${reservation.tenantId} AND subject = ${reservation.subject}
                AND challenge_type = ${reservation.challengeType}
              FOR UPDATE"""
          .query[ChallengeThrottleRecord].run().headOption

      existing.foreach: current =>
        ThrottlePolicy.refund(current, reservation) match
          case None =>
            sql"""DELETE FROM challenge_throttle
                  WHERE tenant_id = ${reservation.tenantId} AND subject = ${reservation.subject}
                    AND challenge_type = ${reservation.challengeType}""".update.run()
          case Some(restored) =>
            if restored != current then update(restored)
    .unit

  /** The decision for a subject that is already denied, taken from an unlocked primary-key read: a ban or a hard window that is
    * full. [[alreadyBanned]] short-circuits only the ban; a subject over a hard window (rate limited, not banned) is as much what
    * an attacker keeps hammering, and would otherwise queue on the row lock and hold a connection for every request of the flood.
    * It is the same decision [[ThrottlePolicy.reserve]] takes, from the same function, so what is answered here is what the
    * locked path would answer. Only a refusal is taken without the lock: a state that lets the attempt through, and any state
    * that changes between this read and the write, is decided again under the lock in [[readModifyWrite]].
    */
  private def deniedWithoutLock(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
  )(using DbCon): Option[LimitStatus] =
    sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
          FROM challenge_throttle
          WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType"""
      .query[ChallengeThrottleRecord].run().headOption
      .map(ThrottlePolicy.evaluate(_, wLimits, now))
      .filter(_ != LimitStatus.Allowed)

  /** Short-circuits subjects that are already banned, before taking any lock or writing anything. A
    * ban is precisely the state an attacker keeps hammering, so without this the hot path under
    * attack would be "lock the row, write back identical values" on every request — pointless WAL
    * plus a lock convoy on a single row. This read is an unlocked primary-key lookup; the
    * authoritative re-check still happens under the lock in [[readModifyWrite]], so a ban landing
    * right after this read is not missed.
    */
  private def alreadyBanned(tenantId: TenantId, subject: String, challengeType: ChallengeType, now: Instant)(using DbCon): Boolean =
    sql"""SELECT 1 FROM challenge_throttle
          WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
            AND banned_until IS NOT NULL AND banned_until > $now"""
      .query[Int].run().nonEmpty

  private def update(record: ChallengeThrottleRecord)(using DbCon): Unit =
    sql"""
      UPDATE challenge_throttle SET
        attempts = ${record.attempts},
        banned_until = ${record.bannedUntil},
        expires_at = ${record.expiresAt}
      WHERE tenant_id = ${record.tenantId} AND subject = ${record.subject} AND challenge_type = ${record.challengeType}
    """.update.run(): Unit

  /** One atomic read-modify-write of a throttle row. `decide` sees the row as it is under the lock
    * (`None` when there is none) and returns the row to store (`None` to leave it as it is) and the
    * result to hand back; it may run more than once, so it must be pure.
    *
    * Optimistic path: assume this is the very first attempt ever for this key and try to insert it
    * directly. Postgres's unique index on (subject, tenant_id, challenge_type) resolves any
    * concurrent "first attempt" race natively — at most one such INSERT can land — so this needs no
    * advisory lock or other session state, and behaves identically under any connection pooler,
    * including PgBouncer in transaction pooling mode.
    */
  private def readModifyWrite[A](
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  )(decide: Option[ChallengeThrottleRecord] => (Option[ChallengeThrottleRecord], A))(using DbCon): A =
    def attempt(retriesLeft: Int): A =
      val (speculative, speculativeResult) = decide(None)

      val inserted = speculative.fold(0): record =>
        sql"""
          INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at)
          VALUES (${record.subject}, ${record.tenantId}, ${record.challengeType}, ${record.attempts}, ${record.bannedUntil}, ${record.expiresAt})
          ON CONFLICT (subject, tenant_id, challenge_type) DO NOTHING
        """.update.run()

      if inserted == 1 || speculative.isEmpty then speculativeResult
      else
        // Someone beat us to it — either a pre-existing row, or a concurrent first attempt
        // that won the race above. Either way Postgres guarantees the conflicting row was
        // visible (and committed) at the moment our INSERT reported the conflict, so lock and
        // merge into it exactly like any other update.
        val existing =
          sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
                FROM challenge_throttle
                WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
                FOR UPDATE"""
            .query[ChallengeThrottleRecord].run().headOption

        existing match
          case None if retriesLeft > 0 =>
            // Rare: the row got deleted (e.g. a concurrent reset on a successful challenge)
            // between our failed INSERT and this SELECT. It's genuinely gone now, so retry
            // from the top instead of updating zero rows and silently dropping this attempt.
            // Bounded so a pathological delete loop fails loudly instead of recursing forever
            // while holding this transaction's connection.
            attempt(retriesLeft - 1)
          case None =>
            // Deliberately identifies the row by tenant and challenge type only — `subject` is
            // an email, phone number or IP, and this message ends up in logs.
            throw new IllegalStateException(
              s"challenge_throttle row for tenant $tenantId / $challengeType " +
                s"kept disappearing under concurrent deletes after $MaxRecordAttemptRetries retries",
            )
          case Some(record) =>
            val (write, result) = decide(Some(record))
            // Nothing to record (an active ban, a denied reservation): skip the write rather than
            // rewriting identical values.
            write.filter(_ != record).foreach(update)
            result

    attempt(MaxRecordAttemptRetries)

  override def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit] =
    xa.connectMeasured("delete-challenge-throttle"):
      sql"""DELETE FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType"""
        .update.run()
    .unit

  override def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit] =
    xa.connectMeasured("delete-challenge-throttle-for-subject"):
      sql"""DELETE FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = $subject"""
        .update.run()
    .unit

object PostgresChallengeThrottleRepository:
  def live: ZLayer[TransactorZIO, Nothing, ChallengeThrottleRepository] =
    ZLayer.fromFunction(PostgresChallengeThrottleRepository(_))
