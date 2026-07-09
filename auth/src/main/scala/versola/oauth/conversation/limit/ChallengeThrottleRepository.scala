package versola.oauth.conversation.limit

import versola.oauth.client.model.TenantId
import zio.Task

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

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]
