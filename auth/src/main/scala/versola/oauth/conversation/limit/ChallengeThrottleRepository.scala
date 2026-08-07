package versola.oauth.conversation.limit

import versola.oauth.client.model.TenantId
import zio.Task

import java.time.Instant

enum ChallengeType:
  case OtpRequest, OtpSubmit, PasswordSubmit, PasskeyAssertion

/** @param version
  *   the version this record was read at; `0` for a record that does not exist yet. [[ChallengeThrottleRepository.upsert]]
  *   only writes when the stored version still matches, so a concurrent write is detected instead of silently overwritten.
  */
case class ChallengeThrottleRecord(
    tenantId: TenantId,
    subject: String,
    challengeType: ChallengeType,
    attempts: List[Long],
    bannedUntil: Option[Instant],
    expiresAt: Instant,
    version: Long = 0,
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

  /** Writes the record only if the stored row is still at `record.version` (or, for version `0`, if no
    * row exists yet), bumping the version on success. Returns `false` when another writer got there
    * first — the caller must re-read and recompute rather than retrying the same write.
    */
  def upsert(record: ChallengeThrottleRecord): Task[Boolean]

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]
