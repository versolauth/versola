package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** The one-time tokens an operator hands to an edge so that it can enrol (see [[EdgeIdentityService]]). Only the hash
  * of a token is stored. */
trait EdgeEnrollmentTokenRepository:
  def create(tokenHash: String, edgeId: EdgeId, expiresAt: Instant): Task[Unit]

  /** Spends the token in one statement: the edge it was created for, or `None` when it is unknown, already spent or
    * past `expiresAt`. Two concurrent calls with the same token cannot both succeed. */
  def consume(tokenHash: String, now: Instant): Task[Option[EdgeId]]
