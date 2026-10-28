package versola.configuration.challenges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.central.configuration.challenges.{
  ChallengeSettingsPatchResult,
  ChallengeSettingsRecord,
  ChallengeSettingsRepository,
  EmailRegistration,
  MtlsCertificateEncoding,
  PasskeyPolicy,
  PasskeySettings,
  PatchChallengeSettingsRequest,
  PhoneRegion,
  SecurityProfile,
  SubmissionLimits,
}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresChallengeSettingsRepository(xa: TransactorZIO) extends ChallengeSettingsRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[List[String]] = PgCodec.ListCodec[String]
  given phoneRegionsCodec: DbCodec[List[PhoneRegion]] = jsonBCodec[List[PhoneRegion]]
  given DbCodec[EmailRegistration] = jsonBCodec[EmailRegistration]
  given DbCodec[Set[RoleId]] = jsonBCodec[Set[RoleId]]
  given DbCodec[SubmissionLimits] = jsonBCodec[SubmissionLimits]
  given DbCodec[PasskeySettings] = jsonBCodec[PasskeySettings]
  given DbCodec[PasskeyPolicy] = jsonBCodec[PasskeyPolicy]
  given DbCodec[MtlsCertificateEncoding] =
    DbCodec.StringCodec.biMap(value => MtlsCertificateEncoding.values.find(_.toString == value).get, _.toString)
  given DbCodec[SecurityProfile] =
    DbCodec.StringCodec.biMap(value => SecurityProfile.values.find(_.toString == value).get, _.toString)
  given DbCodec[ChallengeSettingsRecord] = DbCodec.derived

  override def getAll: Task[Vector[ChallengeSettingsRecord]] =
    xa.connectMeasured("get-all-challenge-settings"):
      sql"""SELECT tenant_id, phone_regions, email_registration, default_roles, submission_limits, otp_length, otp_resend_after, passkey_settings, passkey_policy, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, signing_key_id, client_assertion_max_lifetime_seconds, security_profile, logo_light, logo_dark FROM challenge_settings ORDER BY tenant_id"""
        .query[ChallengeSettingsRecord].run()

  override def findByTenant(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]] =
    xa.connectMeasured("find-challenge-settings-by-tenant"):
      sql"""SELECT tenant_id, phone_regions, email_registration, default_roles, submission_limits, otp_length, otp_resend_after, passkey_settings, passkey_policy, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, signing_key_id, client_assertion_max_lifetime_seconds, security_profile, logo_light, logo_dark FROM challenge_settings WHERE tenant_id = $tenantId"""
        .query[ChallengeSettingsRecord].run()
        .headOption

  override def upsert(record: ChallengeSettingsRecord): Task[Boolean] =
    xa.connectMeasured("upsert-challenge-settings"):
      sql"""
        INSERT INTO challenge_settings (tenant_id, phone_regions, email_registration, default_roles, submission_limits, otp_length, otp_resend_after, passkey_settings, passkey_policy, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, signing_key_id, client_assertion_max_lifetime_seconds, security_profile, logo_light, logo_dark)
        VALUES (${record.tenantId}, ${record.phoneRegions}, ${record.emailRegistration}, ${record.defaultRoles}, ${record.submissionLimits}, ${record.otpLength}, ${record.otpResendAfter}, ${record.passkeySettings}, ${record.passkeyPolicy}, ${record.authConversationTtlSeconds}, ${record.sessionTtlSeconds}, ${record.sessionIdleTtlSeconds}, ${record.userAgentTtlSeconds}, ${record.ipHeader}, ${record.acrVocabulary}, ${record.postLogoutRedirectUris}, ${record.requireDpopNonce}, ${record.mtlsCertificateHeader}, ${record.mtlsCertificateEncoding}, ${record.signingKeyId}, ${record.clientAssertionMaxLifetimeSeconds}, ${record.securityProfile}, ${record.logoLight}, ${record.logoDark})
        ON CONFLICT (tenant_id) DO UPDATE SET
          phone_regions = EXCLUDED.phone_regions,
          email_registration = EXCLUDED.email_registration,
          default_roles = EXCLUDED.default_roles,
          submission_limits = EXCLUDED.submission_limits,
          otp_length = EXCLUDED.otp_length,
          otp_resend_after = EXCLUDED.otp_resend_after,
          passkey_settings = EXCLUDED.passkey_settings,
          passkey_policy = EXCLUDED.passkey_policy,
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
          signing_key_id = EXCLUDED.signing_key_id,
          client_assertion_max_lifetime_seconds = EXCLUDED.client_assertion_max_lifetime_seconds,
          logo_light = EXCLUDED.logo_light,
          logo_dark = EXCLUDED.logo_dark
        WHERE challenge_settings.security_profile = EXCLUDED.security_profile
      """.update.run() > 0

  // READ COMMITTED: a concurrent patch of the same tenant waits on FOR UPDATE and then reads the row as the first one
  // committed it, so both changes are applied to the latest version. `FOR UPDATE` on the row, not an advisory lock:
  // the row exists (a missing one is a 404), so it is the natural thing to lock.
  override def patch(
      tenantId: TenantId,
      patch: PatchChallengeSettingsRequest,
  ): Task[ChallengeSettingsPatchResult] =
    xa.transactMeasured("patch-challenge-settings"):
      val locked =
        sql"""SELECT tenant_id, phone_regions, email_registration, default_roles, submission_limits, otp_length, otp_resend_after, passkey_settings, passkey_policy, auth_conversation_ttl_seconds, session_ttl_seconds, session_idle_ttl_seconds, user_agent_ttl_seconds, ip_header, acr_vocabulary, post_logout_redirect_uris, require_dpop_nonce, mtls_certificate_header, mtls_certificate_encoding, signing_key_id, client_assertion_max_lifetime_seconds, security_profile, logo_light, logo_dark FROM challenge_settings WHERE tenant_id = $tenantId FOR UPDATE"""
          .query[ChallengeSettingsRecord].run()
          .headOption
      locked match
        case None => ChallengeSettingsPatchResult.NotFound
        case Some(current) =>
          patch.applyTo(current) match
            case Left(reason) => ChallengeSettingsPatchResult.Invalid(reason)
            case Right(record) =>
              sql"""
                UPDATE challenge_settings SET
                  phone_regions = ${record.phoneRegions},
                  email_registration = ${record.emailRegistration},
                  default_roles = ${record.defaultRoles},
                  submission_limits = ${record.submissionLimits},
                  otp_length = ${record.otpLength},
                  otp_resend_after = ${record.otpResendAfter},
                  passkey_settings = ${record.passkeySettings},
                  passkey_policy = ${record.passkeyPolicy},
                  auth_conversation_ttl_seconds = ${record.authConversationTtlSeconds},
                  session_ttl_seconds = ${record.sessionTtlSeconds},
                  session_idle_ttl_seconds = ${record.sessionIdleTtlSeconds},
                  user_agent_ttl_seconds = ${record.userAgentTtlSeconds},
                  ip_header = ${record.ipHeader},
                  acr_vocabulary = ${record.acrVocabulary},
                  post_logout_redirect_uris = ${record.postLogoutRedirectUris},
                  require_dpop_nonce = ${record.requireDpopNonce},
                  mtls_certificate_header = ${record.mtlsCertificateHeader},
                  mtls_certificate_encoding = ${record.mtlsCertificateEncoding},
                  signing_key_id = ${record.signingKeyId},
                  client_assertion_max_lifetime_seconds = ${record.clientAssertionMaxLifetimeSeconds},
                  logo_light = ${record.logoLight},
                  logo_dark = ${record.logoDark}
                WHERE tenant_id = ${record.tenantId}
              """.update.run()
              ChallengeSettingsPatchResult.Patched(record)

object PostgresChallengeSettingsRepository:
  def live: ZLayer[TransactorZIO, Nothing, ChallengeSettingsRepository] =
    ZLayer.fromFunction(PostgresChallengeSettingsRepository(_))
