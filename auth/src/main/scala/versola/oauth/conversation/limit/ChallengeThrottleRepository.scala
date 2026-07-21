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

/** The non-key fields `recordAttempt` lets `mutate` decide. Key fields (`tenantId`, `subject`,
  * `challengeType`) are fixed by the `recordAttempt` call itself, not by `mutate`'s result, so a
  * `mutate` that computes the wrong record can't steer the write to a different row than the one
  * `recordAttempt` locked.
  */
case class ThrottleUpdate(
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

  /** Loads the record for the given key while holding a lock for the rest of the transaction,
    * applies `mutate` to compute both the record to persist and a derived status, and upserts the
    * record — all atomically. This avoids the lost-update race where two concurrent callers read
    * the same record and each write back an update missing the other's attempt (see issue #91),
    * including the case where the record doesn't exist yet: a Postgres advisory lock keyed on
    * (tenantId, subject, challengeType) is taken before the row lookup, so concurrent first
    * attempts for a brand-new subject also serialize instead of racing on the initial insert.
    */
  def recordAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      mutate: Option[ChallengeThrottleRecord] => (ThrottleUpdate, LimitStatus),
  ): Task[LimitStatus]

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]
