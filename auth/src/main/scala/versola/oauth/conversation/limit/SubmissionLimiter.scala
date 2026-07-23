package versola.oauth.conversation.limit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, RateLimit, SubmissionLimits}
import zio.{Clock, Task, UIO, ZIO, ZLayer}

import java.time.Instant

enum LimitStatus:
  case Allowed
  case RateLimited(retryAfterSeconds: Long) // Short-window limit exceeded; resolves on its own when the window slides
  case Banned                               // Persistent ban applied (longest window exceeded)

trait SubmissionLimiter:
  /** Returns the current limit status for the subject. */
  def isBanned(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus]

  /** Returns the worst limit status for the subject across the given challenge types, using a single
    * throttle lookup.
    */
  def statusFor(clientId: ClientId, subject: String, challengeTypes: List[ChallengeType]): Task[LimitStatus]

  /** Returns the worst limit status across multiple subjects for a single challenge type, using a
    * single throttle lookup.
    */
  def statusForSubjects(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus]

  /** Records an attempt against the configured limits and returns the resulting limit status. */
  def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus]

  /** Records an attempt for each subject via its own atomic read-modify-write, and returns the worst status. */
  def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus]

  /** Clears the throttle record for the subject, resetting any accumulated failure count. */
  def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit]

object SubmissionLimiter:
  def live = ZLayer.fromFunction(Impl(_, _))

  class Impl(
      throttleRepo: ChallengeThrottleRepository,
      configService: OAuthConfigurationService,
  ) extends SubmissionLimiter:

    private def windowLimits(limits: SubmissionLimits, ct: ChallengeType): List[RateLimit] =
      ct match
        case ChallengeType.OtpRequest => limits.otpRequest
        case ChallengeType.OtpSubmit => limits.otpSubmit
        case ChallengeType.PasswordSubmit => limits.passwordSubmit
        case ChallengeType.PasskeyAssertion => limits.passkeyAssertion

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

    /** Hard rate-limit windows enforced on every request; the ban window is excluded. */
    private def rateLimitWindows(limits: List[RateLimit]): List[RateLimit] =
      banWindow(limits).fold(limits)(bw => limits.filterNot(_.eq(bw)))

    /** Evaluates a single throttle record against its configured windows. */
    private def evaluate(record: ChallengeThrottleRecord, wLimits: List[RateLimit], now: Instant): LimitStatus =
      val rlWindows = rateLimitWindows(wLimits)
      if record.bannedUntil.exists(_.isAfter(now)) then LimitStatus.Banned
      else if limitsExceeded(record.attempts, now.getEpochSecond, rlWindows) then
        LimitStatus.RateLimited(retryAfterSeconds(record.attempts, now.getEpochSecond, rlWindows))
      else LimitStatus.Allowed

    private def worstStatus(a: LimitStatus, b: LimitStatus): LimitStatus =
      (a, b) match
        case (LimitStatus.Banned, _) | (_, LimitStatus.Banned) => LimitStatus.Banned
        case (LimitStatus.RateLimited(s), _) => LimitStatus.RateLimited(s)
        case (_, LimitStatus.RateLimited(s)) => LimitStatus.RateLimited(s)
        case _ => LimitStatus.Allowed

    /** Read-only status check for a single subject: loads the client's limits, and if any window is
      * configured, fetches the subject's throttle record and evaluates it against those windows.
      * Short-circuits to `Allowed` when no windows are configured, the client is unknown, or no
      * record exists yet.
      */
    override def isBanned(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        wLimits = windowLimits(limits, challengeType)
        result <-
          if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
          else
            configService.find(clientId).flatMap:
              case None => ZIO.succeed(LimitStatus.Allowed)
              case Some(client) =>
                throttleRepo.find(client.tenantId, subject, challengeType).flatMap:
                  case None => ZIO.succeed(LimitStatus.Allowed)
                  case Some(record) => Clock.instant.map(evaluate(record, wLimits, _))
      yield result

    /** Read-only status check for one subject across several challenge types in a single lookup.
      * Keeps only the challenge types that have configured windows, fetches all their records at
      * once, evaluates each, and folds them into the worst status. `Allowed` when none apply.
      */
    override def statusFor(clientId: ClientId, subject: String, challengeTypes: List[ChallengeType]): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        typeWindows = challengeTypes.map(ct => ct -> windowLimits(limits, ct)).filter(_._2.nonEmpty)
        result <-
          if typeWindows.isEmpty then ZIO.succeed(LimitStatus.Allowed)
          else
            configService.find(clientId).flatMap:
              case None => ZIO.succeed(LimitStatus.Allowed)
              case Some(client) =>
                for
                  records <- throttleRepo.findAll(client.tenantId, subject, typeWindows.map(_._1))
                  now <- Clock.instant
                  byType = records.map(r => r.challengeType -> r).toMap
                yield typeWindows
                  .map((ct, wLimits) => byType.get(ct).fold(LimitStatus.Allowed)(evaluate(_, wLimits, now)))
                  .foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

    /** Read-only status check for several subjects of one challenge type in a single lookup.
      * Fetches all matching records at once, evaluates each subject (missing record => `Allowed`),
      * and folds them into the worst status so a ban on any subject dominates.
      */
    override def statusForSubjects(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        wLimits = windowLimits(limits, challengeType)
        result <-
          if wLimits.isEmpty || subjects.isEmpty then ZIO.succeed(LimitStatus.Allowed)
          else
            configService.find(clientId).flatMap:
              case None => ZIO.succeed(LimitStatus.Allowed)
              case Some(client) =>
                for
                  records <- throttleRepo.findAllForSubjects(client.tenantId, subjects, challengeType)
                  now <- Clock.instant
                  bySubject = records.map(r => r.subject -> r).toMap
                yield subjects
                  .map(s => bySubject.get(s).fold(LimitStatus.Allowed)(evaluate(_, wLimits, now)))
                  .foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

    /** Deletes the subject's throttle record, clearing its accumulated attempts and any active ban.
      * Called on a successful challenge so the subject starts fresh. No-op when the client is unknown.
      */
    override def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit] =
      configService.find(clientId).flatMap:
        case None => ZIO.unit
        case Some(client) => throttleRepo.delete(client.tenantId, subject, challengeType)

    /** Pure prune/append/ban decision shared by [[recordLimit]] and [[recordLimitAll]]. Given the
      * previous record (if any), prunes attempts older than the longest window, appends the current
      * one, and if the broadest (ban) window is now exceeded and a ban duration is configured,
      * applies a temporary ban (clearing attempts so the subject restarts once it expires).
      * Otherwise reports `RateLimited` when a hard window is exceeded, or `Allowed`. The record's
      * TTL is extended to cover the ban or the longest window.
      *
      * Runs inside the transaction started by [[ChallengeThrottleRepository.recordAttempt]], with
      * `recordOpt` read under a row lock, so the read-modify-write is atomic and can't lose
      * concurrent attempts against the same key (issue #91).
      */
    private def nextThrottleState(
        recordOpt: Option[ChallengeThrottleRecord],
        now: Instant,
        wLimits: List[RateLimit],
        banDurationSeconds: Long,
    ): (ThrottleUpdate, LimitStatus) =
      val nowEpoch = now.getEpochSecond
      val longestWindow = wLimits.map(_.windowSeconds).max
      val existing = recordOpt.fold[List[Long]](Nil)(_.attempts)
      val pruned = existing.filter(_ > nowEpoch - longestWindow)
      val updated = pruned :+ nowEpoch

      val banExceeded = banWindow(wLimits).exists(windowExceeded(updated, nowEpoch, _))
      val applyBan = banExceeded && banDurationSeconds > 0

      // Clear attempts on ban so the user starts fresh after the ban expires.
      val finalAttempts = if applyBan then Nil else updated
      val bannedUntil =
        if applyBan then Some(now.plusSeconds(banDurationSeconds))
        else recordOpt.flatMap(_.bannedUntil).filter(_.isAfter(now))

      val expiresAt =
        if applyBan then bannedUntil.get
        else
          val ttl = now.plusSeconds(longestWindow)
          bannedUntil.filter(_.isAfter(ttl)).getOrElse(ttl)

      val rlWindows = rateLimitWindows(wLimits)
      val status =
        if applyBan then LimitStatus.Banned
        else if limitsExceeded(updated, nowEpoch, rlWindows) then
          LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
        else LimitStatus.Allowed

      (ThrottleUpdate(finalAttempts, bannedUntil, expiresAt), status)

    /** Records a single failed attempt for the subject and returns the resulting status. See
      * [[nextThrottleState]] for the prune/append/ban decision; it runs inside the repository's
      * transaction so the read and write are atomic.
      */
    override def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        val wLimits = windowLimits(limits, challengeType)
        if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
        else
          configService.find(clientId).flatMap:
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(client) =>
              Clock.instant.flatMap: now =>
                throttleRepo.recordAttempt(
                  client.tenantId,
                  subject,
                  challengeType,
                  recordOpt => nextThrottleState(recordOpt, now, wLimits, limits.banDurationSeconds),
                )

    /** Batch variant of [[recordLimit]]: records a failed attempt for every subject, applying the
      * same prune/append/ban logic per subject via its own atomic [[ChallengeThrottleRepository.recordAttempt]]
      * call. Used to charge related subjects (e.g. IP and credential) for the same failure. Returns
      * the worst status across all subjects so a ban on any one dominates.
      *
      * Each subject's read-modify-write runs in its own transaction that only ever locks that one
      * row, so running them in parallel can't deadlock.
      */
    override def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        val wLimits = windowLimits(limits, challengeType)
        if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
        else
          configService.find(clientId).flatMap:
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(client) =>
              Clock.instant.flatMap: now =>
                ZIO.foreachPar(subjects) { subject =>
                  throttleRepo.recordAttempt(
                    client.tenantId,
                    subject,
                    challengeType,
                    recordOpt => nextThrottleState(recordOpt, now, wLimits, limits.banDurationSeconds),
                  )
                }.map(_.foldLeft(LimitStatus.Allowed)(worstStatus))
