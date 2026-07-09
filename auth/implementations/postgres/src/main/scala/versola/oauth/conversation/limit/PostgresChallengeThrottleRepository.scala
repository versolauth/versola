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
    xa.connect:
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
