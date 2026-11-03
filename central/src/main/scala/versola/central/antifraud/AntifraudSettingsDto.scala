package versola.central.antifraud

import versola.central.users.UserId
import versola.util.{JsonStringSchema, Patch}
import zio.json.ast.Json
import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.schema.{Schema, derived}

/** Whether the engine's decision is acted on (`enforce`) or only computed and recorded, letting every request through
  * (`shadow`); see auth's `TuningMode`. */
enum AntifraudMode derives JsonCodec:
  case shadow, enforce

object AntifraudMode:
  given Schema[AntifraudMode] = JsonStringSchema.of

/** The limits of one kind of velocity key, see auth's `VelocityRules.Limit`. 0 is no limit. */
@jsonNoExtraFields
case class VelocityLimitDto(perMinute: Int, perTenMinutes: Int, perHour: Int, perDay: Int, weight: Int) derives JsonCodec, Schema

@jsonNoExtraFields
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
@jsonNoExtraFields
case class PowDifficultyDto(minDifficulty: Int, maxDifficulty: Int, scoreStep: Int) derives JsonCodec, Schema

@jsonNoExtraFields
case class SmsLimitDto(sentPerHour: Int, minVolume: Int, minConversion: Double) derives JsonCodec, Schema

/** `byPrefix` is keyed by the leading digits of a number, country code included; the longest key a number starts with
  * decides, and `default` applies to a number that starts with none. */
@jsonNoExtraFields
case class SmsLimitsDto(default: SmsLimitDto, byPrefix: Map[String, SmsLimitDto]) derives JsonCodec, Schema

/** The settings: what applies to the whole deployment. Auth validates them. `mode` is required: a `PUT` replaces the
  * settings as a whole, and one that left it out would silently put an enforcing deployment back in `shadow`. A member that is
  * not one of these, here and in the classes nested in it, is refused (`@jsonNoExtraFields`) instead of dropped. */
@jsonNoExtraFields
case class AntifraudSettings(
    mode: AntifraudMode,
    challengeAt: Int,
    blockAt: Int,
    pow: PowDifficultyDto,
    thresholds: VelocityThresholdsDto,
    smsLimits: SmsLimitsDto,
    ruleWeights: Map[String, Int],
    shadowRules: Set[String],
) derives JsonCodec, Schema

/** `userId` is the admin, which edge puts in from the verified token (see [[AntifraudSettingsController]]); auth records it
  * as who made the change. `version` is the version the settings were read at, for a client that cannot send `If-Match`
  * (which is preferred); the same body goes to auth, with the version the request named in either. */
case class PutAntifraudSettingsRequest(settings: AntifraudSettings, userId: UserId, version: Option[Long] = None)
  derives JsonCodec, Schema

case class DeleteAntifraudSettingsRequest(userId: UserId) derives JsonCodec, Schema

/** The patch DTOs mirror the settings DTOs above member for member, each member an `Option[Patch[...]]` (see [[Patch]]): absent
  * leaves the setting, a value sets it, `null` puts it back to its default (everything off, `shadow`); in `ruleWeights` and
  * `smsLimits.byPrefix` a `null` entry removes it and a value replaces it whole. No member has a default, because zio-json
  * reads a `null` for one that has as the default. Auth has its own mirror of its `Tuning` (central does not depend on auth). */
@jsonNoExtraFields
case class VelocityLimitPatchDto(
    perMinute: Option[Patch[Int]],
    perTenMinutes: Option[Patch[Int]],
    perHour: Option[Patch[Int]],
    perDay: Option[Patch[Int]],
    weight: Option[Patch[Int]],
) derives JsonCodec, Schema

object VelocityLimitPatchDto:
  /** Changes nothing. */
  val empty: VelocityLimitPatchDto = VelocityLimitPatchDto(None, None, None, None, None)

@jsonNoExtraFields
case class VelocityThresholdsPatchDto(
    phone: Option[Patch[VelocityLimitPatchDto]],
    ip: Option[Patch[VelocityLimitPatchDto]],
    device: Option[Patch[VelocityLimitPatchDto]],
    asn: Option[Patch[VelocityLimitPatchDto]],
    prefix: Option[Patch[VelocityLimitPatchDto]],
    trustedDiscount: Option[Patch[Int]],
) derives JsonCodec, Schema

