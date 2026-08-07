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

  /** Explicit `now` for every `recordAttempt` test, rather than `Clock.instant`. The prune/ban
    * decision is relative to the instant passed in, so an ambient clock would make these tests
    * depend on whether the suite happens to run under `TestClock` (epoch) or a live one — and on
    * whether pre-seeded attempt timestamps land inside the window as a result. Deliberately in the
    * future, too: `expires_at` is derived from `now`, and an epoch-based value would write rows
    * that are already past their TTL.
    */
  private val now = Instant.parse("2030-06-01T00:00:00Z")
  private val nowEpoch = now.getEpochSecond

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
          found.exists(r => r.attempts.distinct.sizeIs == r.attempts.size),
        )
      },
    )

object ChallengeThrottleRepositorySpec:
  case class Env(repository: ChallengeThrottleRepository)
