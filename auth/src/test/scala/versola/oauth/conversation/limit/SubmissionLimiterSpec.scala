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
          _ <- env.throttleRepo.upsert.succeedsWith(())
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
          _ <- env.throttleRepo.upsert.succeedsWith(())
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
          _ <- env.throttleRepo.upsert.succeedsWith(())
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
          _ <- env.throttleRepo.upsert.succeedsWith(())
          _ <- env.limiter.recordLimit(clientId, subject, ChallengeType.OtpSubmit)
          upserted = env.throttleRepo.upsert.calls.head
        yield assertTrue(
          upserted.attempts.size == 9,
          upserted.bannedUntil.isEmpty,
        )
      },
    ),
  )
