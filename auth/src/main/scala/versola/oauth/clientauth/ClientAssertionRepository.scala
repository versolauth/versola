package versola.oauth.clientauth

import zio.Task

import java.time.Instant

/** Replay protection for RFC 7523 client assertions (§3): a `jti` must not be accepted twice
  * while the assertion bearing it is still valid. Scoped by client id too, so an accidental
  * `jti` collision between two unrelated clients cannot reject a legitimate assertion as a
  * replay.
  */
trait ClientAssertionRepository:

  /** Atomically records an assertion's `(clientId, jti)` pair.
    *
    * @param expiresAt the assertion's signed `exp`, which decides how long the record is kept:
    *   past it the assertion is refused on `exp` alone, so the record guards nothing and the
    *   space can be reclaimed. Taken from the signed claim rather than from the clock, which
    *   is also what stops a captured assertion from being replayed into a fresh record.
    * @return true if this is the first time the pair has been seen and it is now recorded,
    *   false if it was already recorded -- a replay.
    */
  def recordIfAbsent(clientId: String, jti: String, expiresAt: Instant): Task[Boolean]
