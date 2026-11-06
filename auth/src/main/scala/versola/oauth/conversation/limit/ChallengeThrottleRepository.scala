package versola.oauth.conversation.limit

import versola.oauth.client.model.{RateLimit, TenantId}
import zio.{NonEmptyChunk, Task}

import java.time.Instant

enum ChallengeType:
  case OtpRequest, OtpSubmit, PasswordSubmit, PasskeyAssertion
  // Step-up counts attempts in its own throttle records, so failures there neither spend nor are
  // spent by the budget of a regular sign-in, and are configured by `SubmissionLimits.stepUp`.
  case StepUpOtpRequest, StepUpOtpSubmit, StepUpPasswordSubmit, StepUpPasskeyAssertion

  def isStepUp: Boolean = this match
    case StepUpOtpRequest | StepUpOtpSubmit | StepUpPasswordSubmit | StepUpPasskeyAssertion => true
    case _ => false

object ChallengeType:
  /** The challenge type to throttle under: the step-up variant when the conversation re-verifies an
    * existing session toward a target ACR, otherwise `base` itself.
    */
  def forConversation(stepUp: Boolean, base: ChallengeType): ChallengeType =
    if !stepUp then base
    else
      base match
        case OtpRequest => StepUpOtpRequest
        case OtpSubmit => StepUpOtpSubmit
        case PasswordSubmit => StepUpPasswordSubmit
        case PasskeyAssertion => StepUpPasskeyAssertion
        case stepUpType => stepUpType

case class ChallengeThrottleRecord(
    tenantId: TenantId,
    subject: String,
    challengeType: ChallengeType,
    attempts: List[Long],
    bannedUntil: Option[Instant],
    expiresAt: Instant,
)

/** The outcome of [[ChallengeThrottleRepository.reserveAttempt]]. */
sealed trait Reservation

object Reservation:
  /** The subject was already banned or rate limited; nothing was written. `status` is never `Allowed`. */
  case class Denied(status: LimitStatus) extends Reservation

  /** An attempt was charged, atomically with the check that allowed it. `after` is the status including it
    * (as [[ChallengeThrottleRepository.recordAttempt]] reports it). `previous` and `applied` are the row before and
    * after the charge, and `reservedAt` the instant it was charged at: what [[ChallengeThrottleRepository.refundAttempt]]
    * needs to take this one attempt back.
    */
  case class Granted(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      after: LimitStatus,
      reservedAt: Instant,
      previous: Option[ChallengeThrottleRecord],
      applied: ChallengeThrottleRecord,
  ) extends Reservation

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

  /** Checks the limits and charges an attempt as one atomic step: [[Reservation.Denied]], writing nothing, when
    * the subject is already banned or over a window, [[Reservation.Granted]] otherwise.
    *
    * [[recordAttempt]] is atomic in what it writes but says nothing about what came before it, so a caller that
    * reads the status, checks a secret and only then records a failure lets every request of a burst pass the
    * read. This charges before the secret is checked, so a burst is cut off after the configured number of
    * attempts however its requests interleave. A caller that then finds the secret right takes the charge back
    * with [[refundAttempt]]; a wrong one leaves it.
    */
  def reserveAttempt(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
      now: Instant,
      wLimits: NonEmptyChunk[RateLimit],
      banDurationSeconds: Long,
  ): Task[Reservation]

  /** Takes back one attempt charged by [[reserveAttempt]], atomically. Restores the row as it was before the
    * charge when nothing has touched it since (which also lifts a ban that this very attempt caused); otherwise
    * removes that attempt's timestamp from the list. Nothing to do when the row is gone.
    */
  def refundAttempt(reservation: Reservation.Granted): Task[Unit]

  def delete(
      tenantId: TenantId,
      subject: String,
      challengeType: ChallengeType,
  ): Task[Unit]

  /** Removes all throttle records for a subject across every challenge type. */
  def deleteAllForSubject(tenantId: TenantId, subject: String): Task[Unit]
