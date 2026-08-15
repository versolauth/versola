package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

enum OtpTemplatePurpose derives Schema, JsonCodec:
  case otp, password

enum OtpTemplateChannel derives Schema, JsonCodec:
  case sms, email

case class GetOtpTemplatesResponse(templates: Vector[OtpTemplateRecord]) derives Schema, JsonCodec

case class UpsertOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    localizations: Map[String, String],
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
) derives Schema, JsonCodec

case class DeleteOtpTemplateRequest(
    id: String,
    tenantId: TenantId,
    purpose: OtpTemplatePurpose,
    channel: OtpTemplateChannel,
) derives Schema, JsonCodec

case class GetChallengeSettingsResponse(settings: Option[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class GetAllChallengeSettingsResponse(settings: Vector[ChallengeSettingsRecord]) derives Schema, JsonCodec

case class UpsertChallengeSettingsRequest(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    temporaryPasswordTtlSeconds: Option[Int],
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Option[Int],
    sessionTtlSeconds: Option[Int],
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Option[Int],
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: Option[List[String]],
) derives Schema, JsonCodec