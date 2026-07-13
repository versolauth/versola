package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsRepository, PasskeySettings, SubmissionLimits}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresChallengeSettingsRepository(xa: TransactorZIO) extends ChallengeSettingsRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[List[String]] = PgCodec.ListCodec[String]
  given DbCodec[SubmissionLimits] = jsonBCodec[SubmissionLimits]
  given DbCodec[PasskeySettings] = jsonBCodec[PasskeySettings]
  given DbCodec[ChallengeSettingsRecord] = DbCodec.derived

  override def getAll: Task[Vector[ChallengeSettingsRecord]] =
    xa.connectMeasured("get-all-challenge-settings"):
      sql"""SELECT tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, ip_header FROM challenge_settings ORDER BY tenant_id"""
        .query[ChallengeSettingsRecord].run()

  override def findByTenant(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]] =
    xa.connectMeasured("find-challenge-settings-by-tenant"):
      sql"""SELECT tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, ip_header FROM challenge_settings WHERE tenant_id = $tenantId"""
        .query[ChallengeSettingsRecord].run()
        .headOption

  override def upsert(record: ChallengeSettingsRecord): Task[Unit] =
    xa.connectMeasured("upsert-challenge-settings"):
      sql"""
        INSERT INTO challenge_settings (tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, ip_header)
        VALUES (${record.tenantId}, ${record.allowedPrefixes}, ${record.submissionLimits}, ${record.otpLength}, ${record.otpResendAfter}, ${record.passkeySettings}, ${record.authConversationTtlSeconds}, ${record.sessionTtlSeconds}, ${record.sessionIdleTtlSeconds}, ${record.ipHeader})
        ON CONFLICT (tenant_id) DO UPDATE SET
          allowed_prefixes = EXCLUDED.allowed_prefixes,
          submission_limits = EXCLUDED.submission_limits,
          otp_length = EXCLUDED.otp_length,
          otp_resend_after = EXCLUDED.otp_resend_after,
          passkey_settings = EXCLUDED.passkey_settings,
          auth_conversation_ttl_seconds = EXCLUDED.auth_conversation_ttl_seconds,
          session_ttl_seconds = EXCLUDED.session_ttl_seconds,
          session_idle_ttl_seconds = EXCLUDED.session_idle_ttl_seconds,
          ip_header = EXCLUDED.ip_header
      """.update.run()
    .unit

object PostgresChallengeSettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, ChallengeSettingsRepository] =
    ZLayer.fromFunction(PostgresChallengeSettingsRepository(_))
