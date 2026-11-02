package versola.antifraud

import versola.util.JsonMergePatch
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** A change that is not valid: a negative limit, a limit above what can be meant, a prefix that is not digits. */
final class InvalidTuning(message: String) extends RuntimeException(message)

/** The change was made on a row that is no longer at the version the caller read: someone else changed it first. `current` is
  * the version of the row now (0 where there is none). */
final class VersionMismatch(val current: Long) extends RuntimeException(s"the settings are at version $current")

/** What an instance knows of its tuning, for the status endpoint: whether it has one, the version of the row it holds (the
  * ETag of `GET /antifraud/settings`), when it last confirmed it against the database and what the last failed refresh said. */
case class TuningStatus(available: Boolean, version: Option[Long], checkedAt: Option[Instant], lastError: Option[String])

/** The tuning as stored, and the internal API that changes it.
  *
  * ==The row==
  * `af_settings` has one row with every key, written with everything off by auth's bootstrap if there is none. A change
  * replaces it as a whole and bumps `af_settings_version` and the version of the row (the ETag the admin API hands out, so that
  * a change made on a stale read is refused, see [[VersionMismatch]]); [[reset]] puts everything off again. A deployment with no row
  * (bootstrap has not run yet, or the row was deleted by hand) is taken to have everything off ([[Tuning.off]]). Nothing in
  * the configuration says what a threshold is: the store is the single source.
  *
  * ==Reading==
  * Every instance holds the tuning in memory; a request reads it without touching the database. It is rebuilt when
  * `af_settings_version` changes (checked every `refresh-interval`) and, whatever the version says, once a minute. A refresh
  * that fails keeps the last tuning that was read. Until the first one has been read ([[AntifraudTuning.available]] false,
  * which is a start with the database unreadable) a request is decided as `failure-action` says. A change made through this
  * instance applies on it at once and on the others within the interval.
  */
trait AntifraudTuningStore extends AntifraudTuning:
  /** The row as stored, with its version; [[Tuning.off]] at version 0 where there is no row. */
  def stored: Task[StoredTuning]

  /** Replaces the row and returns its new version. Fails with [[InvalidTuning]], and with [[VersionMismatch]] when
    * `expectedVersion` is given and the row is at another one (nothing is written). Without one the row is replaced whatever
    * it holds: for a caller that has the whole desired state and takes responsibility for what it overwrites. */
  def put(tuning: Tuning, actor: String, expectedVersion: Option[Long]): Task[Long]

  /** Changes the row by a JSON merge patch (RFC 7396, see [[JsonMergePatch]]) of the settings as stored (everything off where
    * there is no row), validates the result as [[put]] does, and writes it on the version it was made from. Fails with
    * [[InvalidTuning]] for a result that does not decode or is not valid (nothing is written), and with [[VersionMismatch]]
    * when `expectedVersion` is given and the row is at another one. Without one a change made by someone else between the read
    * and the write makes the patch be made again on what they wrote, once; if that fails too, so does the call. */
  def patch(patch: Json, actor: String, expectedVersion: Option[Long]): Task[StoredTuning]

  /** Puts everything off ([[Tuning.off]]), as [[put]] does: it moves the version too. */
  def reset(actor: String, expectedVersion: Option[Long]): Task[Long]

  /** What this instance holds, read from memory. */
  def status: UIO[TuningStatus]

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
      List(l.perMinute, l.perTenMinutes, l.perHour, l.perDay).find(v => v < 0 || v > MaxLimitValue).map(_ =>
        s"$name limits must be between 0 and $MaxLimitValue",
      )
        .orElse(Option.when(l.weight < -MaxWeight || l.weight > MaxWeight)(s"$name weight must be between -$MaxWeight and $MaxWeight"))
    def sms(name: String, l: SmsLimit) =
      Option.when(
        l.sentPerHour < 0 || l.sentPerHour > MaxLimitValue || l.minVolume < 0 || l.minVolume > MaxLimitValue,
      )(s"$name counts must be between 0 and $MaxLimitValue")
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
      tuning.smsLimits.byPrefix.collectFirst {
        case (prefix, _) if prefix.isEmpty || prefix.length > 15 || !prefix.forall(_.isDigit) => s"prefix '$prefix' must be 1 to 15 digits"
      },
      tuning.smsLimits.byPrefix.flatMap((prefix, l) => sms(prefix, l)).headOption,
      Option.when(tuning.smsLimits.byPrefix.size > 500)("at most 500 prefixes"),
      tuning.ruleWeights.collectFirst {
        case (name, weight) if !rule.matches(name) || weight < -MaxWeight || weight > MaxWeight => s"rule weight '$name' is not valid"
      },
      Option.when(tuning.ruleWeights.size > 200)("at most 200 rule weights"),
      tuning.shadowRules.collectFirst { case name if !rule.matches(name) => s"shadow rule '$name' is not valid" },
      Option.when(tuning.shadowRules.size > 200)("at most 200 shadow rules"),
    ).flatten.headOption

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

    override def stored: Task[StoredTuning] = repository.find.map(_.getOrElse(StoredTuning(Tuning.off, 0L)))

    override def put(tuning: Tuning, actor: String, expectedVersion: Option[Long]): Task[Long] =
      for
        _ <- ZIO.foreachDiscard(invalid(tuning))(message => ZIO.fail(InvalidTuning(message)))
        now <- Clock.instant
        written <- repository.put(tuning, actor, now, expectedVersion)
        version <- written match
          case Some(version) => refresh.as(version)
          case None => stored.flatMap(row => ZIO.fail(VersionMismatch(row.version)))
      yield version

    override def patch(patch: Json, actor: String, expectedVersion: Option[Long]): Task[StoredTuning] =
      def attempt(retriesLeft: Int): Task[StoredTuning] =
        for
          current <- stored
          _ <- ZIO.fail(VersionMismatch(current.version)).when(expectedVersion.exists(_ != current.version))
          merged = JsonMergePatch(current.tuning.toJsonAST.getOrElse(Json.Null), patch)
          tuning <-
            ZIO.fromEither(merged.as[Tuning]).mapError(message => InvalidTuning(s"the settings with the patch applied are not valid: $message"))
          // The write is made on the version the patch was made from, so a change in between is not overwritten.
          written <- put(tuning, actor, Some(current.version)).map(StoredTuning(tuning, _)).catchSome:
            case _: VersionMismatch if expectedVersion.isEmpty && retriesLeft > 0 => attempt(retriesLeft - 1)
        yield written
      attempt(retriesLeft = 1)

    override def reset(actor: String, expectedVersion: Option[Long]): Task[Long] = put(Tuning.off, actor, expectedVersion)

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
      yield ()).catchAllCause: cause =>
        lastError.set(Some(Option(cause.squash.getMessage).getOrElse(cause.squash.getClass.getSimpleName).take(300)))
        AntifraudMetrics.tuningRefreshFailed
