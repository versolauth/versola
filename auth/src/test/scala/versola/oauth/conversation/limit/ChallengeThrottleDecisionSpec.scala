package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import versola.util.UnitSpecBase
import zio.test.*

import java.time.Instant

/** Pure, DB-free coverage of the prune/append/ban decision in
  * [[ChallengeThrottleRepository.nextState]] and the read-only [[ChallengeThrottleRepository.evaluate]].
  * The Postgres implementation's own spec only smoke-tests that these decisions get persisted
  * correctly (see `ChallengeThrottleRepositorySpec`); the exhaustive edge cases belong here since
  * they need no database at all.
  */
object ChallengeThrottleDecisionSpec extends UnitSpecBase:

  private val tenantId = TenantId("test-tenant")
  private val subject = "user@example.com"

  // Short window 3/min acts as an immediate rate limit; the broadest 9/hour window applies the ban.
  private val wLimits = List(
    RateLimit(maxAttempts = 3, windowSeconds = 60),
    RateLimit(maxAttempts = 9, windowSeconds = 3600),
  )
  private val banDurationSeconds = 600L

  private def record(attempts: List[Long], bannedUntil: Option[Instant], expiresAt: Instant) =
    ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, attempts, bannedUntil, expiresAt)

  private def next(
      recordOpt: Option[ChallengeThrottleRecord],
      now: Instant,
      wLimits: List[RateLimit] = wLimits,
      banDurationSeconds: Long = banDurationSeconds,
  ) = ChallengeThrottleRepository.nextState(recordOpt, tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)

  val spec = suite("ChallengeThrottleRepository")(
    suite("nextState")(
      test("first attempt for a new key appends a single entry and returns Allowed") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val (updated, status) = next(None, now)
        assertTrue(
          status == LimitStatus.Allowed,
          updated.attempts == List(now.getEpochSecond),
          updated.bannedUntil.isEmpty,
        )
      },
      test("applies a temporary ban, clears attempts, and returns Banned when the broadest window is exceeded") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val existing = List.fill(8)(nowEpoch - 1)
        val (updated, status) = next(Some(record(existing, None, now.plusSeconds(3600))), now)
        assertTrue(
          status == LimitStatus.Banned,
          updated.attempts.isEmpty,
          updated.bannedUntil.contains(now.plusSeconds(600)),
          updated.expiresAt == now.plusSeconds(600),
        )
      },
      test("records the attempt without banning and returns RateLimited when only a short window is exceeded") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val (updated, status) = next(Some(record(List(nowEpoch, nowEpoch), None, now.plusSeconds(3600))), now)
        assertTrue(
          status.isInstanceOf[LimitStatus.RateLimited],
          updated.attempts.size == 3,
          updated.bannedUntil.isEmpty,
          updated.expiresAt == now.plusSeconds(3600),
        )
      },
      test("prunes attempts that fall outside the broadest window") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val old = nowEpoch - 4000
        val recent = nowEpoch - 10
        val (updated, _) = next(Some(record(List(old, recent), None, now.plusSeconds(3600))), now)
        assertTrue(
          updated.attempts == List(recent, nowEpoch),
          !updated.attempts.contains(old),
        )
      },
      test("does not ban when banDurationSeconds is zero, even if the broadest window is exceeded") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val existing = List.fill(8)(nowEpoch - 1)
        val (updated, status) =
          next(Some(record(existing, None, now.plusSeconds(3600))), now, banDurationSeconds = 0)
        assertTrue(
          status.isInstanceOf[LimitStatus.RateLimited],
          updated.attempts.size == 9,
          updated.bannedUntil.isEmpty,
        )
      },
      test("reports Banned and leaves the record untouched when an existing ban is still active") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val activeBan = now.plusSeconds(300)
        val existing = record(Nil, Some(activeBan), activeBan)
        val (updated, status) = next(Some(existing), now)
        assertTrue(status == LimitStatus.Banned, updated == existing)
      },
      test("drops an expired ban and evaluates the attempt normally") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val expiredBan = now.minusSeconds(1)
        val (updated, status) = next(Some(record(Nil, Some(expiredBan), now)), now)
        assertTrue(status == LimitStatus.Allowed, updated.bannedUntil.isEmpty)
      },
      test("still rate-limits when only a single window is configured and banDurationSeconds is zero") {
        // A lone window would otherwise be carved out entirely as "the ban window" and excluded
        // from rate-limit checks, silently disabling throttling whenever there's no separate ban
        // duration configured for it.
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val singleWindow = List(RateLimit(maxAttempts = 3, windowSeconds = 60))
        val existing = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(60))
        val (_, status) = next(Some(existing), now, wLimits = singleWindow, banDurationSeconds = 0)
        assertTrue(status.isInstanceOf[LimitStatus.RateLimited])
      },
      test("throws on an empty wLimits list rather than silently allowing everything") {
        assertTrue(
          scala.util.Try(next(None, Instant.parse("2030-01-01T00:00:00Z"), wLimits = Nil)).isFailure,
        )
      },
    ),
    suite("evaluate")(
      test("returns Banned while a persisted ban is still active") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val rec = record(Nil, Some(now.plusSeconds(300)), now.plusSeconds(300))
        assertTrue(ChallengeThrottleRepository.evaluate(rec, wLimits, now) == LimitStatus.Banned)
      },
      test("returns RateLimited when a short rate-limit window is exceeded") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val recent = now.getEpochSecond
        val rec = record(List(recent, recent, recent), None, now.plusSeconds(3600))
        assertTrue(ChallengeThrottleRepository.evaluate(rec, wLimits, now).isInstanceOf[LimitStatus.RateLimited])
      },
      test("returns Allowed when only the broadest window is busy but no ban is set") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val attempts = List.fill(8)(nowEpoch - 100) :+ nowEpoch
        val rec = record(attempts, None, now.plusSeconds(3600))
        assertTrue(ChallengeThrottleRepository.evaluate(rec, wLimits, now) == LimitStatus.Allowed)
      },
      test("still rate-limits against a single configured window") {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val nowEpoch = now.getEpochSecond
        val rec = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(60))
        val singleWindow = List(RateLimit(maxAttempts = 3, windowSeconds = 60))
        assertTrue(ChallengeThrottleRepository.evaluate(rec, singleWindow, now).isInstanceOf[LimitStatus.RateLimited])
      },
    ),
  )
