package versola.oauth.dpop

import zio.{Duration, Task}

/** Replay protection for DPoP proofs (RFC 9449 \u00a711.1): a proof's `jti` must not be accepted
  * twice. Scoped by `jkt` too, so an accidental `jti` collision between two unrelated keys can't
  * cause a legitimate proof to be rejected as a replay.
  */
trait DpopProofRepository:

  /** Atomically records a proof's `(jkt, jti)` pair, kept for `ttl`.
    *
    * @return true if this is the first time the pair has been seen and it is now recorded,
    *   false if it was already recorded -- a replay.
    */
  def recordIfAbsent(jkt: String, jti: String, ttl: Duration): Task[Boolean]
