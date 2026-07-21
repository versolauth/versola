package versola.oauth.conversation.limit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, RateLimit, SubmissionLimits, TenantId}
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

  /** Records an attempt against the configured limits and returns the resulting limit status.
    *
    * Charges the attempt unconditionally, so the returned status reflects the state *including* it.
    * That suits recording a failure that has already happened; to gate an action that must not run
    * once the limit is reached, use [[tryAcquire]] instead.
    */
  def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus]

  /** Claims one attempt: returns the status as of *before* this attempt, and only records it when
    * that status is `Allowed`.
    *
    * This is the gate for actions where the request itself is the thing being limited (sending an
    * OTP, say) rather than a failure being punished. The claim is not a single round trip — it reads
    * the record, then writes it back conditionally on the version it read, retrying on a lost race —
    * but it is atomic in effect: two concurrent claims cannot both be granted off the same state.
    * Leaving rejected attempts unrecorded means a flood against someone else's credential cannot
    * keep their counter pinned above the limit forever.
    */
  def tryAcquire(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus]

  /** Records an attempt for each subject in a single fetch + parallel upserts, and returns the worst status. */
  def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus]

  /** Clears the throttle record for the subject, resetting any accumulated failure count. */
  def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit]

object SubmissionLimiter:
  /** How many compare-and-set rounds a single write gets before it gives up and fails.
    *
    * A round is only re-run when a competing writer landed first, so the budget has to cover the
    * writers realistically contending for one subject's row. [[SubmissionLimiter.tryAcquire]] damps
    * this on its own — once the limit is reached it denies without writing, so a burst stops
    * generating writes after roughly `maxAttempts` of them — but [[SubmissionLimiter.recordLimit]]
    * charges unconditionally and stays contended for as long as the burst lasts.
    */
  val maxWriteAttempts = 10

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

    /** Shared preamble for the write paths: resolves the windows configured for the challenge type
      * and hands them, with the owning tenant, to `f`. Short-circuits to `Allowed` when no windows
      * are configured or the client is unknown, matching the read-only checks above.
      */
    private def withWindows(clientId: ClientId, challengeType: ChallengeType)(
        f: (TenantId, SubmissionLimits, List[RateLimit]) => Task[LimitStatus],
    ): Task[LimitStatus] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        val wLimits = windowLimits(limits, challengeType)
        if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
        else
          configService.find(clientId).flatMap:
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(client) => f(client.tenantId, limits, wLimits)

    /** Retries a compare-and-set round that reported a lost race, re-reading the record each time.
      * A retry only happens when another writer actually landed first.
      *
      * Failing once the budget is gone is deliberate: silently giving up would drop the attempt and
      * hand back the under-counted limit we are trying to enforce. Both outcomes are logged, since a
      * contended row is otherwise invisible in production — an exhausted budget surfaces to the user
      * as a generic `ServiceUnavailable` with nothing in the logs tying it back to throttling.
      *
      * The subject is deliberately left out of the log messages: it is the user's email or phone.
      */
    private def withRetry(challengeType: ChallengeType)(round: Task[Option[LimitStatus]]): Task[LimitStatus] =
      def loop(attempt: Int): Task[LimitStatus] =
        round.flatMap:
          case Some(status) => ZIO.succeed(status)
          case None if attempt < SubmissionLimiter.maxWriteAttempts =>
            ZIO.logWarning(
              s"challenge throttle write for $challengeType lost a race on attempt $attempt, re-reading and retrying",
            ) *> loop(attempt + 1)
          case None =>
            val message =
              s"challenge throttle write for $challengeType stayed contended for ${SubmissionLimiter.maxWriteAttempts} attempts; " +
                "failing closed rather than under-counting the limit"
            ZIO.logError(message) *> ZIO.fail(new IllegalStateException(message))
      loop(1)

    /** The record to write, and the status it produces, once `existing` is charged one more attempt
      * at `now`. Prunes attempts older than the longest window, appends the current one, and if the
      * broadest (ban) window is now exceeded and a ban duration is configured, applies a temporary
      * ban (clearing attempts so the subject restarts once it expires). Reports `Banned` while any
      * ban is in force, `RateLimited` when a hard window is exceeded, or `Allowed`. The record's TTL
      * is extended to cover the ban or the longest window, and it carries the version `existing` was
      * read at so the write can detect a concurrent change.
      */
    private def charged(
        existing: Option[ChallengeThrottleRecord],
        tenantId: TenantId,
        subject: String,
        challengeType: ChallengeType,
        limits: SubmissionLimits,
        wLimits: List[RateLimit],
        now: Instant,
    ): (ChallengeThrottleRecord, LimitStatus) =
      val nowEpoch = now.getEpochSecond
      val longestWindow = wLimits.map(_.windowSeconds).max
      val pruned = existing.fold[List[Long]](Nil)(_.attempts).filter(_ > nowEpoch - longestWindow)
      val updated = pruned :+ nowEpoch

      val banExceeded = banWindow(wLimits).exists(windowExceeded(updated, nowEpoch, _))
      val applyBan = banExceeded && limits.banDurationSeconds > 0

      // Clear attempts on ban so the user starts fresh after the ban expires.
      val finalAttempts = if applyBan then Nil else updated
      val bannedUntil =
        if applyBan then Some(now.plusSeconds(limits.banDurationSeconds))
        else existing.flatMap(_.bannedUntil).filter(_.isAfter(now))

      val expiresAt =
        if applyBan then bannedUntil.get
        else
          val ttl = now.plusSeconds(longestWindow)
          bannedUntil.filter(_.isAfter(ttl)).getOrElse(ttl)

      val record = ChallengeThrottleRecord(
        tenantId = tenantId,
        subject = subject,
        challengeType = challengeType,
        attempts = finalAttempts,
        bannedUntil = bannedUntil,
        expiresAt = expiresAt,
        version = existing.fold(0L)(_.version),
      )

      val rlWindows = rateLimitWindows(wLimits)
      val status =
        // Covers a ban this attempt just earned and one already on the record. The latter matters
        // on a retry: a competing writer may have installed a ban between our read and our write,
        // and since applying a ban clears the attempt list, the recomputed list is short enough to
        // look `Allowed` on its own. Checking the ban first mirrors `evaluate`, so the read and
        // write paths cannot disagree about whether a subject is banned.
        if bannedUntil.isDefined then LimitStatus.Banned
        else if limitsExceeded(updated, nowEpoch, rlWindows) then
          LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
        else LimitStatus.Allowed

      (record, status)

    /** Charges one attempt to the subject, re-reading and recomputing if a concurrent writer beat us
      * to the row.
      */
    private def chargeOne(
        tenantId: TenantId,
        subject: String,
        challengeType: ChallengeType,
        limits: SubmissionLimits,
        wLimits: List[RateLimit],
    ): Task[LimitStatus] =
      withRetry(challengeType):
        for
          now <- Clock.instant
          existing <- throttleRepo.find(tenantId, subject, challengeType)
          (record, status) = charged(existing, tenantId, subject, challengeType, limits, wLimits, now)
          written <- throttleRepo.upsert(record)
        yield Option.when(written)(status)

    override def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      withWindows(clientId, challengeType): (tenantId, limits, wLimits) =>
        chargeOne(tenantId, subject, challengeType, limits, wLimits)

    override def tryAcquire(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      withWindows(clientId, challengeType): (tenantId, limits, wLimits) =>
        withRetry(challengeType):
          for
            now <- Clock.instant
            existing <- throttleRepo.find(tenantId, subject, challengeType)
            result <- existing.fold(LimitStatus.Allowed)(evaluate(_, wLimits, now)) match
              case LimitStatus.Allowed =>
                // Claim the attempt against the exact version we just evaluated: if a competing
                // request recorded one in between, the write is rejected and we re-evaluate against
                // its result rather than sending a second OTP off stale state.
                val (record, _) = charged(existing, tenantId, subject, challengeType, limits, wLimits, now)
                throttleRepo.upsert(record).map(Option.when(_)(LimitStatus.Allowed))
              // Already over the limit. Report it without charging, so a flood aimed at someone
              // else's credential cannot keep their counter pinned above the threshold forever.
              case denied => ZIO.succeed(Some(denied))
          yield result

    /** Batch variant of [[recordLimit]]: charges an attempt to every subject in parallel, applying
      * the same prune/append/ban logic to each. Used to charge related subjects (e.g. IP and
      * credential) for the same failure. Returns the worst status across all subjects so a ban on
      * any one dominates.
      *
      * Each subject is read individually rather than through one batched fetch: a compare-and-set
      * retry has to re-read the row it lost, and per-subject reads keep that retry scoped to the row
      * that was actually contended.
      */
    override def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      withWindows(clientId, challengeType): (tenantId, limits, wLimits) =>
        ZIO
          .foreachPar(subjects)(chargeOne(tenantId, _, challengeType, limits, wLimits))
          .map(_.foldLeft(LimitStatus.Allowed)(worstStatus))
