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
    userAgentTtlSeconds: Int,
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[PassedAuthFactor]]],
    postLogoutRedirectUris: List[String],
    /** RFC 9449 §8: whether a proof from one of this tenant's clients must carry a
      * server-issued nonce -- see [[versola.oauth.client.OAuthConfigurationService.requireDpopNonce]]. */
    requireDpopNonce: Boolean,
) derives JsonCodec
