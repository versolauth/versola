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
  // logic — that logic gets its own dedicated coverage in ChallengeThrottleDecisionSpec.
  private val wLimits = List(RateLimit(maxAttempts = 1000, windowSeconds = 3600))
  private val banDurationSeconds = 600L

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
          now <- Clock.instant
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Allowed,
          found.exists(_.attempts == List(now.getEpochSecond)),
        )
      },
      test("recordAttempt appends to an existing record (fallback update path)") {
        for
          now <- Clock.instant
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, attempts = List(1000L)))
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Allowed,
          found.exists(_.attempts == List(1000L, now.getEpochSecond)),
        )
      },
      test("recordAttempt persists a ban when the broadest window is exceeded on the very first attempt") {
        // maxAttempts = 1 means a single attempt already exceeds the (sole, hence also broadest)
        // window, so this exercises the ban decision end-to-end through the optimistic-insert path.
        val tightLimits = List(RateLimit(maxAttempts = 1, windowSeconds = 3600))
        for
          now <- Clock.instant
          status <- env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, now, tightLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          status == LimitStatus.Banned,
          found.exists(_.attempts.isEmpty),
          found.exists(_.bannedUntil.contains(now.plusSeconds(banDurationSeconds))),
        )
      },
      test("concurrent recordAttempt calls against an existing key don't lose updates (regression for #91)") {
        // Pre-seed the row so every concurrent call finds it — exercises the fallback `FOR UPDATE`
        // path against the exact scenario the issue describes: concurrent updates to an
        // already-existing attempt list (e.g. a subject already mid-brute-force). Each call gets a
        // distinct synthetic `now` so we can assert exactly which attempts survived, not just how
        // many.
        val concurrentAttempts = 20
        for
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit, attempts = Nil))
          _ <- ZIO.foreachParDiscard(1 to concurrentAttempts): i =>
            env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, Instant.EPOCH.plusSeconds(i.toLong), wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          found.exists(_.attempts.size == concurrentAttempts),
          found.exists(_.attempts.toSet == (1 to concurrentAttempts).map(_.toLong).toSet),
        )
      },
      test("concurrent recordAttempt calls against a brand-new key (no existing row) don't lose updates") {
        // No pre-seed this time — every one of these calls sees no row on its first read, so this
        // exercises the optimistic-insert path specifically: a plain `FOR UPDATE` has nothing to
        // lock when the row doesn't exist yet, so only the `ON CONFLICT DO NOTHING` unique-index
        // resolution (plus the fallback update for whoever loses that race) protects this case.
        val concurrentAttempts = 20
        for
          _ <- ZIO.foreachParDiscard(1 to concurrentAttempts): i =>
            env.repository.recordAttempt(tenantId, subject, ChallengeType.OtpSubmit, Instant.EPOCH.plusSeconds(i.toLong), wLimits, banDurationSeconds)
          found <- env.repository.find(tenantId, subject, ChallengeType.OtpSubmit)
        yield assertTrue(
          found.exists(_.attempts.size == concurrentAttempts),
          found.exists(_.attempts.toSet == (1 to concurrentAttempts).map(_.toLong).toSet),
        )
      },
    )

object ChallengeThrottleRepositorySpec:
  case class Env(repository: ChallengeThrottleRepository)
