package versola.oauth.conversation.limit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.oauth.client.model.TenantId
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
      mutate: Option[ChallengeThrottleRecord] => (ThrottleUpdate, LimitStatus),
  ): Task[LimitStatus] =
    val lockKey = s"$tenantId|$subject|$challengeType"
    xa.transactMeasured("record-challenge-throttle-attempt"):
      // Serialize concurrent attempts against this key even before any row exists. A plain
      // `SELECT ... FOR UPDATE` below can only lock a row that's already there — without this,
      // the very first concurrent attempts for a brand-new subject could still race: each would
      // see no row, and the later `INSERT ... ON CONFLICT DO UPDATE` would overwrite instead of
      // merge. The advisory lock is scoped to this transaction and auto-released on
      // commit/rollback; wrapping it in a subquery so we select a plain Int (the function itself
      // returns void, which isn't reliably decodable).
      sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext($lockKey)::bigint)) AS throttle_lock"
        .query[Int].run()

      // Lock the row (if any) for the rest of the transaction too, as defense in depth — belt and
      // braces alongside the advisory lock above.
      val existing =
        sql"""SELECT tenant_id, subject, challenge_type, attempts, banned_until, expires_at
              FROM challenge_throttle
              WHERE tenant_id = $tenantId AND subject = $subject AND challenge_type = $challengeType
              FOR UPDATE"""
          .query[ChallengeThrottleRecord].run().headOption

      val (update, result) = mutate(existing)

      // Key fields written are exactly the ones we locked above — `tenantId`/`subject`/
      // `challengeType` parameters, never anything from `mutate`'s result — so `mutate` can't
      // steer the write to a different row than the one this transaction holds the lock for.
      sql"""
        INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, banned_until, expires_at)
        VALUES ($subject, $tenantId, $challengeType, ${update.attempts}, ${update.bannedUntil}, ${update.expiresAt})
        ON CONFLICT (subject, tenant_id, challenge_type) DO UPDATE SET
          attempts = EXCLUDED.attempts,
          banned_until = EXCLUDED.banned_until,
          expires_at = EXCLUDED.expires_at
      """.update.run()

      result

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
