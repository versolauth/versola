package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.*
import zio.json.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** What an operator changes while an attack is going on: the thresholds the score turns into an action at, the weights
  * of the rules, which rules only record, the limits on codes sent to a range of numbers, and the timing of a delay
  * and of a fake send. Not deployment configuration, because a restart is too slow for it and empties the in-memory
  * counters; it is stored in `af_settings` (see [[AntifraudTuning]]) and changed through the admin API.
  *
  * @param delayAt        score from which a step is delayed; 0 disables the threshold (likewise the next three)
  * @param challengeAt    score from which a step is challenged
  * @param reviewAt       score from which a step is sent to review
  * @param blockAt        score from which a step is refused
  * @param delayMillis    how long a `Delay` holds a request back
  * @param fakeSendDelayMillis how long a fake send waits; 0 waits as long as the recent real sends took
  * @param thresholds     the limits (and weights) of the velocity rules
  * @param smsLimits      the limits on codes sent to a range of numbers
  * @param ruleWeights    the weight of a rule by its name, in place of the one it has; a rule that forces an action is not
  *                       weighed
  * @param shadowRules    rules that are evaluated and listed in the fired rules as `shadow:<name>`, but add no weight
  *                       and force nothing: a dry run before the rule is made real
  */
case class Tuning(
    delayAt: Int,
    challengeAt: Int,
    reviewAt: Int,
    blockAt: Int,
    delayMillis: Long,
    fakeSendDelayMillis: Long,
    thresholds: VelocityRules.Thresholds,
    smsLimits: SmsLimits,
    ruleWeights: Map[String, Int],
    shadowRules: Set[String],
) derives JsonCodec:
  def delay: Duration = Duration.fromMillis(delayMillis)

/** The limit on codes sent to one range of numbers, see [[SmsBudget]]. 0 is no limit. */
case class SmsLimit(sentPerHour: Int, minVolume: Int, minConversion: Double) derives JsonCodec:
  def enabled: Boolean = sentPerHour > 0 || minVolume > 0

/** `byPrefix` is keyed by the leading digits of a number, country code included (`"7701"`): the longest key a number
  * starts with decides, and `default` applies to a number that starts with none. */
case class SmsLimits(default: SmsLimit, byPrefix: Map[String, SmsLimit]) derives JsonCodec:
  def of(prefix: String): SmsLimit =
    byPrefix.keysIterator.filter(prefix.startsWith).maxByOption(_.length).fold(default)(byPrefix)

  def enabled: Boolean = default.enabled || byPrefix.values.exists(_.enabled)

/** The part of a [[Tuning]] a row of `af_settings` sets; what it leaves out is taken from the row below it (the tenant's
  * from the global one, that from the configuration). A map or the thresholds are replaced as a whole. */
case class TuningPatch(
    delayAt: Option[Int],
    challengeAt: Option[Int],
    reviewAt: Option[Int],
    blockAt: Option[Int],
    delayMillis: Option[Long],
    fakeSendDelayMillis: Option[Long],
    thresholds: Option[VelocityRules.Thresholds],
    smsLimits: Option[SmsLimits],
    ruleWeights: Option[Map[String, Int]],
    shadowRules: Option[Set[String]],
) derives JsonCodec:
  def over(base: Tuning): Tuning =
    Tuning(
      delayAt = delayAt.getOrElse(base.delayAt),
      challengeAt = challengeAt.getOrElse(base.challengeAt),
      reviewAt = reviewAt.getOrElse(base.reviewAt),
      blockAt = blockAt.getOrElse(base.blockAt),
      delayMillis = delayMillis.getOrElse(base.delayMillis),
      fakeSendDelayMillis = fakeSendDelayMillis.getOrElse(base.fakeSendDelayMillis),
      thresholds = thresholds.getOrElse(base.thresholds),
      smsLimits = smsLimits.getOrElse(base.smsLimits),
      ruleWeights = ruleWeights.getOrElse(base.ruleWeights),
      shadowRules = shadowRules.getOrElse(base.shadowRules),
    )

  /** The timings belong to the whole deployment: a request is held and a fake send waits where no tenant is known. */
  def setsTimings: Boolean = delayMillis.isDefined || fakeSendDelayMillis.isDefined

/** One change of `af_settings`, with what the row was and became (`None`: no row), as the JSON of a [[TuningPatch]]. */
case class AntifraudTuningAudit(
    at: Instant,
    actor: String,
    tenantId: Option[TenantId],
    before: Option[TuningPatch],
    after: Option[TuningPatch],
) derives JsonCodec

/** A change that is not valid: a negative limit, a limit above what can be meant, a prefix that is not digits. */
final class InvalidTuning(message: String) extends RuntimeException(message)

