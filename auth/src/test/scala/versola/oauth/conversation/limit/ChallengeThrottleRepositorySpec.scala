package versola.oauth.conversation.limit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{RateLimit, TenantId}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

trait ChallengeThrottleRepositorySpec extends DatabaseSpecBase[ChallengeThrottleRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("test-tenant")
  val otherTenant = TenantId("other-tenant")
  val subject = "user@example.com"
  val otherSubject = "other@example.com"

  val expiresAt = Instant.parse("2030-01-01T00:00:00Z")
  val bannedUntil = Instant.parse("2030-01-01T01:00:00Z")

  // High enough that the basic recordAttempt tests below don't themselves trip the rate-limit/ban
  // logic, or the retained-history cap — that logic gets its own coverage in ThrottlePolicySpec.
  private val wLimits = NonEmptyChunk(RateLimit(maxAttempts = 1000, windowSeconds = 3600))
  private val banDurationSeconds = 600L

  // A hard window of 3 attempts a minute; the hour-long window is the broad one that applies the ban, far above these tests.
  private val reserveLimits = NonEmptyChunk(RateLimit(maxAttempts = 3, windowSeconds = 60), RateLimit(maxAttempts = 1000, windowSeconds = 3600))

  /** Explicit `now` for every `recordAttempt` test, rather than `Clock.instant`. The prune/ban
    * decision is relative to the instant passed in, so an ambient clock would make these tests
    * depend on whether the suite happens to run under `TestClock` (epoch) or a live one — and on
    * whether pre-seeded attempt timestamps land inside the window as a result. Deliberately in the
    * future, too: `expires_at` is derived from `now`, and an epoch-based value would write rows
    * that are already past their TTL.
    */
  private val now = Instant.parse("2030-06-01T00:00:00Z")
  private val nowEpoch = now.getEpochSecond

  private def grantedOf(reservation: Reservation): Option[Reservation.Granted] = reservation match
    case granted: Reservation.Granted => Some(granted)
    case _ => None

  def record(
      challengeType: ChallengeType,
      subj: String = subject,
      tenant: TenantId = tenantId,
      attempts: List[Long] = List(1000L, 2000L),
      banned: Option[Instant] = None,
  ): ChallengeThrottleRecord =
    ChallengeThrottleRecord(
      tenantId = tenant,
      subject = subj,
      challengeType = challengeType,
      attempts = attempts,
      bannedUntil = banned,
      expiresAt = expiresAt,
    )

  def testCases(env: ChallengeThrottleRepositorySpec.Env): List[Spec[ChallengeThrottleRepositorySpec.Env & Scope, Any]] =
    List(
      test("upsert and find round-trips a record including attempts and bannedUntil") {
        val rec = record(ChallengeType.OtpSubmit, banned = Some(bannedUntil))
        for
          _ <- env.repository.upsert(rec)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(found.contains(rec))
      },
      test("upsert updates the existing row on conflict") {
        val initial = record(ChallengeType.OtpSubmit, attempts = List(1000L))
        val updated = record(ChallengeType.OtpSubmit, attempts = List(1000L, 2000L, 3000L), banned = Some(bannedUntil))
        for
          _ <- env.repository.upsert(initial)
          _ <- env.repository.upsert(updated)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
          all <- env.repository.findAll(tenantId, subject, List(ChallengeType.OtpSubmit))
        yield assertTrue(found.contains(updated), all.length == 1)
      },
      test("step-up records round-trip and are kept apart from the regular record of the same subject") {
        val regular = record(ChallengeType.PasswordSubmit, attempts = List(1000L))
        val stepUp = record(ChallengeType.StepUpPasswordSubmit, attempts = List(2000L, 3000L), banned = Some(bannedUntil))
        for
          _ <- env.repository.upsert(regular)
          _ <- env.repository.upsert(stepUp)
          foundRegular <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
          foundStepUp <- env.repository.find(tenantId, subject, ChallengeType.StepUpPasswordSubmit)
          _ <- env.repository.delete(tenantId, subject, ChallengeType.StepUpPasswordSubmit)
          afterDelete <- env.repository.findAll(tenantId, subject, ChallengeType.values.toList)
        yield assertTrue(
          foundRegular.contains(regular),
          foundStepUp.contains(stepUp),
          afterDelete == List(regular),
        )
      },
      test("recordAttempt counts step-up attempts without touching the regular counter") {
        val limits = NonEmptyChunk(RateLimit(maxAttempts = 1000, windowSeconds = 3600))
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, attempts = List(nowEpoch - 10)))
          _ <- env.repository.recordAttempt(tenantId, subject, ChallengeType.StepUpPasswordSubmit, now, limits, banDurationSeconds)
          regular <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
          stepUp <- env.repository.find(tenantId, subject, ChallengeType.StepUpPasswordSubmit)
        yield assertTrue(
          regular.map(_.attempts) == Some(List(nowEpoch - 10)),
          stepUp.map(_.attempts) == Some(List(nowEpoch)),
        )
      },
      test("findAll returns only the requested types for the subject in a single query") {
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpRequest))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, subj = otherSubject))
          found <- env.repository.findAll(tenantId, subject, List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(
          found.map(_.challengeType).toSet == Set(ChallengeType.OtpRequest, ChallengeType.OtpSubmit),
          found.forall(_.subject == subject),
        )
      },
      test("findAll is scoped by tenant") {
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, tenant = otherTenant))
          found <- env.repository.findAll(tenantId, subject, List(ChallengeType.OtpSubmit))
        yield assertTrue(found.length == 1, found.forall(_.tenantId == tenantId))
      },
      test("findAll returns empty when nothing matches") {
        for
          found <- env.repository.findAll(tenantId, "missing@example.com", List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(found.isEmpty)
      },
      test("findAllForSubjects returns records for all requested subjects for a single type") {
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit))
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, subj = otherSubject))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          found <- env.repository.findAllForSubjects(tenantId, List(subject, otherSubject), ChallengeType.PasswordSubmit)
        yield assertTrue(
          found.map(_.subject).toSet == Set(subject, otherSubject),
          found.forall(_.challengeType == ChallengeType.PasswordSubmit),
        )
      },
      test("findAllForSubjects is scoped by tenant") {
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit))
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, tenant = otherTenant))
          found <- env.repository.findAllForSubjects(tenantId, List(subject), ChallengeType.PasswordSubmit)
        yield assertTrue(found.length == 1, found.forall(_.tenantId == tenantId))
      },
      test("findAllForSubjects returns empty when nothing matches") {
        for
          found <- env.repository.findAllForSubjects(tenantId, List("missing@example.com"), ChallengeType.PasswordSubmit)
        yield assertTrue(found.isEmpty)
      },
      test("delete removes a single challenge type") {
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpRequest))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          _ <- env.repository.delete(tenantId, subject, ChallengeType.OtpRequest)
          removed <- env.repository.find(tenantId, subject, ChallengeType.OtpRequest)
          kept <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(removed.isEmpty, kept.isDefined)
      },
      test("deleteAllForSubject removes every challenge type for the subject") {
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpRequest))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          _ <- env.repository.deleteAllForSubject(tenantId, subject)
          found <- env.repository.findAll(tenantId, subject, List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit))
        yield assertTrue(found.isEmpty)
      },
      test("recordAttempt inserts a record when none exists (optimistic-insert path)") {
        for
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Allowed,
          found.exists(_.attempts == List(nowEpoch)),
        )
      },
      test("recordAttempt appends to an existing record (fallback update path)") {
        // The prior attempt has to sit inside the configured window, or it would be pruned rather
        // than appended to — hence a timestamp relative to `now` instead of an arbitrary constant.
        val earlier = now.minusSeconds(10).getEpochSecond
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, attempts = List(earlier)))
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Allowed,
          found.exists(_.attempts == List(earlier, nowEpoch)),
        )
      },
      test("recordAttempt persists a ban when the broadest window is exceeded on the very first attempt") {
        // maxAttempts = 1 means a single attempt already exceeds the (sole, hence also broadest)
        // window, so this exercises the ban decision end-to-end through the optimistic-insert path.
        val tightLimits = NonEmptyChunk(RateLimit(maxAttempts = 1, windowSeconds = 3600))
        for
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, tightLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Banned,
          found.exists(_.attempts.isEmpty),
          found.exists(_.bannedUntil.contains(now.plusSeconds(banDurationSeconds))),
        )
      },
      test("recordAttempt reports Banned without recording anything while a ban is active") {
        // Ban has to be active relative to the `now` we pass in, not to wall-clock time.
        val activeBan = now.plusSeconds(3600)
        val banned = ChallengeThrottleRecord(tenantId, subject, ChallengeType.OtpSubmit, Nil, Some(activeBan), activeBan)
        for
          _ <- env.repository.upsert(banned)
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(status == LimitStatus.Banned, found.contains(banned))
      },
      test("concurrent recordAttempt calls against an existing key don't lose updates (regression for #91)") {
        // Pre-seed the row so every concurrent call finds it — exercises the fallback `FOR UPDATE`
        // path against the exact scenario the issue describes: concurrent updates to an
        // already-existing attempt list (e.g. a subject already mid-brute-force). Each call gets a
        // distinct synthetic `now` so we can assert exactly which attempts survived, not just how
        // many.
        val concurrentAttempts = 20
        val expected = (1 to concurrentAttempts).map(i => now.plusSeconds(i.toLong).getEpochSecond).toSet
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, attempts = Nil))
          _ <- ZIO.foreachParDiscard(1 to concurrentAttempts): i =>
            env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now.plusSeconds(i.toLong), wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          found.exists(_.attempts.size == concurrentAttempts),
          found.exists(_.attempts.toSet == expected),
        )
      },
      test("concurrent recordAttempt calls against a brand-new key (no existing row) don't lose updates") {
        // No pre-seed this time — every one of these calls sees no row on its first read, so this
        // exercises the optimistic-insert path specifically: a plain `FOR UPDATE` has nothing to
        // lock when the row doesn't exist yet, so only the `ON CONFLICT DO NOTHING` unique-index
        // resolution (plus the fallback update for whoever loses that race) protects this case.
        val concurrentAttempts = 20
        val expected = (1 to concurrentAttempts).map(i => now.plusSeconds(i.toLong).getEpochSecond).toSet
        for
          _ <- ZIO.foreachParDiscard(1 to concurrentAttempts): i =>
            env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now.plusSeconds(i.toLong), wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          found.exists(_.attempts.size == concurrentAttempts),
          found.exists(_.attempts.toSet == expected),
        )
      },
      test("recordAttempt survives the row being deleted concurrently and never silently drops an attempt") {
        // Exercises the fallback's retry path: a `delete` racing `recordAttempt` can remove the
        // row in the window between the failed INSERT and the `SELECT ... FOR UPDATE`. Every call
        // must still succeed and leave a coherent row behind — what must never happen is a call
        // reporting success while writing nothing at all.
        val concurrentAttempts = 30
        val settle = now.plusSeconds((concurrentAttempts + 1).toLong)
        // Every timestamp any writer could legitimately have recorded, the final one included.
        val allowed =
          (1 to concurrentAttempts + 1).map(i => now.plusSeconds(i.toLong).getEpochSecond).toSet

        // Writers and deleters run as separate parallel fibers, so deletes land at unpredictable
        // points relative to any given write — including inside the window between a write's
        // failed INSERT and its `SELECT ... FOR UPDATE`.
        val writers = ZIO.foreachParDiscard(1 to concurrentAttempts): i =>
          env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now.plusSeconds(i.toLong), wLimits, banDurationSeconds)
        val deleters = ZIO.foreachParDiscard(1 to concurrentAttempts / 3): _ =>
          env.repository.delete(tenantId, subject, ChallengeType.OtpSubmit)

        for
          _ <- writers.zipPar(deleters)
          // One final uncontended attempt, so the assertions below don't depend on whether a
          // deleter or a writer happened to finish last.
          _ <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, settle, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          found.exists(_.attempts.contains(settle.getEpochSecond)),
          found.exists(_.attempts.forall(allowed.contains)),
          // Not `r.attempts.distinct.sizeIs == r.attempts.size`: assertTrue's macro rewrites a
          // top-level `==` into `equalTo`, comparing `sizeIs`'s SizeCompareOps wrapper against a
          // plain Int and failing always. Harmless one level down inside this lambda -- the
          // macro never reaches it -- but not a pattern worth keeping around to be lifted out.
          found.exists(r => r.attempts.distinct.size == r.attempts.size),
        )
      },
      test("reserveAttempt charges the attempt when the subject is under the limits, reporting the row before and after") {
        for
          reservation <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, reserveLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(
          found.exists(_.attempts == List(nowEpoch)),
          reservation.isInstanceOf[Reservation.Granted],
          reservation match
            case g: Reservation.Granted => g.after == LimitStatus.Allowed && g.previous.isEmpty && found.contains(g.applied) && g.reservedAt == now
            case _ => false,
        )
      },
      test("reserveAttempt grants the attempt that reaches the limit, then denies the next without writing") {
        val atLimit = (1 to 3).map(i => now.plusSeconds(i.toLong))
        for
          granted <- ZIO.foreach(atLimit)(at =>
            env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, at, reserveLimits, banDurationSeconds),
          )
          denied <-
            env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now.plusSeconds(4), reserveLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(
          granted.forall(_.isInstanceOf[Reservation.Granted]),
          // the third attempt is itself allowed, and says that the limit is now reached
          granted.last match
            case g: Reservation.Granted => g.after.isInstanceOf[LimitStatus.RateLimited]
            case _ => false,
          denied match
            case Reservation.Denied(status) => status.isInstanceOf[LimitStatus.RateLimited]
            case _ => false,
          // a denied attempt is not recorded: a flood against a subject over the limit does not keep it there
          found.exists(_.attempts.size == 3),
        )
      },
      test("reserveAttempt is denied as Banned, writing nothing, while a ban is active") {
        val activeBan = now.plusSeconds(3600)
        val banned = ChallengeThrottleRecord(tenantId, subject, ChallengeType.PasswordSubmit, Nil, Some(activeBan), activeBan)
        for
          _ <- env.repository.upsert(banned)
          reservation <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, reserveLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(reservation == Reservation.Denied(LimitStatus.Banned), found.contains(banned))
      },
      test("reserveAttempt keeps a burst against a brand-new key to the configured number of attempts") {
        // The property the check-then-record sequence lacks: however the requests interleave, only `maxAttempts` of
        // them are granted, because each is checked and charged in one step under the row's lock.
        val burst = 40
        for
          reservations <- ZIO.foreachPar(1 to burst)(i =>
            env.repository.reserveAttempt(
              tenantId,
              subject,
              ChallengeType.PasswordSubmit,
              now.plusSeconds(i.toLong),
              reserveLimits,
              banDurationSeconds,
            ),
          )
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(
          reservations.count(_.isInstanceOf[Reservation.Granted]) == 3,
          reservations.count(_.isInstanceOf[Reservation.Denied]) == burst - 3,
          found.exists(_.attempts.size == 3),
        )
      },
      test("reserveAttempt keeps a burst against an existing row to the configured number of attempts") {
        val burst = 40
        val earlier = now.minusSeconds(10).getEpochSecond
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, attempts = List(earlier)))
          reservations <- ZIO.foreachPar(1 to burst)(i =>
            env.repository.reserveAttempt(
              tenantId,
              subject,
              ChallengeType.PasswordSubmit,
              now.plusSeconds(i.toLong),
              reserveLimits,
              banDurationSeconds,
            ),
          )
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(
          // one attempt is already on record, so two more fit
          reservations.count(_.isInstanceOf[Reservation.Granted]) == 2,
          found.exists(_.attempts.size == 3),
        )
      },
      test("refundAttempt restores the row to what it was before the charge, deleting one the charge created") {
        val earlier = now.minusSeconds(10).getEpochSecond
        for
          first <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, reserveLimits, banDurationSeconds)
          _ <- ZIO.foreachDiscard(grantedOf(first))(env.repository.refundAttempt)
          afterFirst <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, attempts = List(earlier)))
          second <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, reserveLimits, banDurationSeconds)
          _ <- ZIO.foreachDiscard(grantedOf(second))(env.repository.refundAttempt)
          afterSecond <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(afterFirst.isEmpty, afterSecond.exists(_.attempts == List(earlier)))
      },
      test("refundAttempt removes only its own attempt when the row has moved on since the charge") {
        for
          first <-
            env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now.plusSeconds(1), reserveLimits, banDurationSeconds)
          _ <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now.plusSeconds(2), reserveLimits, banDurationSeconds)
          _ <- ZIO.foreachDiscard(grantedOf(first))(env.repository.refundAttempt)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(found.exists(_.attempts == List(now.plusSeconds(2).getEpochSecond)))
      },
      test("refundAttempt lifts a ban that the refunded attempt itself caused") {
        // maxAttempts = 1 in the sole window: the first attempt trips the ban. A right password on that attempt must
        // not leave the user banned.
        // The instant has nanoseconds, as a clock's does; the row keeps microseconds, and the refund still has to recognise it.
        val tightLimits = NonEmptyChunk(RateLimit(maxAttempts = 1, windowSeconds = 3600))
        val at = now.plusNanos(123_456_789)
        for
          reservation <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, at, tightLimits, banDurationSeconds)
          banned <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
          _ <- ZIO.foreachDiscard(grantedOf(reservation))(env.repository.refundAttempt)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(banned.exists(_.bannedUntil.isDefined), found.isEmpty)
      },
      test("refundAttempt does nothing when the row is gone") {
        for
          reservation <- env.repository.reserveAttempt(tenantId, subject, ChallengeType.PasswordSubmit, now, reserveLimits, banDurationSeconds)
          _ <- env.repository.delete(tenantId, subject, ChallengeType.PasswordSubmit)
          _ <- ZIO.foreachDiscard(grantedOf(reservation))(env.repository.refundAttempt)
          found <- env.repository.find(tenantId, subject, ChallengeType.PasswordSubmit)
        yield assertTrue(found.isEmpty)
      },
    )

object ChallengeThrottleRepositorySpec:
  case class Env(repository: ChallengeThrottleRepository)
