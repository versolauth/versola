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
