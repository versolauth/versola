package versola.oauth.conversation.limit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, RateLimit, SubmissionLimits, TenantId}
import zio.{Clock, NonEmptyChunk, Task, ZIO, ZLayer}

import java.time.Instant

enum LimitStatus:
  case Allowed
  case RateLimited(retryAfterSeconds: Long) // Short-window limit exceeded; resolves on its own when the window slides
  case Banned                               // Persistent ban applied (longest window exceeded)

/** The outcome of [[SubmissionLimiter.acquireAll]]. */
enum Acquisition:
  /** One of the subjects is banned or over a window; no attempt was charged to any of them. `status` is never `Allowed`. */
  case Denied(status: LimitStatus)

  /** An attempt was charged to every subject. `after` is the worst status including it (the one a wrong answer is
    * shown); `charges` is what [[SubmissionLimiter.refund]] takes back when the answer turns out to be right.
    */
  case Granted(after: LimitStatus, charges: List[Reservation.Granted])

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
    * OTP, say) rather than a failure being punished. [[recordLimit]] would charge — and count —
    * every request in a flood even after the subject is already over the limit; this checks first
    * and only calls through to [[ChallengeThrottleRepository.recordAttempt]] when that check finds
    * the subject `Allowed`, so a flood against someone else's credential stops adding attempts once
    * denied rather than padding their counter for as long as it lasts.
    */
  def tryAcquire(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus]

  /** Checks the limits of every subject and charges an attempt to each, atomically per subject, before the caller
    * looks at the answer: [[Acquisition.Denied]] when any subject is already banned or over a window (the attempts
    * already charged to the others are taken back), [[Acquisition.Granted]] otherwise.
    *
    * This is the gate for checking a secret. Reading the status first and recording a failure afterwards lets a
    * burst of requests all pass the read, and each of them checks its guess; here the burst is cut off at the
    * configured number of attempts. A caller that finds the answer right takes the charges back with [[refund]],
    * so a correct password or code spends none of the budget; a wrong answer leaves the charge, and a request that
    * fails in between leaves it too (the safe side). With no windows configured, or an unknown client, nothing is
    * charged and the result is `Granted(Allowed, Nil)`.
    */
  def acquireAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[Acquisition]

  /** Takes back the attempts charged by [[acquireAll]], for an answer that turned out to be right. Best effort: a failure is logged and
    * leaves the attempts charged, it does not fail the caller.
    */
  def refund(charges: List[Reservation.Granted]): Task[Unit]

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

    /** The configured windows for a challenge type, or `None` when throttling is switched off for
      * it. Returning `NonEmptyChunk` means "has any windows configured" is answered once, here,
      * and every downstream call to [[ThrottlePolicy]] carries the proof instead of re-checking.
      */
    private def windowLimits(allLimits: SubmissionLimits, ct: ChallengeType): Option[NonEmptyChunk[RateLimit]] =
      val limits = limitsFor(allLimits, ct)
      val configured = ct match
        case ChallengeType.OtpRequest | ChallengeType.StepUpOtpRequest => limits.otpRequest
        case ChallengeType.OtpSubmit | ChallengeType.StepUpOtpSubmit => limits.otpSubmit
        case ChallengeType.PasswordSubmit | ChallengeType.StepUpPasswordSubmit => limits.passwordSubmit
        case ChallengeType.PasskeyAssertion | ChallengeType.StepUpPasskeyAssertion => limits.passkeyAssertion
      NonEmptyChunk.fromIterableOption(configured)

    /** Step-up challenge types are governed by the tenant's step-up limits, the rest by the sign-in ones. */
    private def limitsFor(limits: SubmissionLimits, ct: ChallengeType): SubmissionLimits =
      if ct.isStepUp then limits.forStepUp else limits

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
        result <-
          windowLimits(limits, challengeType) match
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(wLimits) =>
              configService.find(clientId).flatMap:
                case None => ZIO.succeed(LimitStatus.Allowed)
                case Some(client) =>
                  throttleRepo.find(client.tenantId, subject, challengeType).flatMap:
                    case None => ZIO.succeed(LimitStatus.Allowed)
                    case Some(record) => Clock.instant.map(ThrottlePolicy.evaluate(record, wLimits, _))
      yield result

    /** Read-only status check for one subject across several challenge types in a single lookup.
      * Keeps only the challenge types that have configured windows, fetches all their records at
      * once, evaluates each, and folds them into the worst status. `Allowed` when none apply.
      */
    override def statusFor(clientId: ClientId, subject: String, challengeTypes: List[ChallengeType]): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        typeWindows = challengeTypes.flatMap(ct => windowLimits(limits, ct).map(ct -> _).toList)
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
                  .map((ct, wLimits) => byType.get(ct).fold(LimitStatus.Allowed)(ThrottlePolicy.evaluate(_, wLimits, now)))
                  .foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

    /** Read-only status check for several subjects of one challenge type in a single lookup.
      * Fetches all matching records at once, evaluates each subject (missing record => `Allowed`),
      * and folds them into the worst status so a ban on any subject dominates.
      */
    override def statusForSubjects(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        result <-
          windowLimits(limits, challengeType).filter(_ => subjects.nonEmpty) match
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(wLimits) =>
              configService.find(clientId).flatMap:
                case None => ZIO.succeed(LimitStatus.Allowed)
                case Some(client) =>
                  for
                    records <- throttleRepo.findAllForSubjects(client.tenantId, subjects, challengeType)
                    now <- Clock.instant
                    bySubject = records.map(r => r.subject -> r).toMap
                  yield subjects
                    .map(s => bySubject.get(s).fold(LimitStatus.Allowed)(ThrottlePolicy.evaluate(_, wLimits, now)))
                    .foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

    /** Deletes the subject's throttle record, clearing its accumulated attempts and any active ban.
      * Called on a successful challenge so the subject starts fresh. No-op when the client is unknown.
      */
    override def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit] =
      configService.find(clientId).flatMap:
        case None => ZIO.unit
        case Some(client) => throttleRepo.delete(client.tenantId, subject, challengeType)

    /** Shared preamble for the write paths: resolves the windows configured for the challenge type,
      * the owning tenant, and the current instant, and hands them to `f`. Short-circuits to
      * `whenOff` (`Allowed`, for the callers that report a status) when no windows are configured or
      * the client is unknown, matching the read-only checks above.
      */
    private def withWindows[A](clientId: ClientId, challengeType: ChallengeType, whenOff: => A)(
        f: (TenantId, SubmissionLimits, NonEmptyChunk[RateLimit], Instant) => Task[A],
    ): Task[A] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        windowLimits(limits, challengeType) match
          case None => ZIO.succeed(whenOff)
          case Some(wLimits) =>
            configService.find(clientId).flatMap:
              case None => ZIO.succeed(whenOff)
              case Some(client) =>
                Clock.instant.flatMap(now => f(client.tenantId, limitsFor(limits, challengeType), wLimits, now))

    override def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      withWindows[LimitStatus](clientId, challengeType, LimitStatus.Allowed): (tenantId, limits, wLimits, now) =>
        throttleRepo.recordAttempt(tenantId, subject, challengeType, now, wLimits, limits.banDurationSeconds)

    /** Claims one attempt: returns the status as of *before* this attempt, and only records it when
      * that status is `Allowed`.
      *
      * This is the gate for actions where the request itself is the thing being limited (sending an
      * OTP, say) rather than a failure being punished. [[recordLimit]] would charge — and count —
      * every request in a flood even after the subject is already over the limit; this checks first
      * and only calls through to [[ChallengeThrottleRepository.recordAttempt]] when that check finds
      * the subject `Allowed`, so a flood against someone else's credential stops adding attempts
      * once denied rather than padding their counter for as long as it lasts.
      */
    override def tryAcquire(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      acquireAll(clientId, List(subject), challengeType).map:
        case Acquisition.Denied(status) => status
        // A granted attempt is an allowed request, as the contract above says: the status as of before this attempt. `after`,
        // which counts it, would refuse the maxAttempts-th request (it is the one that reaches the limit) although it fits.
        case Acquisition.Granted(_, _) => LimitStatus.Allowed

    // The subjects are charged one after another, in one fixed order, and the first refusal stops it: a request that loses the first
    // subject never touches the second. Charged in parallel, two requests of a burst could each win a last slot on a different subject, each
    // find the other subject full and give its slot back, and the burst would be cut off below the configured number of attempts. In one order
    // the winners of the first subject are the only ones to reach the second, so exactly the configured number get through.
    override def acquireAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[Acquisition] =
      withWindows[Acquisition](clientId, challengeType, Acquisition.Granted(LimitStatus.Allowed, Nil)): (tenantId, limits, wLimits, now) =>
        def charge(remaining: List[String], granted: List[Reservation.Granted]): Task[Acquisition] =
          remaining match
            case Nil =>
              val charges = granted.reverse
              ZIO.succeed(Acquisition.Granted(charges.map(_.after).foldLeft(LimitStatus.Allowed)(worstStatus), charges))
            case subject :: rest =>
              throttleRepo.reserveAttempt(tenantId, subject, challengeType, now, wLimits, limits.banDurationSeconds).flatMap:
                case charged: Reservation.Granted => charge(rest, charged :: granted)
                // Charged to the subjects before this one and refused here: the request will not check anything, so it must not cost them.
                case Reservation.Denied(status) => refund(granted).as(Acquisition.Denied(status))
        charge(subjects.distinct.sorted, Nil)

    // Best effort, on purpose: the answer was right and the user is signed in; a failed give-back leaves the attempt charged, which is the
    // safe side, whereas failing here would answer a correct password or code with an error. Nothing an attacker can trigger.
    override def refund(charges: List[Reservation.Granted]): Task[Unit] =
      ZIO
        .foreachParDiscard(charges)(throttleRepo.refundAttempt)
        .catchAllCause(cause => ZIO.logWarningCause("Could not give back a throttle attempt for a right answer; it stays charged", cause))

    /** Batch variant of [[recordLimit]]: records a failed attempt for every subject via its own
      * atomic [[ChallengeThrottleRepository.recordAttempt]] call. Used to charge related subjects
      * (e.g. IP and credential) for the same failure. Returns the worst status across all subjects
      * so a ban on any one dominates.
      *
      * Each subject's read-modify-write runs in its own transaction that only ever locks that one
      * row, so running them in parallel can't deadlock.
      */
    override def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      withWindows[LimitStatus](clientId, challengeType, LimitStatus.Allowed): (tenantId, limits, wLimits, now) =>
        ZIO
          .foreachPar(subjects)(throttleRepo.recordAttempt(tenantId, _, challengeType, now, wLimits, limits.banDurationSeconds))
          .map(_.foldLeft(LimitStatus.Allowed)(worstStatus))
