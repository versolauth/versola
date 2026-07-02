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

  /** Returns the worst limit status across multiple subjects in a single DB query. */
  def isBannedAny(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus]

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

  /** Records an attempt for each subject in a single fetch + parallel upserts, and returns the worst status. */
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
        case ChallengeType.OtpRequest      => limits.otpRequest
        case ChallengeType.OtpSubmit       => limits.otpSubmit
        case ChallengeType.PasswordSubmit  => limits.passwordSubmit
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
        case (LimitStatus.RateLimited(s), _)                   => LimitStatus.RateLimited(s)
        case (_, LimitStatus.RateLimited(s))                   => LimitStatus.RateLimited(s)
        case _                                                 => LimitStatus.Allowed

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

    override def isBannedAny(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      for
        limits <- configService.getSubmissionLimits(clientId)
        wLimits = windowLimits(limits, challengeType)
        result <-
          if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
          else
            configService.find(clientId).flatMap:
              case None => ZIO.succeed(LimitStatus.Allowed)
              case Some(client) =>
                Clock.instant.flatMap: now =>
                  throttleRepo.findAllForSubjects(client.tenantId, subjects, challengeType).map:
                    _.map(evaluate(_, wLimits, now)).foldLeft(LimitStatus.Allowed)(worstStatus)
      yield result

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

    override def reset(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[Unit] =
      configService.find(clientId).flatMap:
        case None         => ZIO.unit
        case Some(client) => throttleRepo.delete(client.tenantId, subject, challengeType)

    override def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType): Task[LimitStatus] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        val wLimits = windowLimits(limits, challengeType)
        if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
        else
          configService.find(clientId).flatMap:
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(client) =>
              Clock.instant.flatMap: now =>
                val nowEpoch = now.getEpochSecond
                val longestWindow = wLimits.map(_.windowSeconds).max
                throttleRepo.find(client.tenantId, subject, challengeType).flatMap: recordOpt =>
                  val existing = recordOpt.fold[List[Long]](Nil)(_.attempts)
                  val pruned = existing.filter(_ > nowEpoch - longestWindow)
                  val updated = pruned :+ nowEpoch

                  val banExceeded = banWindow(wLimits).exists(windowExceeded(updated, nowEpoch, _))
                  val applyBan = banExceeded && limits.banDurationSeconds > 0

                  // Clear attempts on ban so the user starts fresh after the ban expires.
                  val finalAttempts = if applyBan then Nil else updated
                  val bannedUntil =
                    if applyBan then Some(now.plusSeconds(limits.banDurationSeconds))
                    else recordOpt.flatMap(_.bannedUntil).filter(_.isAfter(now))

                  val expiresAt =
                    if applyBan then bannedUntil.get
                    else
                      val ttl = now.plusSeconds(longestWindow)
                      bannedUntil.filter(_.isAfter(ttl)).getOrElse(ttl)

                  throttleRepo.upsert(
                    ChallengeThrottleRecord(
                      tenantId = client.tenantId,
                      subject = subject,
                      challengeType = challengeType,
                      attempts = finalAttempts,
                      bannedUntil = bannedUntil,
                      expiresAt = expiresAt,
                    ),
                  ).as:
                    val rlWindows = rateLimitWindows(wLimits)
                    if applyBan then LimitStatus.Banned
                    else if limitsExceeded(updated, nowEpoch, rlWindows) then
                      LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
                    else LimitStatus.Allowed

    override def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType): Task[LimitStatus] =
      configService.getSubmissionLimits(clientId).flatMap: limits =>
        val wLimits = windowLimits(limits, challengeType)
        if wLimits.isEmpty then ZIO.succeed(LimitStatus.Allowed)
        else
          configService.find(clientId).flatMap:
            case None => ZIO.succeed(LimitStatus.Allowed)
            case Some(client) =>
              Clock.instant.flatMap: now =>
                val nowEpoch = now.getEpochSecond
                val longestWindow = wLimits.map(_.windowSeconds).max
                throttleRepo.findAllForSubjects(client.tenantId, subjects, challengeType).flatMap: existingRecords =>
                  val bySubject = existingRecords.map(r => r.subject -> r).toMap
                  ZIO.collectAllPar(subjects.map: subject =>
                    val existing = bySubject.get(subject).fold[List[Long]](Nil)(_.attempts)
                    val pruned = existing.filter(_ > nowEpoch - longestWindow)
                    val updated = pruned :+ nowEpoch
                    val banExceeded = banWindow(wLimits).exists(windowExceeded(updated, nowEpoch, _))
                    val applyBan = banExceeded && limits.banDurationSeconds > 0
                    val finalAttempts = if applyBan then Nil else updated
                    val bannedUntil =
                      if applyBan then Some(now.plusSeconds(limits.banDurationSeconds))
                      else bySubject.get(subject).flatMap(_.bannedUntil).filter(_.isAfter(now))
                    val expiresAt =
                      if applyBan then bannedUntil.get
                      else
                        val ttl = now.plusSeconds(longestWindow)
                        bannedUntil.filter(_.isAfter(ttl)).getOrElse(ttl)
                    throttleRepo.upsert(
                      ChallengeThrottleRecord(
                        tenantId = client.tenantId,
                        subject = subject,
                        challengeType = challengeType,
                        attempts = finalAttempts,
                        bannedUntil = bannedUntil,
                        expiresAt = expiresAt,
                      ),
                    ).as:
                      val rlWindows = rateLimitWindows(wLimits)
                      if applyBan then LimitStatus.Banned
                      else if limitsExceeded(updated, nowEpoch, rlWindows) then
                        LimitStatus.RateLimited(retryAfterSeconds(updated, nowEpoch, rlWindows))
                      else LimitStatus.Allowed
                  ).map(_.foldLeft(LimitStatus.Allowed)(worstStatus))