/** The tuning the rules and the budget read, and the internal API that changes it.
  *
  * ==Layers==
  * Per tenant, the configuration (`antifraud.decision`, `velocity.thresholds` and `sms-budget` of the config, see
  * [[Tuning.fromSettings]]) is what applies where nothing is stored; the global row of `af_settings` is laid over it,
  * and the tenant's row over that. Rows are patches: a key a row leaves out is the layer below's.
  *
  * ==Reading==
  * Every instance holds the result for every tenant that has a row, in memory; a request reads it without touching the
  * database. It is rebuilt when `af_settings_version` changes (checked every `refresh-interval`) and, whatever the version
  * says, once a minute. A refresh that fails keeps what there is (nothing is lost: the rules go on with the last
  * tuning, or with the configuration at start). A change made through this instance applies on it at once and on the
  * others within the interval.
  */
trait AntifraudTuning:
  /** What applies to a request of the tenant (`None`: where no tenant is known). */
  def effective(tenant: Option[TenantId]): Tuning

  /** Whether any tenant, or none, has a limit on codes sent: false lets the budget be skipped without looking the tenant up. */
  def smsLimitsEnabled: Boolean

  /** The row of the scope as stored, `None` where there is none. */
  def stored(tenant: Option[TenantId]): Task[Option[TuningPatch]]

  /** Replaces the row of the scope. Fails with [[InvalidTuning]]. */
  def put(tenant: Option[TenantId], patch: TuningPatch, actor: String): Task[Unit]

  /** Removes the row of the scope, which then follows the layer below; false when there was none. */
  def delete(tenant: Option[TenantId], actor: String): Task[Boolean]

  /** The changes, the latest first; of one scope when `scope` is given (`Some(None)` is the global one). */
  def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]]

