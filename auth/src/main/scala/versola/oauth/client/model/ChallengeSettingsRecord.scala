package versola.oauth.client.model

import zio.json.JsonCodec

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives JsonCodec

case class ChallengeSettingsRecord(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Int,
    sessionTtlSeconds: Int,
    sessionIdleTtlSeconds: Option[Int],
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[PassedAuthFactor]]],
) derives JsonCodec
