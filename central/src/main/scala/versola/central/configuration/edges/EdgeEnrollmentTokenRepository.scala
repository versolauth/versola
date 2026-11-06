package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** The one-time tokens an operator hands to an edge so that it can enrol (see [[EdgeIdentityService]]). Only the hash
  * of a token is stored. */
trait EdgeEnrollmentTokenRepository:
  def create(tokenHash: String, edgeId: EdgeId, expiresAt: Instant): Task[Unit]

  /** The edge a token is for, if it is still good (known, unspent, unexpired), without spending it. For the checks that
    * must come before anything is done on the strength of the token. */
  def peek(tokenHash: String, now: Instant): Task[Option[EdgeId]]

  /** Spends the token in one statement: the edge it was created for, or `None` when it is unknown, already spent or
    * past `expiresAt`. Two concurrent calls with the same token cannot both succeed. */
  def consume(tokenHash: String, now: Instant): Task[Option[EdgeId]]
