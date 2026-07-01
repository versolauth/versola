package versola.oauth.conversation.limit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
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
      test("findAllBySubjects returns records for all requested subjects for a single type") {
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit))
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, subj = otherSubject))
          _ <- env.repository.upsert(record(ChallengeType.OtpSubmit))
          found <- env.repository.findAllBySubjects(tenantId, List(subject, otherSubject), ChallengeType.PasswordSubmit)
        yield assertTrue(
          found.map(_.subject).toSet == Set(subject, otherSubject),
          found.forall(_.challengeType == ChallengeType.PasswordSubmit),
        )
      },
      test("findAllBySubjects is scoped by tenant") {
        for
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit))
          _ <- env.repository.upsert(record(ChallengeType.PasswordSubmit, tenant = otherTenant))
          found <- env.repository.findAllBySubjects(tenantId, List(subject), ChallengeType.PasswordSubmit)
        yield assertTrue(found.length == 1, found.forall(_.tenantId == tenantId))
      },
      test("findAllBySubjects returns empty when nothing matches") {
        for
          found <- env.repository.findAllBySubjects(tenantId, List("missing@example.com"), ChallengeType.PasswordSubmit)
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
    )

object ChallengeThrottleRepositorySpec:
  case class Env(repository: ChallengeThrottleRepository)
