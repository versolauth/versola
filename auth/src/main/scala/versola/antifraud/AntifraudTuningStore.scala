package versola.antifraud

import zio.*
import zio.json.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** A change that is not valid: a negative limit, a limit above what can be meant, a prefix that is not digits. */
final class InvalidTuning(message: String) extends RuntimeException(message)

/** The tuning as stored, and the internal API that changes it.
  *
  * ==The row==
  * `af_settings` has one row with every key, seeded by migration `V0024` with everything off, so there is always one. A
  * change replaces it as a whole and bumps `af_settings_version`; deleting it ([[reset]]) puts the seeded values back.
  * Nothing in the configuration says what a threshold is: the store is the single source.
  *
  * ==Reading==
  * Every instance holds the tuning in memory; a request reads it without touching the database. It is rebuilt when
  * `af_settings_version` changes (checked every `refresh-interval`) and, whatever the version says, once a minute. A refresh
  * that fails keeps the last tuning that was read. Until the first one has been read ([[AntifraudTuning.available]] false,
  * which is a start with the database unreadable) a request is decided as `failure-action` says. A change made through this
  * instance applies on it at once and on the others within the interval.
  */
trait AntifraudTuningStore extends AntifraudTuning:
  /** The row as stored. */
  def stored: Task[Option[Tuning]]

  /** Replaces the row. Fails with [[InvalidTuning]]. */
  def put(tuning: Tuning, actor: String): Task[Unit]

  /** Puts the seeded values back. */
  def reset(actor: String): Task[Unit]

object AntifraudTuningStore:

  private val FullReloadSeconds = 60L

  private val MaxScore = 100000
  private val MaxWeight = 100000
  private val MaxLimitValue = 100000000
  private val MaxDifficulty = 32

  /** @param refreshInterval how often the version of the stored tuning is looked at, so how long a change takes to
    *                        reach another instance */
  case class Settings(refreshInterval: Duration)

  /** Why a tuning is not valid, if it is not. */
  def invalid(tuning: Tuning): Option[String] =
    def score(name: String, value: Int) = Option.when(value < 0 || value > MaxScore)(s"$name must be between 0 and $MaxScore")
    def limit(name: String, l: VelocityRules.Limit) =
      List(l.perMinute, l.perTenMinutes, l.perHour, l.perDay).find(v => v < 0 || v > MaxLimitValue).map(_ => s"$name limits must be between 0 and $MaxLimitValue")
        .orElse(Option.when(l.weight < -MaxWeight || l.weight > MaxWeight)(s"$name weight must be between -$MaxWeight and $MaxWeight"))
    def sms(name: String, l: SmsLimit) =
      Option.when(l.sentPerHour < 0 || l.sentPerHour > MaxLimitValue || l.minVolume < 0 || l.minVolume > MaxLimitValue)(s"$name counts must be between 0 and $MaxLimitValue")
        .orElse(Option.when(l.minConversion.isNaN || l.minConversion < 0 || l.minConversion > 1)(s"$name minConversion must be between 0 and 1"))
    val rule = "[a-z0-9_:.-]{1,100}".r
    val t = tuning.thresholds
    val p = tuning.pow
    List(
      score("challengeAt", tuning.challengeAt),
      score("blockAt", tuning.blockAt),
      Option.when(p.minDifficulty < 0 || p.maxDifficulty > MaxDifficulty || p.minDifficulty > p.maxDifficulty)(
        s"pow difficulty must satisfy 0 <= minDifficulty <= maxDifficulty <= $MaxDifficulty",
      ),
      Option.when(p.scoreStep < 0 || p.scoreStep > MaxScore)(s"pow scoreStep must be between 0 and $MaxScore"),
      List("phone" -> t.phone, "ip" -> t.ip, "device" -> t.device, "asn" -> t.asn, "prefix" -> t.prefix).flatMap(limit.tupled).headOption,
      Option.when(t.trustedDiscount < 0 || t.trustedDiscount > MaxWeight)(s"trustedDiscount must be between 0 and $MaxWeight"),
      sms("default", tuning.smsLimits.default),
      tuning.smsLimits.byPrefix.collectFirst { case (prefix, _) if prefix.isEmpty || prefix.length > 15 || !prefix.forall(_.isDigit) => s"prefix '$prefix' must be 1 to 15 digits" },
      tuning.smsLimits.byPrefix.flatMap((prefix, l) => sms(prefix, l)).headOption,
      Option.when(tuning.smsLimits.byPrefix.size > 500)("at most 500 prefixes"),
      tuning.ruleWeights.collectFirst { case (name, weight) if !rule.matches(name) || weight < -MaxWeight || weight > MaxWeight => s"rule weight '$name' is not valid" },
      Option.when(tuning.ruleWeights.size > 200)("at most 200 rule weights"),
      tuning.shadowRules.collectFirst { case name if !rule.matches(name) => s"shadow rule '$name' is not valid" },
      Option.when(tuning.shadowRules.size > 200)("at most 200 shadow rules"),
    ).flatten.headOption

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
    private val loaded = AtomicReference(Option.empty[Tuning])
    private val loadedVersion = AtomicReference(-1L)
    private val loadedAt = AtomicReference(Instant.MIN)

    override def current: Tuning = loaded.get.getOrElse(Tuning.off)

    override def available: Boolean = loaded.get.isDefined

    override def stored: Task[Option[Tuning]] = repository.find

    override def put(tuning: Tuning, actor: String): Task[Unit] =
      ZIO.foreachDiscard(invalid(tuning))(message => ZIO.fail(InvalidTuning(message))) *>
        Clock.instant.flatMap(repository.put(tuning, actor, _)) *> refresh

    override def reset(actor: String): Task[Unit] = put(Tuning.off, actor)

    /** Reads the row when the version moved or a minute has passed; a failure keeps the tuning there is. */
    val refresh: UIO[Unit] =
      (for
        now <- Clock.instant
        version <- repository.version
        stale = version != loadedVersion.get || now.isAfter(loadedAt.get.plusSeconds(FullReloadSeconds))
        _ <- ZIO.when(stale):
          repository.find.flatMap(row => ZIO.fromOption(row).orElseFail(RuntimeException("no antifraud settings row"))).map: tuning =>
            loaded.set(Some(tuning))
            loadedVersion.set(version)
            loadedAt.set(now)
      yield ()).catchAllCause(_ => AntifraudMetrics.tuningRefreshFailed)
