package versola.oauth.conversation.limit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.oauth.client.model.{RateLimit, TenantId}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresChallengeThrottleRepository(xa: TransactorZIO) extends ChallengeThrottleRepository, BasicCodecs:

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
      wLimits: List[RateLimit],
      banDurationSeconds: Long,
  ): Task[LimitStatus] =
    xa.transactMeasured("record-challenge-throttle-attempt"):
      // `attempt` is nested inside this block (rather than defined alongside `recordAttempt`) so
      // its recursive retry stays within the same `DbCon ?=>` context this transaction provides —
      // a top-level def wouldn't have that given in scope.
      //
      // Optimistic path: assume this is the very first attempt ever for this key and try to
      // insert it directly. Postgres's unique index on (subject, tenant_id, challenge_type)
      // resolves any concurrent "first attempt" race natively — at most one such INSERT can land
      // — so this needs no advisory lock or other session state, and behaves identically under
      // any connection pooler, including PgBouncer in transaction pooling mode.
      def attempt(retriesLeft: Int = 3): LimitStatus =
        val (speculative, speculativeStatus) =
          ChallengeThrottleRepository.nextState(None, tenantId, subject, challengeType, now, wLimits, banDurationSeconds)

        val inserted =
          sql"""
            INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at)
            VALUES (${speculative.subject}, ${speculative.tenantId}, ${speculative.challengeType}, ${speculative.attempts}, ${speculative.bannedUntil}, ${speculative.expiresAt})
            ON CONFLICT (subject, tenant_id, challenge_type) DO NOTHING
          """.update.run()

        if inserted == 1 then speculativeStatus
        else
          // Someone beat us to it — either a pre-existing row, or a concurrent first attempt that
          // won the race above. Either way Postgres guarantees the conflicting row was visible
          // (and committed) at the moment our INSERT reported the conflict, so lock and merge
          // into it exactly like any other update.
          val existing =
            sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
                  FROM challenge_throttle
                  WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
                  FOR UPDATE"""
              .query[ChallengeThrottleRecord].run().headOption

          existing match
            case None if retriesLeft > 0 =>
              // Rare: the row got deleted (e.g. a concurrent reset on a successful challenge)
              // between our failed INSERT and this SELECT. It's genuinely gone now, so retry from
              // the top instead of updating zero rows and silently dropping this attempt. Bounded
              // so a pathological delete loop fails loudly instead of recursing forever while
              // holding this transaction's connection.
              attempt(retriesLeft - 1)
            case None =>
              throw new IllegalStateException(
                s"recordAttempt: row for ($tenantId, $subject, $challengeType) kept disappearing under concurrent deletes",
              )
            case Some(record) =>
              val (updated, status) =
                ChallengeThrottleRepository.nextState(Some(record), tenantId, subject, challengeType, now, wLimits, banDurationSeconds)

              sql"""
                UPDATE challenge_throttle SET
                  attempts = ${updated.attempts},
                  banned_until = ${updated.bannedUntil},
                  expires_at = ${updated.expiresAt}
                WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
              """.update.run()

              status

      attempt()

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
