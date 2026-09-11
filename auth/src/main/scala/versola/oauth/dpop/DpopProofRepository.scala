package versola.oauth.dpop

import zio.Task

import java.time.Instant

/** Replay protection for DPoP proofs (RFC 9449 §11.1): a proof's `jti` must not be accepted
  * twice. Scoped by `jkt` too, so an accidental `jti` collision between two unrelated keys can't
  * cause a legitimate proof to be rejected as a replay.
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
