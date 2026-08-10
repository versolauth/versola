package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import zio.{NonEmptyChunk, Task}

import java.time.Instant

enum ChallengeType:
  case OtpRequest, OtpSubmit, PasswordSubmit, PasskeyAssertion

case class ChallengeThrottleRecord(
    tenantId: TenantId,
    subject: String,
    challengeType: ChallengeType,
    attempts: List[Long],
    bannedUntil: Option[Instant],
    expiresAt: Instant,
)

trait ChallengeThrottleRepository:
  def find(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Option[ChallengeThrottleRecord]]

  /** Loads the throttle records for a subject across the given challenge types in a single query. */
  def findAll(
      tenantId: TenantId,
      subject: String,
      challengeTypes: List[ChallengeType],
  ): Task[List[ChallengeThrottleRecord]]

  /** Loads the throttle records for multiple subjects for a single challenge type in a single query. */
  def findAllForSubjects(
      tenantId: TenantId,
      subjects: List[String],
      challengeType: ChallengeType,
  ): Task[List[ChallengeThrottleRecord]]

  def upsert(record: ChallengeThrottleRecord): Task[Unit]

  /** Records an attempt against `wLimits`/`banDurationSeconds` and returns the resulting status.
    *
    * Implementations must apply [[ThrottlePolicy.nextState]] and persist its result as a single
    * atomic read-modify-write, so that concurrent attempts against the same key can't overwrite
    * each other — including the very first attempt for a key that has no row yet (issue #91).
    */
  def recordAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): Task[LimitStatus]

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]
