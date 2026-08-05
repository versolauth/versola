package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import zio.Task

import java.time.Instant

enum ChallengeType:
  case OtpRequest, OtpSubmit, PasswordSubmit, PasskeyAssertion

case class ChallengeThrottleRecord(
    tenantId: TenantId,
    subject: String,
    challengeType: ChallengeType,
    attempts: List[Long],
    bannedUntil: Option[Instant],
    expiresAt: Instant,
)

trait ChallengeThrottleRepository:
  def find(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Option[ChallengeThrottleRecord]]

  /** Loads the throttle records for a subject across the given challenge types in a single query. */
  def findAll(
      tenantId: TenantId,
      subject: String,
      challengeTypes: List[ChallengeType],
  ): Task[List[ChallengeThrottleRecord]]

  /** Loads the throttle records for multiple subjects for a single challenge type in a single query. */
  def findAllForSubjects(
      tenantId: TenantId,
      subjects: List[String],
      challengeType: ChallengeType,
  ): Task[List[ChallengeThrottleRecord]]

  def upsert(record: ChallengeThrottleRecord): Task[Unit]

  /** Atomically records an attempt: applies the prune/append/ban decision from
    * [[ChallengeThrottleRepository.nextState]] against `wLimits`/`banDurationSeconds` and persists
    * the result, without losing concurrent attempts against the same key — including the very
    * first attempt for a key that has no row yet (see issue #91). `wLimits` must be non-empty.
    */
  def recordAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: List[RateLimit],
      banDurationSeconds: Long,
  ): Task[LimitStatus]

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]

object ChallengeThrottleRepository:

  private def windowExceeded(attempts: List[Long], nowEpoch: Long, rl: RateLimit): Boolean =
    attempts.count(_ > nowEpoch - rl.windowSeconds) >= rl.maxAttempts

  private def limitsExceeded(attempts: List[Long], nowEpoch: Long, limits: List[RateLimit]): Boolean =
    limits.exists(windowExceeded(attempts, nowEpoch, _))

  /** Seconds until the subject is allowed again, i.e. when every exceeded window has slid enough
    * attempts out to fall back under its limit. Zero when no window is currently exceeded.
    */
  private def retryAfterSeconds(attempts: List[Long], nowEpoch: Long, limits: List[RateLimit]): Long =
    limits.flatMap: rl =>
      val inWindow = attempts.filter(_ > nowEpoch - rl.windowSeconds).sorted
      Option.when(inWindow.sizeIs >= rl.maxAttempts):
        inWindow(inWindow.size - rl.maxAttempts) + rl.windowSeconds - nowEpoch
    .maxOption.getOrElse(0L).max(0L)

  /** The broadest window — only this one applies a temporary ban when exceeded. */
  private def banWindow(limits: List[RateLimit]): Option[RateLimit] =
    limits.maxByOption(_.windowSeconds)

  /** Hard rate-limit windows enforced on every request; the ban window is excluded — unless it's
    * the only configured window, in which case it does double duty as both. Otherwise a
    * single-window configuration with `banDurationSeconds == 0` would silently never rate-limit
    * at all (nothing left to check once the sole window is carved out as the ban window).
    */
  private def rateLimitWindows(limits: List[RateLimit]): List[RateLimit] =
    if limits.sizeIs <= 1 then limits
    else banWindow(limits).fold(limits)(bw => limits.filterNot(_.eq(bw)))

  /** Evaluates an already-persisted record against its configured windows, for the read-only
    * status checks (`isBanned`, `statusFor`, `statusForSubjects`). Doesn't prune or append — see
    * [[nextState]] for the read-modify-write used when actually recording an attempt.
    */
  def evaluate(record: ChallengeThrottleRecord, wLimits: List[RateLimit], now: Instant): LimitStatus =
    val rlWindows = rateLimitWindows(wLimits)
    if record.bannedUntil.exists(_.isAfter(now)) then LimitStatus.Banned
    else if limitsExceeded(record.attempts, now.getEpochSecond, rlWindows) then
      LimitStatus.RateLimited(retryAfterSeconds(record.attempts, now.getEpochSecond, rlWindows))
    else LimitStatus.Allowed

  /** Pure prune/append/ban decision used by the Postgres implementation of
    * [[ChallengeThrottleRepository.recordAttempt]]. Given the previous record (if any), prunes
    * attempts older than the longest window, appends the current one, and if the broadest (ban)
    * window is now exceeded and a ban duration is configured, applies a temporary ban (clearing
    * attempts so the subject restarts once it expires). Otherwise reports `RateLimited` when a
    * hard window is exceeded, or `Allowed`. The record's TTL is extended to cover the ban or the
    * longest window. `wLimits` must be non-empty.
    */
  def nextState(
      recordOpt: Option[ChallengeThrottleRecord],
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: List[RateLimit],
      banDurationSeconds: Long,
  ): (ChallengeThrottleRecord, LimitStatus) =
    require(wLimits.nonEmpty, "recordAttempt requires at least one configured rate-limit window")
    val nowEpoch = now.getEpochSecond

    // Already banned: leave the record untouched (don't count further attempts while banned —
    // the ban itself already blocks) and report Banned directly. Falling through to the
    // fresh-window evaluation below would only look at this attempt's own windows and could
    // report Allowed/RateLimited even though bannedUntil is still in the future.
    recordOpt.flatMap(_.bannedUntil).filter(_.isAfter(now)) match
      case Some(_) => (recordOpt.get, LimitStatus.Banned)
      case None =>
        val longestWindow = wLimits.map(_.windowSeconds).max
        val existing = recordOpt.fold[List[Long]](Nil)(_.attempts)
        val pruned = existing.filter(_ > nowEpoch - longestWindow)
        val updated = pruned :+ nowEpoch

        val banExceeded = banWindow(wLimits).exists(windowExceeded(updated, nowEpoch, _))
        val applyBan = banExceeded && banDurationSeconds > 0

        // Clear attempts on ban so the user starts fresh after the ban expires.
        val finalAttempts = if applyBan then Nil else updated
        val bannedUntil = if applyBan then Some(now.plusSeconds(banDurationSeconds)) else None
        val expiresAt = if applyBan then bannedUntil.get else now.plusSeconds(longestWindow)

        val record = ChallengeThrottleRecord(
          tenantId = tenantId,
          subject = subject,
          challengeType = challengeType,
          attempts = finalAttempts,
          bannedUntil = bannedUntil,
          expiresAt = expiresAt,
        )

        val rlWindows = rateLimitWindows(wLimits)
        val status =
          if applyBan then LimitStatus.Banned
          else if limitsExceeded(updated, nowEpoch, rlWindows) then
            LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
          else LimitStatus.Allowed

        (record, status)
