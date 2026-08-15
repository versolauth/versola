package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives Schema, JsonCodec

case class ChallengeSettingsRecord(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    temporaryPasswordTtlSeconds: Int,
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Int,
    sessionTtlSeconds: Int,
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Int,
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: List[String],
) derives Schema, JsonCodec

object ChallengeSettingsRecord:
  val DefaultTemporaryPasswordTtlSeconds: Int = 12 * 60 * 60