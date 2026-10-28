package versola.central.antifraud

import versola.central.users.UserId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

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

/** The settings: what applies to the whole deployment. Auth validates them. */
case class AntifraudSettings(
    challengeAt: Int,
    reviewAt: Int,
    blockAt: Int,
    pow: PowDifficultyDto,
    thresholds: VelocityThresholdsDto,
    smsLimits: SmsLimitsDto,
    ruleWeights: Map[String, Int],
    shadowRules: Set[String],
) derives JsonCodec, Schema

/** `userId` is the admin, which edge puts in from the verified token (see [[AntifraudSettingsController]]); auth records it
  * as who made the change. The same body goes to auth. */
case class PutAntifraudSettingsRequest(settings: AntifraudSettings, userId: UserId) derives JsonCodec, Schema

case class DeleteAntifraudSettingsRequest(userId: UserId) derives JsonCodec, Schema
