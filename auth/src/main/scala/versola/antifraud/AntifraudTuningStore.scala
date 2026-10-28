package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.*
import zio.json.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The part of a [[Tuning]] a row of `af_settings` sets; what it leaves out is taken from the row below it (the tenant's
  * from the global one, that from the configuration). A map or the thresholds are replaced as a whole. */
case class TuningPatch(
    challengeAt: Option[Int],
    reviewAt: Option[Int],
    blockAt: Option[Int],
    fakeSendDelayMillis: Option[Long],
    pow: Option[PowDifficulty],
    thresholds: Option[VelocityRules.Thresholds],
    smsLimits: Option[SmsLimits],
    ruleWeights: Option[Map[String, Int]],
    shadowRules: Option[Set[String]],
) derives JsonCodec:
  def over(base: Tuning): Tuning =
    Tuning(
      challengeAt = challengeAt.getOrElse(base.challengeAt),
      reviewAt = reviewAt.getOrElse(base.reviewAt),
      blockAt = blockAt.getOrElse(base.blockAt),
      fakeSendDelayMillis = fakeSendDelayMillis.getOrElse(base.fakeSendDelayMillis),
      pow = pow.getOrElse(base.pow),
      thresholds = thresholds.getOrElse(base.thresholds),
      smsLimits = smsLimits.getOrElse(base.smsLimits),
      ruleWeights = ruleWeights.getOrElse(base.ruleWeights),
      shadowRules = shadowRules.getOrElse(base.shadowRules),
    )

  /** The whole tuning, if the patch sets every key. */
  def complete: Option[Tuning] =
    for
      challenge <- challengeAt
      review <- reviewAt
      block <- blockAt
      fakeSend <- fakeSendDelayMillis
      difficulty <- pow
      limits <- thresholds
      sms <- smsLimits
      weights <- ruleWeights
      shadow <- shadowRules
    yield Tuning(
      challengeAt = challenge,
      reviewAt = review,
      blockAt = block,
      fakeSendDelayMillis = fakeSend,
      pow = difficulty,
      thresholds = limits,
      smsLimits = sms,
      ruleWeights = weights,
      shadowRules = shadow,
    )

  /** The timing belongs to the whole deployment: a fake send waits where no tenant is known. */
  def setsTimings: Boolean = fakeSendDelayMillis.isDefined

object TuningPatch:
  /** A patch that sets every key to what `tuning` has: how the global row is stored. */
  def of(tuning: Tuning): TuningPatch =
    TuningPatch(
      challengeAt = Some(tuning.challengeAt),
      reviewAt = Some(tuning.reviewAt),
      blockAt = Some(tuning.blockAt),
      fakeSendDelayMillis = Some(tuning.fakeSendDelayMillis),
      pow = Some(tuning.pow),
      thresholds = Some(tuning.thresholds),
      smsLimits = Some(tuning.smsLimits),
      ruleWeights = Some(tuning.ruleWeights),
      shadowRules = Some(tuning.shadowRules),
    )

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

/** The tuning as stored, and the internal API that changes it.
  *
  * ==Rows==
  * `af_settings` has the global row (every key, seeded by migration `V0024`, so there is always one) and one row per tenant
  * that differs from it. A tenant's row is a patch: a key it leaves out is the global row's. A change of the global row is
  * a patch too, over what it is now, and the global row cannot be removed. Nothing in the configuration says what a
  * threshold is: the store is the single source.
  *
  * ==Reading==
  * Every instance holds the result for every tenant that has a row, in memory; a request reads it without touching the
  * database. It is rebuilt when `af_settings_version` changes (checked every `refresh-interval`) and, whatever the version
  * says, once a minute. A refresh that fails keeps the last snapshot that was read. Until the first one has been read
  * ([[AntifraudTuning.available]] false, which is a start with the database unreadable) a request is decided as
  * `failure-action` says. A change made through this instance applies on it at once and on the others within the interval.
  */
trait AntifraudTuningStore extends AntifraudTuning:
  /** The row of the scope as stored, `None` where there is none. */
  def stored(tenant: Option[TenantId]): Task[Option[TuningPatch]]

  /** Changes the row of the scope: replaces a tenant's, or patches the global one. Fails with [[InvalidTuning]]. */
  def put(tenant: Option[TenantId], patch: TuningPatch, actor: String): Task[Unit]

  /** Removes a tenant's row, which then follows the global one; false when there was none. The global row cannot be
    * removed ([[InvalidTuning]]). */
  def delete(tenant: Option[TenantId], actor: String): Task[Boolean]

  /** The changes, the latest first; of one scope when `scope` is given (`Some(None)` is the global one). */
  def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]]

