package versola.antifraud

import versola.util.Patch
import versola.util.Patch.applyTo
import zio.json.*

/** A change to [[Tuning]], typed: the body of `PATCH /antifraud/settings`, which is still a JSON merge patch on the wire
  * (`application/merge-patch+json`) but whose members are fixed here, so that a member it does not know is refused instead of
  * dropped. It mirrors [[Tuning]] member for member (`TuningPatchSpec` fails when the two drift apart), every one an
  * `Option[Patch[...]]` read as [[Patch]] reads it:
  *
  *   - absent: the setting is left as it is,
  *   - a value: the setting is set to it (a nested object is changed member by member, a set is replaced whole),
  *   - `null` ([[Patch.Deleted]]): the setting is reset to what it is in [[Tuning.off]]. Every setting has such a default, so a
  *     scalar is never left without a value; a map entry (`smsLimits.byPrefix`, `ruleWeights`) with `null` is removed, and a
  *     map with `null` is emptied.
  *
  * A `byPrefix` entry is replaced as a whole by a value, not merged into the one there is.
  *
  * No member has a default: zio-json reads a `null` for a member that has one as the default, which would turn a removal into
  * "no change". [[TuningPatch.empty]] is the patch that changes nothing.
  *
  * Reading is validation of the shape only; the range of each value and the combinations that can only be a mistake are
  * checked by [[AntifraudTuningStore.invalid]] on what [[applyTo]] makes.
  */
@jsonNoExtraFields
case class TuningPatch(
    mode: Option[Patch[TuningMode]],
    challengeAt: Option[Patch[Int]],
    blockAt: Option[Patch[Int]],
    pow: Option[Patch[PowDifficultyPatch]],
    thresholds: Option[Patch[ThresholdsPatch]],
    smsLimits: Option[Patch[SmsLimitsPatch]],
    ruleWeights: Option[Patch[Map[String, Patch[Int]]]],
    shadowRules: Option[Patch[Set[String]]],
) derives JsonCodec:

  def applyTo(current: Tuning): Tuning =
    val off = Tuning.off
    Tuning(
      mode = TuningPatch.scalar(mode, current.mode, off.mode),
      challengeAt = TuningPatch.scalar(challengeAt, current.challengeAt, off.challengeAt),
      blockAt = TuningPatch.scalar(blockAt, current.blockAt, off.blockAt),
      pow = TuningPatch.nested(pow, current.pow, off.pow)(_.applyTo(_)),
      thresholds = TuningPatch.nested(thresholds, current.thresholds, off.thresholds)(_.applyTo(_)),
      smsLimits = TuningPatch.nested(smsLimits, current.smsLimits, off.smsLimits)(_.applyTo(_)),
      ruleWeights = TuningPatch.entries(ruleWeights, current.ruleWeights, off.ruleWeights),
      shadowRules = TuningPatch.scalar(shadowRules, current.shadowRules, off.shadowRules),
    )

object TuningPatch:

  /** Changes nothing. */
  val empty: TuningPatch = TuningPatch(None, None, None, None, None, None, None, None)

  /** A setting that is always there: kept, set, or put back to `default`. */
  private[antifraud] def scalar[A](patch: Option[Patch[A]], current: A, default: A): A =
    patch match
      case None => current
      case Some(Patch.Deleted) => default
      case Some(Patch.Modified(value)) => value

  /** A group of settings: kept, put back to `default` as a whole, or changed member by member. */
  private[antifraud] def nested[A, P](patch: Option[Patch[P]], current: A, default: A)(change: (P, A) => A): A =
    patch match
      case None => current
      case Some(Patch.Deleted) => default
      case Some(Patch.Modified(changes)) => change(changes, current)

  /** A map: kept, emptied (to `default`), or changed by entry, `null` removing the entry and a value replacing it whole. */
  private[antifraud] def entries[V](patch: Option[Patch[Map[String, Patch[V]]]], current: Map[String, V], default: Map[String, V]): Map[String, V] =
    patch match
      case None => current
      case Some(Patch.Deleted) => default
      case Some(Patch.Modified(changes)) =>
        changes.foldLeft(current):
          case (map, (key, Patch.Deleted)) => map - key
          case (map, (key, Patch.Modified(value))) => map.updated(key, value)

/** See [[PowDifficulty]]. */
@jsonNoExtraFields
case class PowDifficultyPatch(
    minDifficulty: Option[Patch[Int]],
    maxDifficulty: Option[Patch[Int]],
    scoreStep: Option[Patch[Int]],
) derives JsonCodec:

  def applyTo(current: PowDifficulty): PowDifficulty =
    val off = Tuning.off.pow
    PowDifficulty(
      minDifficulty = TuningPatch.scalar(minDifficulty, current.minDifficulty, off.minDifficulty),
      maxDifficulty = TuningPatch.scalar(maxDifficulty, current.maxDifficulty, off.maxDifficulty),
      scoreStep = TuningPatch.scalar(scoreStep, current.scoreStep, off.scoreStep),
    )

