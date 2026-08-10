package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import zio.NonEmptyChunk

import java.time.Instant

/** Pure rate-limiting policy: decides whether a subject is allowed, rate-limited or banned, and
  * what their throttle record should look like after an attempt.
  *
  * Deliberately free of any persistence concern — [[ChallengeThrottleRepository]] implementations
  * apply [[nextState]] inside their atomic read-modify-write, and [[SubmissionLimiter]] applies
  * [[evaluate]] on the read-only status paths. Keeping it here (rather than in the repository or
  * the limiter) means both sides share one definition of the policy, and it can be tested without
  * a database.
  */
object ThrottlePolicy:

  /** `RateLimit` is a plain case class with no validation of its own (it round-trips straight from
    * JSON config), so a non-positive `maxAttempts` or `windowSeconds` would otherwise reach
    * [[retryAfterSeconds]] and either trivially satisfy `inWindow.sizeIs >= rl.maxAttempts` for
    * every attempt list or index `inWindow` out of bounds. Reject it here instead, at the one
    * place every entry point funnels through, so a bad config fails loudly at the call site
    * rather than crashing deep in a retry-seconds calculation.
    */
  private def requireValid(limits: NonEmptyChunk[RateLimit]): Unit =
    limits.foreach: rl =>
      require(rl.maxAttempts > 0, s"RateLimit.maxAttempts must be positive, got ${rl.maxAttempts}")
      require(rl.windowSeconds > 0, s"RateLimit.windowSeconds must be positive, got ${rl.windowSeconds}")

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

  /** Index of the broadest window — only this one applies a temporary ban when exceeded. Selected
    * by index rather than by value so that two structurally equal windows (or the same instance
    * listed twice) can't both be treated as the ban window.
    */
  private def banWindowIndex(limits: List[RateLimit]): Int =
    limits.zipWithIndex.maxBy(_._1.windowSeconds)._2

  /** Hard rate-limit windows enforced on every request; the ban window is excluded — unless it's
    * the only configured window, in which case it does double duty as both. Otherwise a
    * single-window configuration with `banDurationSeconds == 0` would silently never rate-limit
    * at all (nothing left to check once the sole window is carved out as the ban window).
    */
  private def rateLimitWindows(limits: List[RateLimit]): List[RateLimit] =
    if limits.sizeIs <= 1 then limits
    else
      val banIdx = banWindowIndex(limits)
      limits.zipWithIndex.collect { case (rl, i) if i != banIdx => rl }

  /** Evaluates an already-persisted record against its configured windows, for the read-only
    * status checks (`isBanned`, `statusFor`, `statusForSubjects`). Doesn't prune or append — see
    * [[nextState]] for the read-modify-write used when actually recording an attempt.
    */
  def evaluate(record: ChallengeThrottleRecord, wLimits: NonEmptyChunk[RateLimit], now: Instant): LimitStatus =
    requireValid(wLimits)
    val limits = wLimits.toChunk.toList
    val rlWindows = rateLimitWindows(limits)
    if record.bannedUntil.exists(_.isAfter(now)) then LimitStatus.Banned
    else if limitsExceeded(record.attempts, now.getEpochSecond, rlWindows) then
      LimitStatus.RateLimited(retryAfterSeconds(record.attempts, now.getEpochSecond, rlWindows))
    else LimitStatus.Allowed

  /** Prune/append/ban decision applied by [[ChallengeThrottleRepository.recordAttempt]] inside its
    * atomic read-modify-write.
    *
    * When the previous record carries a ban that hasn't expired yet, it's returned untouched with
    * `Banned` — further attempts aren't counted while a ban is in force (the ban already blocks
    * them), and returning the record unchanged lets the caller skip the write entirely.
    *
    * Otherwise: prunes attempts older than the longest window, appends the current one, caps the
    * retained history, and if the broadest (ban) window is now exceeded and a ban duration is
    * configured, applies a temporary ban — clearing attempts so the subject restarts once it
    * expires. Failing that it reports `RateLimited` when a hard window is exceeded, else
    * `Allowed`. The record's TTL covers the ban, or the longest window.
    */
  def nextState(
      recordOpt: Option[ChallengeThrottleRecord],
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): (ChallengeThrottleRecord, LimitStatus) =
    requireValid(wLimits)
    val limits = wLimits.toChunk.toList
    val nowEpoch = now.getEpochSecond

    recordOpt.filter(_.bannedUntil.exists(_.isAfter(now))) match
      case Some(banned) => (banned, LimitStatus.Banned)
      case None =>
        val longestWindow = limits.map(_.windowSeconds).max
        val existing = recordOpt.fold[List[Long]](Nil)(_.attempts)
        val pruned = existing.filter(_ > nowEpoch - longestWindow)

        // Cap retained history at the largest configured allowance. Anything older than that can
        // no longer change any window's verdict, and without a cap a configuration with no ban
        // (`banDurationSeconds == 0`, so `attempts` is never cleared) would let the JSONB array
        // grow without bound for as long as an attacker keeps hammering inside the window.
        val cap = limits.map(_.maxAttempts).max
        val updated = (pruned :+ nowEpoch).takeRight(cap)

        val banExceeded = windowExceeded(updated, nowEpoch, limits(banWindowIndex(limits)))
        val applyBan = banExceeded && banDurationSeconds > 0

        // Clear attempts on ban so the subject starts fresh after the ban expires.
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

        val rlWindows = rateLimitWindows(limits)
        val status =
          if applyBan then LimitStatus.Banned
          else if limitsExceeded(updated, nowEpoch, rlWindows) then
            LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
          else LimitStatus.Allowed

        (record, status)
