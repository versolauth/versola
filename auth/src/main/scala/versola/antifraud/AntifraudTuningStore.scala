package versola.antifraud

import zio.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** A change that is not valid: a negative limit, a limit above what can be meant, a prefix that is not digits. */
final class InvalidTuning(message: String) extends RuntimeException(message)

/** What an instance knows of its tuning, for the status endpoint: whether it has one, the version of the row it holds (that
  * of central's settings the copy was made at), when it last confirmed it against the database and what the last failed refresh said. */
case class TuningStatus(available: Boolean, version: Option[Long], checkedAt: Option[Instant], lastError: Option[String])

/** The tuning this cell applies, and what is said of a tuning before it is stored.
  *
  * ==The row==
  * The settings are central's, one for every cell. `af_settings` holds this cell's copy of them, one row with every key,
  * which [[AntifraudPolicySync]] keeps current; a row that lacks a key added by a later version is read with the key's default
  * (see [[Tuning]]). A cell with no row (central has none, or the first copy has not been made) is taken to have
  * everything off ([[Tuning.off]]). Nothing in the configuration says what a threshold is.
  *
  * Central asks a cell whether a change is valid before it stores it ([[AntifraudTuningStore.checked]]): what is valid, the
  * names of the rules and the defaults of a setting belong to the engine that applies them.
  *
  * ==Reading==
  * Every instance holds the tuning in memory; a request reads it without touching the database. It is rebuilt when
  * `af_settings_version` changes (checked every `refresh-interval`) and, whatever the version says, once a minute. A refresh
  * that fails keeps the last tuning that was read. Until the first one has been read ([[AntifraudTuning.available]] false,
  * which is a start with the database unreadable) a request is decided as `failure-action` says.
  */
trait AntifraudTuningStore extends AntifraudTuning:
  /** What this instance holds, read from memory. */
  def status: UIO[TuningStatus]

