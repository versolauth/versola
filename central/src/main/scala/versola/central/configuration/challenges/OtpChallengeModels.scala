package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class GetOtpTemplatesResponse(templates: Vector[OtpTemplateRecord]) derives Schema, JsonCodec

case class UpsertOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
) derives Schema, JsonCodec

case class DeleteOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
) derives Schema, JsonCodec

case class GetChallengeSettingsResponse(settings: Option[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class GetAllChallengeSettingsResponse(settings: Vector[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class UpsertChallengeSettingsRequest(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    passwordRegex: Option[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    passwordHistorySize: Option[Int],
    passwordNumDifferent: Option[Int],
    authConversationTtlSeconds: Option[Int],
    sessionTtlSeconds: Option[Int],
    sessionIdleTtlSeconds: Option[Int],
) derives Schema, JsonCodec
