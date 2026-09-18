package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsRepository, MtlsCertificateEncoding, PasskeySettings, SubmissionLimits}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresChallengeSettingsRepository(xa: TransactorZIO) extends ChallengeSettingsRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[List[String]] = PgCodec.ListCodec[String]
  given DbCodec[SubmissionLimits] = jsonBCodec[SubmissionLimits]
  given DbCodec[PasskeySettings] = jsonBCodec[PasskeySettings]
  given DbCodec[MtlsCertificateEncoding] =
    DbCodec.StringCodec.biMap(value => MtlsCertificateEncoding.values.find(_.toString == value).get, _.toString)
  given DbCodec[ChallengeSettingsRecord] = DbCodec.derived

  override def getAll: Task[Vector[ChallengeSettingsRecord]] =
    xa.connectMeasured("get-all-challenge-settings"):
      sql"""SELECT tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, client_assertion_max_lifetime_seconds FROM challenge_settings ORDER BY tenant_id"""
        .query[ChallengeSettingsRecord].run()

  override def findByTenant(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]] =
    xa.connectMeasured("find-challenge-settings-by-tenant"):
      sql"""SELECT tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, client_assertion_max_lifetime_seconds FROM challenge_settings WHERE tenant_id = $tenantId"""
        .query[ChallengeSettingsRecord].run()
        .headOption

  override def upsert(record: ChallengeSettingsRecord): Task[Unit] =
    xa.connectMeasured("upsert-challenge-settings"):
      sql"""
        INSERT INTO challenge_settings (tenant_id, allowed_prefixes, submission_limits, otp_length, otp_resend_after, passkey_settings, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, client_assertion_max_lifetime_seconds)
        VALUES (${record.tenantId}, ${record.allowedPrefixes}, ${record.submissionLimits}, ${record.otpLength}, ${record.otpResendAfter}, ${record.passkeySettings}, ${record.authConversationTtlSeconds}, ${record.sessionTtlSeconds}, ${record.sessionIdleTtlSeconds}, ${record.userAgentTtlSeconds}, ${record.ipHeader}, ${record.acrVocabulary}, ${record.postLogoutRedirectUris}, ${record.requireDpopNonce}, ${record.mtlsCertificateHeader}, ${record.mtlsCertificateEncoding}, ${record.clientAssertionMaxLifetimeSeconds})
        ON CONFLICT (tenant_id) DO UPDATE SET
          allowed_prefixes = EXCLUDED.allowed_prefixes,
          submission_limits = EXCLUDED.submission_limits,
          otp_length = EXCLUDED.otp_length,
          otp_resend_after = EXCLUDED.otp_resend_after,
          passkey_settings = EXCLUDED.passkey_settings,
          auth_conversation_ttl_seconds = EXCLUDED.auth_conversation_ttl_seconds,
          session_ttl_seconds = EXCLUDED.session_ttl_seconds,
          session_idle_ttl_seconds = EXCLUDED.session_idle_ttl_seconds,
          user_agent_ttl_seconds = EXCLUDED.user_agent_ttl_seconds,
          ip_header = EXCLUDED.ip_header,
          acr_vocabulary = EXCLUDED.acr_vocabulary,
          post_logout_redirect_uris = EXCLUDED.post_logout_redirect_uris,
          require_dpop_nonce = EXCLUDED.require_dpop_nonce,
          mtls_certificate_header = EXCLUDED.mtls_certificate_header,
          mtls_certificate_encoding = EXCLUDED.mtls_certificate_encoding,
          client_assertion_max_lifetime_seconds = EXCLUDED.client_assertion_max_lifetime_seconds
      """.update.run()
    .unit

object PostgresChallengeSettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, ChallengeSettingsRepository] =
    ZLayer.fromFunction(PostgresChallengeSettingsRepository(_))