object VelocityThresholdsPatchDto:
  /** Changes nothing. */
  val empty: VelocityThresholdsPatchDto = VelocityThresholdsPatchDto(None, None, None, None, None, None)

@jsonNoExtraFields
case class PowDifficultyPatchDto(
    minDifficulty: Option[Patch[Int]],
    maxDifficulty: Option[Patch[Int]],
    scoreStep: Option[Patch[Int]],
) derives JsonCodec, Schema

object PowDifficultyPatchDto:
  /** Changes nothing. */
  val empty: PowDifficultyPatchDto = PowDifficultyPatchDto(None, None, None)

@jsonNoExtraFields
case class SmsLimitPatchDto(
    sentPerHour: Option[Patch[Int]],
    minVolume: Option[Patch[Int]],
    minConversion: Option[Patch[Double]],
) derives JsonCodec, Schema

object SmsLimitPatchDto:
  /** Changes nothing. */
  val empty: SmsLimitPatchDto = SmsLimitPatchDto(None, None, None)

/** A `byPrefix` entry that is a value is a whole [[SmsLimitDto]], as in a `PUT`. */
@jsonNoExtraFields
case class SmsLimitsPatchDto(
    default: Option[Patch[SmsLimitPatchDto]],
    byPrefix: Option[Patch[Map[String, Patch[SmsLimitDto]]]],
) derives JsonCodec, Schema

object SmsLimitsPatchDto:
  /** Changes nothing. */
  val empty: SmsLimitsPatchDto = SmsLimitsPatchDto(None, None)

/** The body of `PATCH /antifraud/settings`, without the `userId` edge added and the `version` the request may carry. It is
  * still a JSON merge patch on the wire (`application/merge-patch+json`), but its members are the settings' own, so a member
  * that is not one is refused. Auth applies it to the stored settings and validates the result. */
@jsonNoExtraFields
case class AntifraudSettingsPatch(
    mode: Option[Patch[AntifraudMode]],
    challengeAt: Option[Patch[Int]],
    blockAt: Option[Patch[Int]],
    pow: Option[Patch[PowDifficultyPatchDto]],
    thresholds: Option[Patch[VelocityThresholdsPatchDto]],
    smsLimits: Option[Patch[SmsLimitsPatchDto]],
    ruleWeights: Option[Patch[Map[String, Patch[Int]]]],
    shadowRules: Option[Patch[Set[String]]],
) derives JsonCodec, Schema

object AntifraudSettingsPatch:
  /** Changes nothing. */
  val empty: AntifraudSettingsPatch = AntifraudSettingsPatch(None, None, None, None, None, None, None, None)

/** What the controller reads of a patch request around the patch: `userId` is the admin, which edge adds from the verified
  * token, and `version` the version the patch is made on, when the caller named one. */
case class PatchEnvelope(userId: UserId, version: Option[Long]) derives JsonCodec

/** The patch to auth, with who asks and the version it is made on (`If-Match` or the body's, see
  * [[AntifraudSettingsController]]); it is sent in the shape of a [[PutAntifraudSettingsRequest]]. */
case class PatchAntifraudSettingsRequest(patch: AntifraudSettingsPatch, userId: UserId, version: Option[Long] = None)
  derives JsonCodec, Schema

/** The settings and the version of the row they are stored in (0 where there is none): what a read answers (the settings
  * flat, as ever, with `version` next to them, and the version as the `ETag`) and what a change is made on. */
case class VersionedAntifraudSettings(settings: AntifraudSettings, version: Long)

object VersionedAntifraudSettings:
  import zio.json.ast.Json
  import zio.json.{EncoderOps, JsonDecoder, JsonEncoder}

  given JsonEncoder[VersionedAntifraudSettings] = JsonEncoder[Json].contramap: versioned =>
    versioned.settings.toJsonAST.toOption
      .collect { case obj: Json.Obj => obj.add("version", Json.Num(versioned.version)) }
      .getOrElse(Json.Null)

  given JsonDecoder[VersionedAntifraudSettings] = JsonDecoder[Json.Obj].mapOrFail: obj =>
    for
      // The settings refuse a member they do not have, and `version` is not one of them.
      settings <- Json.Obj(obj.fields.filterNot((name, _) => name == "version")).as[AntifraudSettings]
      version <- obj.get("version").toRight("version is missing").flatMap(_.as[Long])
    yield VersionedAntifraudSettings(settings, version)
