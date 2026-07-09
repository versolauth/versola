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
    passwordRegex: Option[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    passwordHistorySize: Int,
    passwordNumDifferent: Int,
    authConversationTtlSeconds: Int,
    sessionTtlSeconds: Int,
    sessionIdleTtlSeconds: Option[Int],
    ipHeader: String,
) derives JsonCodec
