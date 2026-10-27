package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.PageLimit
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.time.Instant

/** The limits of one kind of velocity key, see auth's `VelocityRules.Limit`. 0 is no limit. */
case class VelocityLimitDto(perMinute: Int, perTenMinutes: Int, perHour: Int, perDay: Int, weight: Int) derives JsonCodec, Schema

case class VelocityThresholdsDto(
    phone: VelocityLimitDto,
    ip: VelocityLimitDto,
    device: VelocityLimitDto,
    asn: VelocityLimitDto,
    prefix: VelocityLimitDto,
    trustedDiscount: Int,
) derives JsonCodec, Schema

/** The work a proof-of-work challenge asks, in zero bits of a hash: `minDifficulty` at the challenge score, up to
  * `maxDifficulty`, one more bit for each `scoreStep` of score above it (0: none). */
case class PowDifficultyDto(minDifficulty: Int, maxDifficulty: Int, scoreStep: Int) derives JsonCodec, Schema

case class SmsLimitDto(sentPerHour: Int, minVolume: Int, minConversion: Double) derives JsonCodec, Schema

/** `byPrefix` is keyed by the leading digits of a number, country code included; the longest key a number starts with
  * decides, and `default` applies to a number that starts with none. */
case class SmsLimitsDto(default: SmsLimitDto, byPrefix: Map[String, SmsLimitDto]) derives JsonCodec, Schema

/** What a row of the settings sets; what it leaves out is the layer below's (the tenant's from the global row, that from
  * the configuration of auth). Auth validates it. */
case class AntifraudSettingsPatch(
    delayAt: Option[Int],
    challengeAt: Option[Int],
    reviewAt: Option[Int],
    blockAt: Option[Int],
    delayMillis: Option[Long],
    fakeSendDelayMillis: Option[Long],
    pow: Option[PowDifficultyDto],
    thresholds: Option[VelocityThresholdsDto],
    smsLimits: Option[SmsLimitsDto],
    ruleWeights: Option[Map[String, Int]],
    shadowRules: Option[Set[String]],
) derives JsonCodec, Schema

/** What applies to a scope once the layers are put together. */
case class AntifraudSettingsValues(
    delayAt: Int,
    challengeAt: Int,
    reviewAt: Int,
    blockAt: Int,
    delayMillis: Long,
    fakeSendDelayMillis: Long,
    pow: PowDifficultyDto,
    thresholds: VelocityThresholdsDto,
    smsLimits: SmsLimitsDto,
    ruleWeights: Map[String, Int],
    shadowRules: Set[String],
) derives JsonCodec, Schema

/** `tenantId` is the tenant's row; without it the global one. `userId` is the admin, which edge puts in from the verified
  * token (see [[AntifraudSettingsController]]); auth records it as who made the change. The same body goes to auth. */
case class PutAntifraudSettingsRequest(tenantId: Option[TenantId], settings: AntifraudSettingsPatch, userId: UserId) derives JsonCodec, Schema

case class DeleteAntifraudSettingsRequest(tenantId: Option[TenantId], userId: UserId) derives JsonCodec, Schema

/** `stored` is the row as it is (absent where there is none), `effective` what applies. */
case class AntifraudSettingsResponse(
    tenantId: Option[TenantId],
    stored: Option[AntifraudSettingsPatch],
    effective: AntifraudSettingsValues,
) derives JsonCodec, Schema

case class AntifraudSettingsAuditRecord(
    at: Instant,
    actor: String,
    tenantId: Option[TenantId],
    before: Option[AntifraudSettingsPatch],
    after: Option[AntifraudSettingsPatch],
) derives JsonCodec, Schema

case class AntifraudSettingsAuditResponse(records: List[AntifraudSettingsAuditRecord]) derives JsonCodec, Schema

/** What the audit is narrowed to; `global` is the global row only. */
case class AntifraudSettingsAuditQuery(tenantId: Option[TenantId], global: Option[Boolean], limit: Option[PageLimit])
