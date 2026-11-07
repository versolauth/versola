package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import versola.util.UnitSpecBase
import zio.NonEmptyChunk
import zio.test.*

import java.time.Instant

/** Pure, DB-free coverage of [[ThrottlePolicy]]. The repository specs only smoke-test that these
  * decisions get persisted correctly; the exhaustive edge cases belong here, since they need no
  * database at all.
  */
object ThrottlePolicySpec extends UnitSpecBase:

  private val tenantId = TenantId("test-tenant")
  private val subject = "user@example.com"
  private val now = Instant.parse("2030-01-01T00:00:00Z")
  private val nowEpoch = now.getEpochSecond

  // Short window 3/min acts as an immediate rate limit; the broadest 9/hour window applies the ban.
  private val wLimits = NonEmptyChunk(
    RateLimit(maxAttempts = 3, windowSeconds = 60),
    RateLimit(maxAttempts = 9, windowSeconds = 3600),
  )
  private val banDurationSeconds = 600L

  private def record(attempts: List[Long], bannedUntil: Option[Instant], expiresAt: Instant) =
    ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, attempts, bannedUntil, expiresAt)

  private def next(
      recordOpt: Option[ChallengeThrottleRecord],
      at: Instant = now,
      limits: NonEmptyChunk[RateLimit] = wLimits,
      banDuration: Long = banDurationSeconds,
  ) = ThrottlePolicy.nextState(recordOpt, tenantId, subject, ChallengeType.OtpSubmit, at, limits, banDuration)

  private def reserveAt(recordOpt: Option[ChallengeThrottleRecord]) =
    ThrottlePolicy.reserve(
      recordOpt = recordOpt,
      tenantId = tenantId,
      subject = subject,
      challengeType = ChallengeType.OtpSubmit,
      now = now,
      wLimits = wLimits,
      banDurationSeconds = banDurationSeconds,
    )

  /** The charge `ThrottlePolicy.reserve` makes against `previous`, as the repository would report it. */
  private def charge(previous: Option[ChallengeThrottleRecord]): Reservation.Granted =
    reserveAt(previous) match
      case Right((applied, status)) =>
        Reservation.Granted(
          tenantId = tenantId,
          subject = subject,
          challengeType = ChallengeType.OtpSubmit,
          after = status,
          reservedAt = now,
          previous = previous,
          applied = applied,
        )
      case Left(status) => throw new IllegalStateException(s"expected the attempt to be granted, got $status")

  val spec = suite("ThrottlePolicy")(
    suite("nextState")(
      test("first attempt for a new key appends a single entry and returns Allowed") {
        val (updated, status) = next(None)
        assertTrue(
          status == LimitStatus.Allowed,
          updated.attempts == List(nowEpoch),
          updated.bannedUntil.isEmpty,
        )
      },
      test("applies a temporary ban, clears attempts, and returns Banned when the broadest window is exceeded") {
        val (updated, status) = next(Some(record(List.fill(8)(nowEpoch - 1), None, now.plusSeconds(3600))))
        assertTrue(
          status == LimitStatus.Banned,
          updated.attempts.isEmpty,
          updated.bannedUntil.contains(now.plusSeconds(600)),
          updated.expiresAt == now.plusSeconds(600),
        )
      },
      test("records the attempt without banning and returns RateLimited when only a short window is exceeded") {
        val (updated, status) = next(Some(record(List(nowEpoch, nowEpoch), None, now.plusSeconds(3600))))
        assertTrue(
          status.isInstanceOf[LimitStatus.RateLimited],
          updated.attempts.size == 3,
          updated.bannedUntil.isEmpty,
          updated.expiresAt == now.plusSeconds(3600),
        )
      },
      test("prunes attempts that fall outside the broadest window") {
        val old = nowEpoch - 4000
        val recent = nowEpoch - 10
        val (updated, _) = next(Some(record(List(old, recent), None, now.plusSeconds(3600))))
        assertTrue(updated.attempts == List(recent, nowEpoch), !updated.attempts.contains(old))
      },
      test("does not ban when banDurationSeconds is zero, even if the broadest window is exceeded") {
        val (updated, status) =
          next(Some(record(List.fill(8)(nowEpoch - 1), None, now.plusSeconds(3600))), banDuration = 0)
        assertTrue(
          status.isInstanceOf[LimitStatus.RateLimited],
          updated.attempts.size == 9,
          updated.bannedUntil.isEmpty,
        )
      },
      test("caps retained attempts at the largest configured allowance") {
        // With no ban to clear the list, an unbounded history would grow for as long as an
        // attacker keeps hammering inside the window. 9 is the largest maxAttempts configured.
        val existing = List.fill(40)(nowEpoch - 1)
        val (updated, _) = next(Some(record(existing, None, now.plusSeconds(3600))), banDuration = 0)
        assertTrue(updated.attempts.size == 9, updated.attempts.last == nowEpoch)
      },
      test("reports Banned and leaves the record untouched when an existing ban is still active") {
        val activeBan = now.plusSeconds(300)
        val existing = record(Nil, Some(activeBan), activeBan)
        val (updated, status) = next(Some(existing))
        assertTrue(status == LimitStatus.Banned, updated == existing)
      },
      test("drops an expired ban and evaluates the attempt normally") {
        val (updated, status) = next(Some(record(Nil, Some(now.minusSeconds(1)), now)))
        assertTrue(status == LimitStatus.Allowed, updated.bannedUntil.isEmpty)
      },
      test("still rate-limits when only a single window is configured and banDurationSeconds is zero") {
        // A lone window would otherwise be carved out entirely as "the ban window" and excluded
        // from rate-limit checks, silently disabling throttling.
        val single = NonEmptyChunk(RateLimit(maxAttempts = 3, windowSeconds = 60))
        val existing = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(60))
        val (_, status) = next(Some(existing), limits = single, banDuration = 0)
        assertTrue(status.isInstanceOf[LimitStatus.RateLimited])
      },
      test("treats only one of two identical windows as the ban window") {
        // Selection is by index, not by value, so a duplicated window can't be filtered out twice
        // and leave nothing behind to rate-limit against.
        val duplicated = NonEmptyChunk(RateLimit(3, 60), RateLimit(3, 60))
        val existing = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(60))
        val (_, status) = next(Some(existing), limits = duplicated, banDuration = 0)
        assertTrue(status.isInstanceOf[LimitStatus.RateLimited])
      },
      test("rejects a non-positive maxAttempts instead of indexing out of bounds in retryAfterSeconds") {
        val bad = NonEmptyChunk(RateLimit(maxAttempts = 0, windowSeconds = 60))
        assertTrue(scala.util.Try(next(None, limits = bad)).isFailure)
      },
      test("rejects a non-positive windowSeconds") {
        val bad = NonEmptyChunk(RateLimit(maxAttempts = 3, windowSeconds = 0))
        assertTrue(scala.util.Try(next(None, limits = bad)).isFailure)
      },
    ),
    suite("evaluate")(
      test("returns Banned while a persisted ban is still active") {
        val rec = record(Nil, Some(now.plusSeconds(300)), now.plusSeconds(300))
        assertTrue(ThrottlePolicy.evaluate(rec, wLimits, now) == LimitStatus.Banned)
      },
      test("returns RateLimited when a short rate-limit window is exceeded") {
        val rec = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(3600))
        assertTrue(ThrottlePolicy.evaluate(rec, wLimits, now).isInstanceOf[LimitStatus.RateLimited])
      },
      test("returns Allowed when only the broadest window is busy but no ban is set") {
        val rec = record(List.fill(8)(nowEpoch - 100) :+ nowEpoch, None, now.plusSeconds(3600))
        assertTrue(ThrottlePolicy.evaluate(rec, wLimits, now) == LimitStatus.Allowed)
      },
      test("still rate-limits against a single configured window") {
        val single = NonEmptyChunk(RateLimit(maxAttempts = 3, windowSeconds = 60))
        val rec = record(List(nowEpoch, nowEpoch, nowEpoch), None, now.plusSeconds(60))
        assertTrue(ThrottlePolicy.evaluate(rec, single, now).isInstanceOf[LimitStatus.RateLimited])
      },
      test("rejects a non-positive maxAttempts") {
        val bad = NonEmptyChunk(RateLimit(maxAttempts = -1, windowSeconds = 60))
        val rec = record(Nil, None, now)
        assertTrue(scala.util.Try(ThrottlePolicy.evaluate(rec, bad, now)).isFailure)
      },
    ),
    suite("reserve")(
      test("charges the attempt, like nextState, when no record exists") {
        val reserved = reserveAt(None)
        assertTrue(reserved == Right(next(None)))
      },
      test("charges the attempt that reaches the short window: the status is reported, the attempt is not refused") {
        val reserved = reserveAt(Some(record(List(nowEpoch - 2, nowEpoch - 1), None, now.plusSeconds(3600))))
        assertTrue(
          reserved.exists(_._1.attempts == List(nowEpoch - 2, nowEpoch - 1, nowEpoch)),
          reserved.exists(_._2.isInstanceOf[LimitStatus.RateLimited]),
        )
      },
      test("refuses with RateLimited, writing nothing, once the short window is full") {
        val reserved = reserveAt(Some(record(List.fill(3)(nowEpoch - 1), None, now.plusSeconds(3600))))
        assertTrue(reserved.left.exists(_.isInstanceOf[LimitStatus.RateLimited]))
      },
      test("refuses with Banned while a ban is active") {
        val reserved = reserveAt(Some(record(Nil, Some(now.plusSeconds(600)), now.plusSeconds(600))))
        assertTrue(reserved == Left(LimitStatus.Banned))
      },
      test("charges again after the short window has slid past the old attempts") {
        val reserved = reserveAt(Some(record(List.fill(3)(nowEpoch - 61), None, now.plusSeconds(3600))))
        assertTrue(reserved.exists(_._2 == LimitStatus.Allowed))
      },
    ),
    suite("refund")(
      test("restores the previous row when the row is still exactly what the charge wrote") {
        val previous = record(List(nowEpoch - 5), None, now.plusSeconds(3600))
        val charged = charge(Some(previous))
        assertTrue(ThrottlePolicy.refund(charged.applied, charged) == Some(previous))
      },
      test("deletes the row when the charge created it") {
        val charged = charge(None)
        assertTrue(ThrottlePolicy.refund(charged.applied, charged).isEmpty)
      },
      test("lifts a ban that the charge itself applied") {
        val tight = NonEmptyChunk(RateLimit(maxAttempts = 1, windowSeconds = 3600))
        val (applied, status) = ThrottlePolicy.nextState(None, tenantId, subject, ChallengeType.OtpSubmit, now, tight, banDurationSeconds)
        val charged = Reservation.Granted(
          tenantId = tenantId,
          subject = subject,
          challengeType = ChallengeType.OtpSubmit,
          after = status,
          reservedAt = now,
          previous = None,
          applied = applied,
        )
        assertTrue(status == LimitStatus.Banned, ThrottlePolicy.refund(applied, charged).isEmpty)
      },
      test("lifts the ban although storage kept the ban time to the nearest microsecond only") {
        val tight = NonEmptyChunk(RateLimit(maxAttempts = 1, windowSeconds = 3600))
        val at = now.plusNanos(123_456_789)
        val (applied, status) = ThrottlePolicy.nextState(None, tenantId, subject, ChallengeType.OtpSubmit, at, tight, banDurationSeconds)
        // as Postgres stores it: rounded to the microsecond (…123456789 ns becomes …123457 µs)
        val stored = applied.copy(bannedUntil = applied.bannedUntil.map(at => at.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(1000)))
        val charged = Reservation.Granted(
          tenantId = tenantId,
          subject = subject,
          challengeType = ChallengeType.OtpSubmit,
          after = status,
          reservedAt = at,
          previous = None,
          applied = applied,
        )
        assertTrue(stored.bannedUntil != applied.bannedUntil, ThrottlePolicy.refund(stored, charged).isEmpty)
      },
      test("drops only its own attempt from a row that has moved on") {
        val charged = charge(Some(record(List(nowEpoch - 5), None, now.plusSeconds(3600))))
        val moved = charged.applied.copy(attempts = charged.applied.attempts :+ (nowEpoch + 1))
        val refunded = ThrottlePolicy.refund(moved, charged)
        assertTrue(refunded.exists(_.attempts == List(nowEpoch - 5, nowEpoch + 1)))
      },
      test("drops one occurrence when two attempts share the timestamp") {
        val charged = charge(Some(record(List(nowEpoch), None, now.plusSeconds(3600))))
        val moved = charged.applied.copy(attempts = charged.applied.attempts :+ nowEpoch)
        assertTrue(ThrottlePolicy.refund(moved, charged).exists(_.attempts == List(nowEpoch, nowEpoch)))
      },
      test("leaves a row alone that no longer holds the attempt") {
        val charged = charge(None)
        val expired = charged.applied.copy(attempts = Nil)
        assertTrue(ThrottlePolicy.refund(expired, charged) == Some(expired))
      },
    ),
  )