object PowDifficultyPatch:
  /** Changes nothing. */
  val empty: PowDifficultyPatch = PowDifficultyPatch(None, None, None)

/** See [[VelocityRules.Limit]]. */
@jsonNoExtraFields
case class LimitPatch(
    perMinute: Option[Patch[Int]],
    perTenMinutes: Option[Patch[Int]],
    perHour: Option[Patch[Int]],
    perDay: Option[Patch[Int]],
    weight: Option[Patch[Int]],
) derives JsonCodec:

  /** `default` is the limit of the same key in [[Tuning.off]]: the keys have weights of their own. */
  def applyTo(current: VelocityRules.Limit, default: VelocityRules.Limit): VelocityRules.Limit =
    VelocityRules.Limit(
      perMinute = TuningPatch.scalar(perMinute, current.perMinute, default.perMinute),
      perTenMinutes = TuningPatch.scalar(perTenMinutes, current.perTenMinutes, default.perTenMinutes),
      perHour = TuningPatch.scalar(perHour, current.perHour, default.perHour),
      perDay = TuningPatch.scalar(perDay, current.perDay, default.perDay),
      weight = TuningPatch.scalar(weight, current.weight, default.weight),
    )

object LimitPatch:
  /** Changes nothing. */
  val empty: LimitPatch = LimitPatch(None, None, None, None, None)

/** See [[VelocityRules.Thresholds]]. */
@jsonNoExtraFields
case class ThresholdsPatch(
    phone: Option[Patch[LimitPatch]],
    ip: Option[Patch[LimitPatch]],
    device: Option[Patch[LimitPatch]],
    asn: Option[Patch[LimitPatch]],
    prefix: Option[Patch[LimitPatch]],
    trustedDiscount: Option[Patch[Int]],
) derives JsonCodec:

  def applyTo(current: VelocityRules.Thresholds): VelocityRules.Thresholds =
    val off = Tuning.off.thresholds
    def limit(patch: Option[Patch[LimitPatch]], now: VelocityRules.Limit, default: VelocityRules.Limit) =
      TuningPatch.nested(patch, now, default)(_.applyTo(_, default))
    VelocityRules.Thresholds(
      phone = limit(phone, current.phone, off.phone),
      ip = limit(ip, current.ip, off.ip),
      device = limit(device, current.device, off.device),
      asn = limit(asn, current.asn, off.asn),
      prefix = limit(prefix, current.prefix, off.prefix),
      trustedDiscount = TuningPatch.scalar(trustedDiscount, current.trustedDiscount, off.trustedDiscount),
    )

object ThresholdsPatch:
  /** Changes nothing. */
  val empty: ThresholdsPatch = ThresholdsPatch(None, None, None, None, None, None)

/** See [[SmsLimit]]. */
@jsonNoExtraFields
case class SmsLimitPatch(
    sentPerHour: Option[Patch[Int]],
    minVolume: Option[Patch[Int]],
    minConversion: Option[Patch[Double]],
) derives JsonCodec:

  def applyTo(current: SmsLimit, default: SmsLimit): SmsLimit =
    SmsLimit(
      sentPerHour = TuningPatch.scalar(sentPerHour, current.sentPerHour, default.sentPerHour),
      minVolume = TuningPatch.scalar(minVolume, current.minVolume, default.minVolume),
      minConversion = TuningPatch.scalar(minConversion, current.minConversion, default.minConversion),
    )

object SmsLimitPatch:
  /** Changes nothing. */
  val empty: SmsLimitPatch = SmsLimitPatch(None, None, None)

/** See [[SmsLimits]]. `byPrefix` is changed by entry, an entry that is a value replacing the one under that prefix as a whole. */
@jsonNoExtraFields
case class SmsLimitsPatch(
    default: Option[Patch[SmsLimitPatch]],
    byPrefix: Option[Patch[Map[String, Patch[SmsLimit]]]],
) derives JsonCodec:

  def applyTo(current: SmsLimits): SmsLimits =
    val off = Tuning.off.smsLimits
    SmsLimits(
      default = TuningPatch.nested(default, current.default, off.default)(_.applyTo(_, off.default)),
      byPrefix = TuningPatch.entries(byPrefix, current.byPrefix, off.byPrefix),
    )

object SmsLimitsPatch:
  /** Changes nothing. */
  val empty: SmsLimitsPatch = SmsLimitsPatch(None, None)
