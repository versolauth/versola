package versola.oauth.conversation.limit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, RateLimit, ScopeToken, SubmissionLimits, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant

object SubmissionLimiterSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val tenantId = TenantId("default")
  private val subject = "user@example.com"
  private val subject2 = "user-id-2"

  private val client = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Test Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
  )

  // Short window 3/min acts as an immediate rate limit; the broadest 9/hour window applies the ban.
  private val limits = SubmissionLimits(
    otpSubmit = List(
      RateLimit(maxAttempts = 3, windowSeconds = 60),
      RateLimit(maxAttempts = 9, windowSeconds = 3600),
    ),
    banDurationSeconds = 600,
  )

  private def throttleRecord(attempts: List[Long], bannedUntil: Option[Instant], expiresAt: Instant) =
    ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, attempts, bannedUntil, expiresAt)

  class Env:
    val throttleRepo = stub[ChallengeThrottleRepository]
    val configService = stub[OAuthConfigurationService]
    val limiter = SubmissionLimiter.Impl(throttleRepo, configService)

  val spec = suite("SubmissionLimiter")(
    suite("isBanned")(
      test("returns Allowed and skips lookups when no windows are configured") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(SubmissionLimits.empty)
          status <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Allowed,
          env.configService.find.calls.isEmpty,
          env.throttleRepo.find.calls.isEmpty,
        )
      },
      test("returns Allowed when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Allowed, env.throttleRepo.find.calls.isEmpty)
      },
      test("returns Allowed when there is no throttle record") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(None)
          result <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Allowed)
      },
      test("returns Banned while a persisted ban is still active") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(Nil, Some(now.plusSeconds(300)), now.plusSeconds(300))),
          )
          result <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Banned)
      },
      test("returns RateLimited when a short rate-limit window is exceeded") {
        val env = Env()
        for
          now <- Clock.instant
          recent = now.getEpochSecond
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(List(recent, recent, recent), None, now.plusSeconds(3600))),
          )
          result <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result.isInstanceOf[LimitStatus.RateLimited])
      },
      test("returns Allowed when only the broadest window is busy but no ban is set") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          // 8 attempts older than a minute (still inside the hour) plus one fresh attempt
          attempts = List.fill(8)(nowEpoch - 100) :+ nowEpoch
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(attempts, None, now.plusSeconds(3600))),
          )
          result <- env.limiter.isBanned(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Allowed)
      },
    ),
    suite("statusFor")(
      test("queries all challenge types in a single lookup and returns the worst status") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.findAll.succeedsWith(
            List(
              ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, Nil, Some(now.plusSeconds(300)), now.plusSeconds(300)),
            ),
          )
          result <- env.limiter.statusFor(clientId, subject, List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(
          result == LimitStatus.Banned,
          env.throttleRepo.findAll.calls.length == 1,
          env.throttleRepo.find.calls.isEmpty,
        )
      },
      test("returns Allowed when there are no throttle records") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.findAll.succeedsWith(Nil)
          result <- env.limiter.statusFor(clientId, subject, List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(result == LimitStatus.Allowed)
      },
      test("skips the lookup when no challenge type has configured windows") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(SubmissionLimits.empty)
          result <- env.limiter.statusFor(clientId, subject, List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.findAll.calls.isEmpty,
        )
      },
    ),
    suite("statusForSubjects")(
      test("queries all subjects in a single lookup and returns the worst status") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.findAllForSubjects.succeedsWith(
            List(
              ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, Nil, Some(now.plusSeconds(300)), now.plusSeconds(300)),
            ),
          )
          result <- env.limiter.statusForSubjects(clientId, List(subject, "other@example.com"), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Banned,
          env.throttleRepo.findAllForSubjects.calls.length == 1,
          env.throttleRepo.find.calls.isEmpty,
        )
      },
      test("returns Allowed when there are no throttle records") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.findAllForSubjects.succeedsWith(Nil)
          result <- env.limiter.statusForSubjects(clientId, List(subject), ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Allowed)
      },
      test("returns Allowed when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.statusForSubjects(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Allowed, env.throttleRepo.findAllForSubjects.calls.isEmpty)
      },
      test("skips the lookup when no windows are configured") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(SubmissionLimits.empty)
          result <- env.limiter.statusForSubjects(clientId, List(subject), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.findAllForSubjects.calls.isEmpty,
        )
      },
      test("skips the lookup when subjects list is empty") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          result <- env.limiter.statusForSubjects(clientId, Nil, ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.findAllForSubjects.calls.isEmpty,
        )
      },
    ),
    suite("reset")(
      test("deletes the throttle record for the subject") {
        val env = Env()
        for
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.delete.succeedsWith(())
          _ <- env.limiter.reset(clientId, subject, ChallengeType.OtpSubmit)
          deleteTimes = env.throttleRepo.delete.times
          deleteCall = env.throttleRepo.delete.calls.head
        yield assertTrue(
          deleteTimes == 1,
          deleteCall == (tenantId, subject, ChallengeType.OtpSubmit),
        )
      },
      test("is a no-op when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.find.succeedsWith(None)
          _ <- env.limiter.reset(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(env.throttleRepo.delete.calls.isEmpty)
      },
    ),
    suite("recordLimit")(
      test("returns Allowed and skips lookups when no windows are configured") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(SubmissionLimits.empty)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.configService.find.calls.isEmpty,
          env.throttleRepo.recordAttempt.calls.isEmpty,
        )
      },
      test("returns Allowed and skips recordAttempt when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.recordAttempt.calls.isEmpty,
        )
      },
      test("delegates to recordAttempt with the client's tenant, the configured windows, and the current time") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.recordAttempt.succeedsWith(LimitStatus.Banned)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          call = env.throttleRepo.recordAttempt.calls.head
        yield assertTrue(
          result == LimitStatus.Banned,
          call == (tenantId, subject, ChallengeType.OtpSubmit, now, limits.otpSubmit, limits.banDurationSeconds),
        )
      },
    ),
    suite("recordLimitAll")(
      test("returns Allowed and skips lookups when no windows are configured") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(SubmissionLimits.empty)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.configService.find.calls.isEmpty,
          env.throttleRepo.recordAttempt.calls.isEmpty,
        )
      },
      test("returns Allowed and skips recordAttempt when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.recordAttempt.calls.isEmpty,
        )
      },
      test("calls recordAttempt for every subject and returns Allowed") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.recordAttempt.succeedsWith(LimitStatus.Allowed)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
          calls = env.throttleRepo.recordAttempt.calls
          recordedSubjects = calls.map(_._2).toSet
        yield assertTrue(
          result == LimitStatus.Allowed,
          calls.length == 2,
          recordedSubjects == Set(subject, subject2),
          calls.forall(c => c._4 == now && c._5 == limits.otpSubmit && c._6 == limits.banDurationSeconds),
        )
      },
      test("returns the worst status when one subject's recordAttempt reports Banned") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          // `subject` is over the ban threshold; `subject2` isn't.
          _ <- env.throttleRepo.recordAttempt.returnsZIO:
            case (_, subj, _, _, _, _) if subj == subject => ZIO.succeed(LimitStatus.Banned)
            case _ => ZIO.succeed(LimitStatus.Allowed)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Banned,
          env.throttleRepo.recordAttempt.calls.length == 2,
        )
      },
    ),
  )
