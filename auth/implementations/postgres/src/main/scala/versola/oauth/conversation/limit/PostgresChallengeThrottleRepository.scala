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
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at, version
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
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at, version
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
      sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at, version
            FROM challenge_throttle
            WHERE tenant_id = $tenantId AND subject = ANY($subjects) AND challenge_type = $challengeType"""
        .query[ChallengeThrottleRecord].run()
        .toList

  /** Compare-and-set write. The INSERT branch covers a subject with no row yet; if a row appeared in
    * the meantime the ON CONFLICT branch runs, and its WHERE keeps the update only when the stored
    * version is still the one the caller read. Either way a lost race updates no rows and reports
    * `false`, so concurrent attempts are forced to serialise instead of overwriting each other.
    */
  override def upsert(record: ChallengeThrottleRecord): Task[Boolean] =
    xa.connectMeasured("upsert-challenge-throttle"):
      sql"""
        INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at, version)
        VALUES (${record.subject}, ${record.tenantId}, ${record.challengeType}, ${record.attempts}, ${record.bannedUntil}, ${record.expiresAt}, ${record.version + 1})
        ON CONFLICT (subject, tenant_id, challenge_type) DO UPDATE SET
          attempts = EXCLUDED.attempts,
          banned_until = EXCLUDED.banned_until,
          expires_at = EXCLUDED.expires_at,
          version = EXCLUDED.version
        WHERE challenge_throttle.version = ${record.version}
      """.update.run()
    .map(_ > 0)

  override def recordAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): Task[LimitStatus] =
    xa.transactMeasured("record-challenge-throttle-attempt"):
      // Short-circuit subjects that are already banned, before taking any lock or writing
      // anything. A ban is precisely the state an attacker keeps hammering, so without this the
      // hot path under attack would be "lock the row, write back identical values" on every
      // request — pointless WAL plus a lock convoy on a single row. This read is an unlocked
      // primary-key lookup; the authoritative re-check still happens under the lock below (via
      // `ThrottlePolicy.nextState`), so a ban landing right after this read is not missed.
      val alreadyBanned =
        sql"""SELECT 1 FROM challenge_throttle
              WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
                AND banned_until IS NOT NULL AND banned_until > $now"""
          .query[Int].run().nonEmpty

      if alreadyBanned then LimitStatus.Banned
      else
        // `attempt` is nested inside this block (rather than defined alongside `recordAttempt`)
        // so its recursive retry stays within the same `DbCon ?=>` context this transaction
        // provides — a top-level def wouldn't have that given in scope.
        //
        // Optimistic path: assume this is the very first attempt ever for this key and try to
        // insert it directly. Postgres's unique index on (subject, tenant_id, challenge_type)
        // resolves any concurrent "first attempt" race natively — at most one such INSERT can
        // land — so this needs no advisory lock or other session state, and behaves identically
        // under any connection pooler, including PgBouncer in transaction pooling mode.
        def attempt(retriesLeft: Int): LimitStatus =
          val (speculative, speculativeStatus) =
            ThrottlePolicy.nextState(None, tenantId, subject, challengeType, now, wLimits, banDurationSeconds)

          val inserted =
            sql"""
              INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at)
              VALUES (${speculative.subject}, ${speculative.tenantId}, ${speculative.challengeType}, ${speculative.attempts}, ${speculative.bannedUntil}, ${speculative.expiresAt})
              ON CONFLICT (subject, tenant_id, challenge_type) DO NOTHING
            """.update.run()

          if inserted == 1 then speculativeStatus
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
                  s"recordAttempt: challenge_throttle row for tenant $tenantId / $challengeType " +
                    s"kept disappearing under concurrent deletes after $MaxRecordAttemptRetries retries",
                )
              case Some(record) =>
                val (updated, status) =
                  ThrottlePolicy.nextState(Some(record), tenantId, subject, challengeType, now, wLimits, banDurationSeconds)

                // `nextState` returns the record unchanged when there's nothing to record (an
                // active ban); skip the write rather than rewriting identical values.
                if updated != record then
                  sql"""
                    UPDATE challenge_throttle SET
                      attempts = ${updated.attempts},
                      banned_until = ${updated.bannedUntil},
                      expires_at = ${updated.expiresAt}
                    WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
                  """.update.run()

                status

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
