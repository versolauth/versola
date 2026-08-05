package versola.oauth.conversation.limit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, RateLimit, SubmissionLimits}
import zio.{Clock, Task, UIO, ZIO, ZLayer}

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
                  case Some(record) => Clock.instant.map(ChallengeThrottleRepository.evaluate(record, wLimits, _))
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
                  .map((ct, wLimits) => byType.get(ct).fold(LimitStatus.Allowed)(ChallengeThrottleRepository.evaluate(_, wLimits, now)))
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
                  .map(s => bySubject.get(s).fold(LimitStatus.Allowed)(ChallengeThrottleRepository.evaluate(_, wLimits, now)))
                  .foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

    /** Deletes the subject's throttle record, clearing its accumulated attempts and any active ban.
      * Called on a successful challenge so the subject starts fresh. No-op when the client is unknown.
      */
    override def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit] =
      configService.find(clientId).flatMap:
        case None => ZIO.unit
        case Some(client) => throttleRepo.delete(client.tenantId, subject, challengeType)

    /** Records a single failed attempt for the subject and returns the resulting status. The
      * prune/append/ban decision itself lives in [[ChallengeThrottleRepository.nextState]],
      * applied by the repository's implementation inside its own atomic read-modify-write so it
      * can't lose concurrent attempts against the same key (issue #91).
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
                throttleRepo.recordAttempt(client.tenantId, subject, challengeType, now, wLimits, limits.banDurationSeconds)

    /** Batch variant of [[recordLimit]]: records a failed attempt for every subject via its own
      * atomic [[ChallengeThrottleRepository.recordAttempt]] call. Used to charge related subjects
      * (e.g. IP and credential) for the same failure. Returns the worst status across all subjects
      * so a ban on any one dominates.
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
                  throttleRepo.recordAttempt(client.tenantId, subject, challengeType, now, wLimits, limits.banDurationSeconds)
                }.map(_.foldLeft(LimitStatus.Allowed)(worstStatus))
