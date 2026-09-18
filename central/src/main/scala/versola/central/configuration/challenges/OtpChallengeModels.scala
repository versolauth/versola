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
    /** The `kid` of the JWKS key this tenant signs with. Null clears the selection, falling
      * back to auth's configured private key; absent keeps the stored one. Rejected unless
      * central holds a private key for that kid -- see `ChallengeSettingsService`.
      *
      * No default: a field with one decodes an explicit `null` as the default rather than as
      * `Some(Deleted)`, which would leave the selection impossible to clear. */
    signingKeyId: Option[Patch[String]],
    /** RFC 7523 §3: furthest into the future a client assertion's `exp` may sit; absent
      * keeps the stored value. */
    clientAssertionMaxLifetimeSeconds: Option[Int] = None,
) derives Schema, JsonCodec