package versola.edge.dpop

import zio.Task

import java.time.Instant

/** The fleet-wide half of replay protection (RFC 9449 §11.1): a record of seen proofs that every
  * replica shares, so that "have I seen this proof?" can be answered for the fleet and not just
  * for the pod the request happened to reach.
  *
  * Separate from [[DpopReplayGuard]] rather than folded into it, because the two answer
  * different questions and fail differently: the guard is total and always available, this is
  * exact and can be unreachable. [[DpopReplayGuard.Shared]] is what puts them in order.
  */
trait DpopProofRepository:

  /** Atomically records a proof's `(jkt, jti)` pair.
    *
    * @param iat the proof's signed creation time, which decides how long the record is kept: it
    *   has to outlive the window in which this proof would still be accepted, and need not
    *   outlive it by more than that. Taking it from the proof rather than from the clock is also
    *   what stops a captured proof from being replayed into a fresh record -- `iat` is covered
    *   by the signature.
    * @return true if this is the first time the pair has been seen and it is now recorded,
    *   false if it was already recorded -- a replay.
    */
  def recordIfAbsent(jkt: String, jti: String, iat: Instant): Task[Boolean]
