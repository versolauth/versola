package versola.oauth.client.model

import zio.json.JsonCodec

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives JsonCodec

/** Mirrors central's `MtlsCertificateEncoding` -- see there for what each case means. */
enum MtlsCertificateEncoding derives JsonCodec:
  case urlEncodedPem
  case base64Der

/** Where a client certificate is to be read from for one tenant, once the two settings that
  * are only meaningful together are known to both be present. */
case class MtlsCertificateSource(
    header: String,
    encoding: MtlsCertificateEncoding,
)

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
    mtlsCertificateHeader: Option[String],
    mtlsCertificateEncoding: Option[MtlsCertificateEncoding],
    /** RFC 7523 §3: furthest into the future a client assertion's `exp` may sit, and so how
      * long its `jti` is remembered against replay -- see
      * [[versola.oauth.client.OAuthConfigurationService.getClientAssertionMaxLifetime]]. */
    clientAssertionMaxLifetimeSeconds: Int,
) derives JsonCodec

object ChallengeSettingsRecord:
  /** Mirrors central's default, and applies where a tenant has no settings row at all. */
  val DefaultClientAssertionMaxLifetime: zio.Duration = zio.Duration.fromSeconds(300)
