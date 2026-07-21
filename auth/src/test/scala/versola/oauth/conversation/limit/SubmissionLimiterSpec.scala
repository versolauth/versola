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
          env.throttleRepo.upsert.calls.isEmpty,
        )
      },
      test("returns Allowed and skips upsert when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.find.calls.isEmpty,
          env.throttleRepo.upsert.calls.isEmpty,
        )
      },
      test("applies a temporary ban, clears attempts, and returns Banned when the broadest window is exceeded") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          existing = List.fill(8)(nowEpoch - 1)
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(Some(throttleRecord(existing, None, now.plusSeconds(3600))))
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          result == LimitStatus.Banned,
          upserted.attempts.isEmpty,
          upserted.bannedUntil.contains(now.plusSeconds(600)),
          upserted.expiresAt == now.plusSeconds(600),
        )
      },
      test("records the attempt without banning and returns RateLimited when only a short window is exceeded") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(List(nowEpoch, nowEpoch), None, now.plusSeconds(3600))),
          )
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          result.isInstanceOf[LimitStatus.RateLimited],
          upserted.attempts.size == 3,
          upserted.bannedUntil.isEmpty,
          upserted.expiresAt == now.plusSeconds(3600),
        )
      },
      test("prunes attempts that fall outside the broadest window") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          old = nowEpoch - 4000
          recent = nowEpoch - 10
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(List(old, recent), None, now.plusSeconds(3600))),
          )
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          _ <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          upserted.attempts == List(recent, nowEpoch),
          !upserted.attempts.contains(old),
        )
      },
      test("does not ban when banDurationSeconds is zero") {
        val env = Env()
        val noBanLimits = SubmissionLimits(
          otpSubmit = List(RateLimit(maxAttempts = 9, windowSeconds = 3600)),
          banDurationSeconds = 0,
        )
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          existing = List.fill(8)(nowEpoch - 1)
          _ <- env.configService.getSubmissionLimits.succeedsWith(noBanLimits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(Some(throttleRecord(existing, None, now.plusSeconds(3600))))
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          _ <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          upserted.attempts.size == 9,
          upserted.bannedUntil.isEmpty,
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
          env.throttleRepo.upsert.calls.isEmpty,
        )
      },
      test("returns Allowed and skips upsert when the client is unknown") {
        val env = Env()
        for
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.find.calls.isEmpty,
          env.throttleRepo.upsert.calls.isEmpty,
        )
      },
      test("records an attempt for every subject and returns Allowed") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(None)
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
          upsertedSubjects = env.throttleRepo.upsert.calls.map(_.subject).toSet
        yield assertTrue(
          result == LimitStatus.Allowed,
          env.throttleRepo.upsert.calls.length == 2,
          upsertedSubjects == Set(subject, subject2),
          env.throttleRepo.upsert.calls.forall(_.attempts == List(nowEpoch)),
        )
      },
      test("returns the worst status when one subject exceeds the broadest window") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          existing = List.fill(8)(nowEpoch - 1)
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.returnsZIO:
            case (_, s, _) if s == subject =>
              ZIO.succeed(Some(throttleRecord(existing, None, now.plusSeconds(3600))))
            case _ => ZIO.succeed(None)
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          result <- env.limiter.recordLimitAll(clientId, List(subject, subject2), ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Banned,
          env.throttleRepo.upsert.calls.length == 2,
        )
      },
    ),
    suite("compare-and-set")(
      test("recordLimit re-reads and recomputes when the first write loses the race") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          // The competing writer got 8 attempts in between, which tips the subject over the
          // broadest window: the retry must see that and ban, not re-apply the stale count.
          attemptsByVersion = Map(0L -> List.empty[Long], 1L -> List.fill(8)(nowEpoch - 1))
          version <- Ref.make(0L)
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.returnsZIO: _ =>
            version.get.map: v =>
              Some(throttleRecord(attemptsByVersion(v), None, now.plusSeconds(3600)).copy(version = v))
          // Reject the write staged against version 0, then accept the recomputed one.
          _ <- env.throttleRepo.upsert.returnsZIO: record =>
            if record.version == 0 then version.set(1).as(false) else ZIO.succeed(true)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          result == LimitStatus.Banned,
          env.throttleRepo.find.calls.length == 2,
          env.throttleRepo.upsert.calls.length == 2,
        )
      },
      test("recordLimit fails rather than under-counting when the row stays contended") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(Some(throttleRecord(Nil, None, now.plusSeconds(3600))))
          _ <- env.throttleRepo.upsert.succeedsWith(false)
          result <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit).exit
        yield assertTrue(
          result.isFailure,
          env.throttleRepo.upsert.calls.length == SubmissionLimiter.maxWriteAttempts,
        )
      },
    ),
    suite("tryAcquire")(
      test("charges the attempt and returns Allowed when under the limit") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(List(nowEpoch - 1), None, now.plusSeconds(3600))),
          )
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          result <- env.limiter.tryAcquire(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          result == LimitStatus.Allowed,
          upserted.attempts == List(nowEpoch - 1, nowEpoch),
        )
      },
      test("allows exactly maxAttempts claims, unlike recordLimit which reports the limit a claim early") {
        val env = Env()
        // Short window is 3/min, so the third claim must still be permitted.
        val twoAlready = (now: Instant) => List(now.getEpochSecond - 2, now.getEpochSecond - 1)
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(Some(throttleRecord(twoAlready(now), None, now.plusSeconds(3600))))
          _ <- env.throttleRepo.upsert.succeedsWith(true)
          acquired <- env.limiter.tryAcquire(clientId, subject, ChallengeType.OtpSubmit)
          recorded <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          acquired == LimitStatus.Allowed,
          recorded.isInstanceOf[LimitStatus.RateLimited],
        )
      },
      test("reports the limit without charging an attempt once it is reached") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(List.fill(3)(nowEpoch - 1), None, now.plusSeconds(3600))),
          )
          result <- env.limiter.tryAcquire(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          result.isInstanceOf[LimitStatus.RateLimited],
          // Not charged: a flood against someone else's credential must not keep it pinned.
          env.throttleRepo.upsert.calls.isEmpty,
        )
      },
      test("reports Banned without charging while a ban is active") {
        val env = Env()
        for
          now <- Clock.instant
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.succeedsWith(
            Some(throttleRecord(Nil, Some(now.plusSeconds(600)), now.plusSeconds(600))),
          )
          result <- env.limiter.tryAcquire(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(result == LimitStatus.Banned, env.throttleRepo.upsert.calls.isEmpty)
      },
      test("re-evaluates against the winner's state when the claim loses the race") {
        val env = Env()
        for
          now <- Clock.instant
          nowEpoch = now.getEpochSecond
          // A competitor claims the last slot between our read and our write.
          attemptsByVersion = Map(0L -> List(nowEpoch - 1, nowEpoch - 1), 1L -> List.fill(3)(nowEpoch - 1))
          version <- Ref.make(0L)
          _ <- env.configService.getSubmissionLimits.succeedsWith(limits)
          _ <- env.configService.find.succeedsWith(Some(client))
          _ <- env.throttleRepo.find.returnsZIO: _ =>
            version.get.map: v =>
              Some(throttleRecord(attemptsByVersion(v), None, now.plusSeconds(3600)).copy(version = v))
          _ <- env.throttleRepo.upsert.returnsZIO: record =>
            if record.version == 0 then version.set(1).as(false) else ZIO.succeed(true)
          result <- env.limiter.tryAcquire(clientId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          // The retry sees the limit is now reached, so this claim is refused instead of granted.
          result.isInstanceOf[LimitStatus.RateLimited],
          env.throttleRepo.upsert.calls.length == 1,
        )
      },
    ),
  )