object AntifraudTuningStore:

  private val FullReloadSeconds = 60L

  private val MaxScore = 100000
  private val MaxWeight = 100000
  private val MaxLimitValue = 100000000

  /** Each bit doubles the work a phone has to do: past 24 a challenge cannot be solved in a browser in any time a person waits. */
  private val MaxDifficulty = 24

  /** A limit of fewer codes than this closes a range to whoever is behind it before an attack has started; 0 is off. */
  private val MinSmsLimit = 20

  /** A score below this is crossed by a single rule of the default weights. */
  private val MinBlockAt = 10

  /** @param refreshInterval how often the version of the stored tuning is looked at, so how long a change takes to
    *                        reach another instance */
  case class Settings(refreshInterval: Duration)

  /** Why a tuning is not valid, if it is not. Each field is checked in its range, and then the combinations that can only be
    * a mistake: a challenge score above the block score (the challenge would never be reached), a limit so low it closes
    * a range on first use, a block score a single rule crosses, and the name of a rule there is none of. */
  def invalid(tuning: Tuning): Option[String] =
    def score(name: String, value: Int) = Option.when(value < 0 || value > MaxScore)(s"$name must be between 0 and $MaxScore")
    def limit(name: String, l: VelocityRules.Limit) =
      List(l.perMinute, l.perTenMinutes, l.perHour, l.perDay).find(v => v < 0 || v > MaxLimitValue).map(_ =>
        s"$name limits must be between 0 and $MaxLimitValue",
      )
        .orElse(Option.when(l.weight < -MaxWeight || l.weight > MaxWeight)(s"$name weight must be between -$MaxWeight and $MaxWeight"))
    def sms(name: String, l: SmsLimit) =
      Option.when(
        l.sentPerHour < 0 || l.sentPerHour > MaxLimitValue || l.minVolume < 0 || l.minVolume > MaxLimitValue,
      )(s"$name counts must be between 0 and $MaxLimitValue")
        .orElse(Option.when(
          (l.sentPerHour != 0 && l.sentPerHour < MinSmsLimit) || (l.minVolume != 0 && l.minVolume < MinSmsLimit),
        )(s"$name counts must be 0 or at least $MinSmsLimit"))
        .orElse(Option.when(l.minConversion.isNaN || l.minConversion < 0 || l.minConversion > 1)(s"$name minConversion must be between 0 and 1"))
    val rule = "[a-z0-9_:.-]{1,100}".r
    val t = tuning.thresholds
    val p = tuning.pow
    List(
      score("challengeAt", tuning.challengeAt),
      score("blockAt", tuning.blockAt),
      Option.when(tuning.challengeAt > 0 && tuning.blockAt > 0 && tuning.challengeAt > tuning.blockAt)(
        "challengeAt must not be above blockAt when both are set",
      ),
      Option.when(tuning.blockAt > 0 && tuning.blockAt < MinBlockAt)(s"blockAt must be at least $MinBlockAt when set"),
      Option.when(p.minDifficulty < 0 || p.maxDifficulty > MaxDifficulty || p.minDifficulty > p.maxDifficulty)(
        s"pow difficulty must satisfy 0 <= minDifficulty <= maxDifficulty <= $MaxDifficulty",
      ),
      Option.when(p.scoreStep < 0 || p.scoreStep > MaxScore)(s"pow scoreStep must be between 0 and $MaxScore"),
      List("phone" -> t.phone, "ip" -> t.ip, "device" -> t.device, "asn" -> t.asn, "prefix" -> t.prefix).flatMap(limit.tupled).headOption,
      Option.when(t.trustedDiscount < 0 || t.trustedDiscount > MaxWeight)(s"trustedDiscount must be between 0 and $MaxWeight"),
      sms("default", tuning.smsLimits.default),
      tuning.smsLimits.byPrefix.collectFirst {
        case (prefix, _) if prefix.isEmpty || prefix.length > 15 || !prefix.forall(_.isDigit) => s"prefix '$prefix' must be 1 to 15 digits"
      },
      tuning.smsLimits.byPrefix.flatMap((prefix, l) => sms(prefix, l)).headOption,
      Option.when(tuning.smsLimits.byPrefix.size > 500)("at most 500 prefixes"),
      tuning.ruleWeights.collectFirst {
        case (name, weight) if !rule.matches(name) || weight < -MaxWeight || weight > MaxWeight => s"rule weight '$name' is not valid"
      },
      tuning.ruleWeights.keys.toList.sorted.collectFirst {
        case name if !Antifraud.ruleNames.contains(name) => s"rule weight '$name' names no rule (see GET /antifraud/rules)"
      },
      Option.when(tuning.ruleWeights.size > 200)("at most 200 rule weights"),
      tuning.shadowRules.collectFirst { case name if !rule.matches(name) => s"shadow rule '$name' is not valid" },
      tuning.shadowRules.toList.sorted.collectFirst {
        case name if !Antifraud.ruleNames.contains(name) => s"shadow rule '$name' names no rule (see GET /antifraud/rules)"
      },
      Option.when(tuning.shadowRules.size > 200)("at most 200 shadow rules"),
    ).flatten.headOption

  /** The tuning a change would make: `patch` applied to `settings` (everything off where there are none), or the reason it
    * is not valid. */
  def checked(settings: Option[Tuning], patch: Option[TuningPatch]): Either[InvalidTuning, Tuning] =
    val base = settings.getOrElse(Tuning.off)
    val result = patch.fold(base)(_.applyTo(base))
    invalid(result).map(InvalidTuning(_)).toLeft(result)

  /** Reads the tuning once, and then on a fiber of its own, which stops with the scope. A start with the database
    * unreadable goes on, with [[AntifraudTuning.available]] false until a read succeeds. */
  val live: ZLayer[AntifraudSettingsRepository & Settings & Scope, Nothing, AntifraudTuningStore & AntifraudTuning] =
    ZLayer.fromZIOEnvironment:
      for
        repository <- ZIO.service[AntifraudSettingsRepository]
        settings <- ZIO.service[Settings]
        tuning = Impl(repository)
        _ <- tuning.refresh
        _ <- (ZIO.sleep(settings.refreshInterval) *> tuning.refresh).forever.forkScoped
      yield ZEnvironment[AntifraudTuningStore, AntifraudTuning](tuning, tuning)

  class Impl(repository: AntifraudSettingsRepository) extends AntifraudTuningStore:
    private val loaded = AtomicReference(Option.empty[Tuning])
    private val loadedVersion = AtomicReference(-1L)
    private val loadedAt = AtomicReference(Instant.MIN)
    private val loadedRowVersion = AtomicReference(Option.empty[Long])
    private val checkedAt = AtomicReference(Option.empty[Instant])
    private val lastError = AtomicReference(Option.empty[String])

    override def current: Tuning = loaded.get.getOrElse(Tuning.off)

    override def available: Boolean = loaded.get.isDefined

    override def status: UIO[TuningStatus] =
      ZIO.succeed(TuningStatus(available, loadedRowVersion.get, checkedAt.get, lastError.get))

    /** Reads the row when the version moved or a minute has passed; a failure keeps the tuning there is. */
    val refresh: UIO[Unit] =
      (for
        now <- Clock.instant
        version <- repository.version
        stale = version != loadedVersion.get || now.isAfter(loadedAt.get.plusSeconds(FullReloadSeconds))
        _ <- ZIO.when(stale):
          repository.find.map: row =>
            loaded.set(Some(row.fold(Tuning.off)(_.tuning)))
            loadedRowVersion.set(Some(row.fold(0L)(_.version)))
            loadedVersion.set(version)
            loadedAt.set(now)
        _ = checkedAt.set(Some(now))
        _ = lastError.set(None)
        // Read, or found unchanged: either way the tuning is as new as the table.
        _ <- AntifraudMetrics.tuningRefreshed(now)
      yield ()).catchAllCause: cause =>
        lastError.set(Some(Option(cause.squash.getMessage).getOrElse(cause.squash.getClass.getSimpleName).take(300)))
        AntifraudMetrics.tuningRefreshFailed