object AntifraudTuningStore:

  /** The most audit records one call returns. */
  val MaxLimit = 500

  private val FullReloadSeconds = 60L

  private val MaxScore = 100000
  private val MaxWeight = 100000
  private val MaxLimitValue = 100000000
  private val MaxDelayMillis = 60000L
  private val MaxDifficulty = 32

  /** @param refreshInterval how often the version of the stored tuning is looked at, so how long a change takes to
    *                        reach another instance */
  case class Settings(refreshInterval: Duration)

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
      score("challengeAt", patch.challengeAt),
      score("reviewAt", patch.reviewAt),
      score("blockAt", patch.blockAt),
      patch.fakeSendDelayMillis.filter(v => v < 0 || v > MaxDelayMillis).map(_ => s"fakeSendDelayMillis must be between 0 and $MaxDelayMillis"),
      patch.pow.flatMap: p =>
        Option.when(p.minDifficulty < 0 || p.maxDifficulty > MaxDifficulty || p.minDifficulty > p.maxDifficulty)(s"pow difficulty must satisfy 0 <= minDifficulty <= maxDifficulty <= $MaxDifficulty")
          .orElse(Option.when(p.scoreStep < 0 || p.scoreStep > MaxScore)(s"pow scoreStep must be between 0 and $MaxScore")),
      Option.when(tenant && patch.setsTimings)("fakeSendDelayMillis is for the global settings only"),
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

  /** `None` where there is no complete global row: nothing is known to be the thresholds then. */
  def snapshot(rows: List[(Option[TenantId], TuningPatch)]): Option[Snapshot] =
    rows.collectFirst { case (None, patch) => patch }.flatMap(_.complete).map: global =>
      Snapshot(global, rows.collect { case (Some(tenant), patch) => tenant -> patch.over(global) }.toMap)

  /** Reads the tuning once, and then on a fiber of its own, which stops with the scope. A start with the database
    * unreadable goes on, with [[AntifraudTuning.available]] false until a read succeeds. */
  val live: ZLayer[AntifraudSettingsRepository & Settings & Scope, Nothing, AntifraudTuningStore] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudSettingsRepository]
        settings <- ZIO.service[Settings]
        tuning = Impl(repository)
        _ <- tuning.refresh
        _ <- (ZIO.sleep(settings.refreshInterval) *> tuning.refresh).forever.forkScoped
      yield tuning

  class Impl(repository: AntifraudSettingsRepository) extends AntifraudTuningStore:
    private val current = AtomicReference(Option.empty[Snapshot])
    private val loadedVersion = AtomicReference(-1L)
    private val loadedAt = AtomicReference(Instant.MIN)

    override def effective(tenant: Option[TenantId]): Tuning = current.get.fold(Tuning.off)(_.of(tenant))

    override def smsLimitsEnabled: Boolean = current.get.exists(_.smsLimitsEnabled)

    override def available: Boolean = current.get.isDefined

    override def stored(tenant: Option[TenantId]): Task[Option[TuningPatch]] = repository.find(tenant)

    override def put(tenant: Option[TenantId], patch: TuningPatch, actor: String): Task[Unit] =
      for
        row <- tenant match
          case Some(_) => ZIO.succeed(patch)
          case None =>
            repository.find(None).map(_.flatMap(_.complete)).flatMap:
              case Some(global) => ZIO.succeed(TuningPatch.of(patch.over(global)))
              case None =>
                ZIO.fromOption(patch.complete).map(TuningPatch.of).orElseFail(InvalidTuning("the global settings are missing: give every key"))
        _ <- ZIO.foreachDiscard(invalid(row, tenant.isDefined))(message => ZIO.fail(InvalidTuning(message)))
        _ <- Clock.instant.flatMap(repository.put(tenant, row, actor, _))
        _ <- refresh
      yield ()

    override def delete(tenant: Option[TenantId], actor: String): Task[Boolean] =
      if tenant.isEmpty then ZIO.fail(InvalidTuning("the global settings cannot be removed"))
      else Clock.instant.flatMap(repository.delete(tenant, actor, _)).tap(_ => refresh)

    override def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]] =
      repository.audit(scope, math.max(1, math.min(limit, MaxLimit)))

    /** Rebuilds the snapshot when the version moved or a minute has passed; a failure keeps the snapshot there is. */
    val refresh: UIO[Unit] =
      (for
        now <- Clock.instant
        version <- repository.version
        stale = version != loadedVersion.get || now.isAfter(loadedAt.get.plusSeconds(FullReloadSeconds))
        _ <- ZIO.when(stale):
          repository.loadAll.flatMap: rows =>
            ZIO.fromOption(snapshot(rows)).orElseFail(RuntimeException("no complete global antifraud settings")).map: loaded =>
              current.set(Some(loaded))
              loadedVersion.set(version)
              loadedAt.set(now)
      yield ()).catchAllCause(_ => AntifraudMetrics.tuningRefreshFailed)