object AntifraudTuning:

  /** The most audit records one call returns. */
  val MaxLimit = 500

  private val FullReloadSeconds = 60L

  private val MaxScore = 100000
  private val MaxWeight = 100000
  private val MaxLimitValue = 100000000
  private val MaxDelayMillis = 60000L

  /** @param refreshInterval how often the version of the stored tuning is looked at, so how long a change takes to
    *                        reach another instance */
  case class Settings(refreshInterval: Duration)

  /** What applies where nothing is stored: the keys of the configuration. */
  def fromSettings(settings: AntifraudEvents.Settings): Tuning =
    Tuning(
      delayAt = settings.decision.delayAt,
      challengeAt = settings.decision.challengeAt,
      reviewAt = settings.decision.reviewAt,
      blockAt = settings.decision.blockAt,
      delayMillis = settings.decision.delay.toMillis,
      fakeSendDelayMillis = 0L,
      thresholds = settings.velocity.thresholds,
      smsLimits = SmsLimits(
        default = SmsLimit(
          sentPerHour = settings.smsBudget.sentPerHour,
          minVolume = settings.smsBudget.minVolume,
          minConversion = settings.smsBudget.minConversion,
        ),
        byPrefix = Map.empty,
      ),
      ruleWeights = Map.empty,
      shadowRules = Set.empty,
    )

  /** Why a patch is not valid, if it is not. `tenant` is whether it is for one tenant. */
  def invalid(patch: TuningPatch, tenant: Boolean): Option[String] =
    def score(name: String, value: Option[Int]) = value.filter(v => v < 0 || v > MaxScore).map(_ => s"$name must be between 0 and $MaxScore")
    def limit(name: String, l: VelocityRules.Limit) =
      List(l.perMinute, l.perTenMinutes, l.perHour, l.perDay).find(v => v < 0 || v > MaxLimitValue).map(_ => s"$name limits must be between 0 and $MaxLimitValue")
        .orElse(Option.when(l.weight < -MaxWeight || l.weight > MaxWeight)(s"$name weight must be between -$MaxWeight and $MaxWeight"))
    def sms(name: String, l: SmsLimit) =
      Option.when(l.sentPerHour < 0 || l.sentPerHour > MaxLimitValue || l.minVolume < 0 || l.minVolume > MaxLimitValue)(s"$name counts must be between 0 and $MaxLimitValue")
        .orElse(Option.when(l.minConversion.isNaN || l.minConversion < 0 || l.minConversion > 1)(s"$name minConversion must be between 0 and 1"))
    val rule = "[a-z0-9_:.-]{1,100}".r
    List(
      score("delayAt", patch.delayAt),
      score("challengeAt", patch.challengeAt),
      score("reviewAt", patch.reviewAt),
      score("blockAt", patch.blockAt),
      patch.delayMillis.filter(v => v < 0 || v > MaxDelayMillis).map(_ => s"delayMillis must be between 0 and $MaxDelayMillis"),
      patch.fakeSendDelayMillis.filter(v => v < 0 || v > MaxDelayMillis).map(_ => s"fakeSendDelayMillis must be between 0 and $MaxDelayMillis"),
      Option.when(tenant && patch.setsTimings)("delayMillis and fakeSendDelayMillis are for the global settings only"),
      patch.thresholds.flatMap: t =>
        List("phone" -> t.phone, "ip" -> t.ip, "device" -> t.device, "asn" -> t.asn, "prefix" -> t.prefix).flatMap(limit.tupled).headOption
          .orElse(Option.when(t.trustedDiscount < 0 || t.trustedDiscount > MaxWeight)(s"trustedDiscount must be between 0 and $MaxWeight")),
      patch.smsLimits.flatMap: l =>
        sms("default", l.default)
          .orElse(l.byPrefix.collectFirst { case (prefix, _) if prefix.isEmpty || prefix.length > 15 || !prefix.forall(_.isDigit) => s"prefix '$prefix' must be 1 to 15 digits" })
          .orElse(l.byPrefix.flatMap((prefix, limit) => sms(prefix, limit)).headOption)
          .orElse(Option.when(l.byPrefix.size > 500)("at most 500 prefixes")),
      patch.ruleWeights.flatMap: weights =>
        weights.collectFirst { case (name, weight) if !rule.matches(name) || weight < -MaxWeight || weight > MaxWeight => s"rule weight '$name' is not valid" }
          .orElse(Option.when(weights.size > 200)("at most 200 rule weights")),
      patch.shadowRules.flatMap: names =>
        names.collectFirst { case name if !rule.matches(name) => s"shadow rule '$name' is not valid" }
          .orElse(Option.when(names.size > 200)("at most 200 shadow rules")),
    ).flatten.headOption

  /** The tuning of every tenant that has a row, and of the rest (`global`). */
  final class Snapshot(val global: Tuning, val tenants: Map[TenantId, Tuning]):
    val smsLimitsEnabled: Boolean = global.smsLimits.enabled || tenants.values.exists(_.smsLimits.enabled)

    def of(tenant: Option[TenantId]): Tuning = tenant.flatMap(tenants.get).getOrElse(global)

  def snapshot(base: Tuning, rows: List[(Option[TenantId], TuningPatch)]): Snapshot =
    val global = rows.collectFirst { case (None, patch) => patch }.fold(base)(_.over(base))
    Snapshot(global, rows.collect { case (Some(tenant), patch) => tenant -> patch.over(global) }.toMap)

  /** Starts the refresh on a fiber of its own, which stops with the scope. */
  val live: ZLayer[AntifraudSettingsRepository & AntifraudEvents.Settings & Settings & Scope, Nothing, AntifraudTuning] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudSettingsRepository]
        events <- ZIO.service[AntifraudEvents.Settings]
        settings <- ZIO.service[Settings]
        tuning = Impl(repository, fromSettings(events))
        _ <- tuning.refresh
        _ <- (ZIO.sleep(settings.refreshInterval) *> tuning.refresh).forever.forkScoped
      yield tuning

  class Impl(repository: AntifraudSettingsRepository, base: Tuning) extends AntifraudTuning:
    private val current = AtomicReference(snapshot(base, Nil))
    private val loadedVersion = AtomicReference(-1L)
    private val loadedAt = AtomicReference(Instant.MIN)

    override def effective(tenant: Option[TenantId]): Tuning = current.get.of(tenant)

    override def smsLimitsEnabled: Boolean = current.get.smsLimitsEnabled

    override def stored(tenant: Option[TenantId]): Task[Option[TuningPatch]] = repository.find(tenant)

    override def put(tenant: Option[TenantId], patch: TuningPatch, actor: String): Task[Unit] =
      ZIO.foreachDiscard(invalid(patch, tenant.isDefined))(message => ZIO.fail(InvalidTuning(message))) *>
        Clock.instant.flatMap(repository.put(tenant, patch, actor, _)) *> refresh

    override def delete(tenant: Option[TenantId], actor: String): Task[Boolean] =
      Clock.instant.flatMap(repository.delete(tenant, actor, _)).tap(_ => refresh)

    override def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]] =
      repository.audit(scope, math.max(1, math.min(limit, MaxLimit)))

    /** Rebuilds the snapshot when the version moved or a minute has passed; a failure keeps the snapshot there is. */
    val refresh: UIO[Unit] =
      (for
        now <- Clock.instant
        version <- repository.version
        stale = version != loadedVersion.get || now.isAfter(loadedAt.get.plusSeconds(FullReloadSeconds))
        _ <- ZIO.when(stale):
          repository.loadAll.map: rows =>
            current.set(snapshot(base, rows))
            loadedVersion.set(version)
            loadedAt.set(now)
      yield ()).catchAllCause(_ => AntifraudMetrics.tuningRefreshFailed)
