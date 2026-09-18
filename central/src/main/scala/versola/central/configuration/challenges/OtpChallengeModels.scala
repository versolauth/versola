package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.Patch
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
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Option[Int],
    sessionTtlSeconds: Option[Int],
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Option[Int],
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: Option[List[String]],
    requireDpopNonce: Option[Boolean] = None,
    /** Null clears the header, which is how a tenant's mutual TLS is turned off; absent
      * keeps the stored value. Sent together with `mtlsCertificateEncoding` -- a header with
      * no encoding cannot be parsed, and an encoding with no header names nothing. */
    mtlsCertificateHeader: Option[Patch[String]],
    mtlsCertificateEncoding: Option[Patch[MtlsCertificateEncoding]],
) derives Schema, JsonCodec