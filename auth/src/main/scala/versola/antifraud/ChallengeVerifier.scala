package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.model.AuthId
import zio.*

/** What a challenge is for: the request it is set for and the destination of that request. A solution is only good
  * for the subject its challenge was made for.
  *
  * @param generation says which code of the conversation the request is for: the moment the last one was sent, 0 before
  *                   any. Sending a code moves it on, which is what spends every challenge made before.
  */
case class ChallengeSubject(
    tenantId: Option[TenantId],
    clientId: ClientId,
    conversationId: AuthId,
    subjectHash: String,
    generation: Long,
)

/** A challenge to put to the client. `token` is what the verifier needs back to check a solution; `params` is what
  * the client needs to solve it, and means whatever `kind` says it means. `level` is how hard it is, for the log.
  */
case class Challenge(
    kind: String,
    token: String,
    level: Int,
    params: Map[String, String],
)

enum ChallengeResult:
  case Passed

  /** Not a solution. `attempts` is how many wrong ones the request has made, this included. */
  case Failed(attempts: Int)
  case Expired

/** Something a client has to do before a request is let through, which a script that only sends requests does not do:
  * proof of work today ([[PowChallengeVerifier]]), a provider's widget (Turnstile and the like) when one is added.
  *
  * A verifier keeps no state of its own. A token is bound to the conversation and to its [[ChallengeSubject.generation]],
  * so it is spent by the code it lets through being sent, which is what makes a solution single-use.
  */
trait ChallengeVerifier:
  /** The challenge for a request that scored `score` and has answered `attempts` wrong ones, or `None` when none can be
    * made (the verifier is off, or failed): the request is then let through as if it had not been challenged, the
    * engine's fail-open rule. */
  def issue(subject: ChallengeSubject, score: Int, attempts: Int): UIO[Option[Challenge]]

  /** Whether `solution` solves `token` for `subject`. Never fails: anything that cannot be checked is `Failed`. */
  def verify(subject: ChallengeSubject, token: String, solution: String): UIO[ChallengeResult]

  /** Whether a `Challenge` decision is put to the client at all; off, it stays the pause it was before challenges. */
  def enabled: Boolean

  /** How many wrong solutions a request may make before it is refused. */
  def maxAttempts: Int

object ChallengeVerifier:
  /** Issues nothing, so a `Challenge` decision stays a pause. */
  object off extends ChallengeVerifier:
    override def issue(subject: ChallengeSubject, score: Int, attempts: Int): UIO[Option[Challenge]] = ZIO.none
    override def verify(subject: ChallengeSubject, token: String, solution: String): UIO[ChallengeResult] = ZIO.succeed(ChallengeResult.Failed(1))
    override def enabled: Boolean = false
    override def maxAttempts: Int = 0
