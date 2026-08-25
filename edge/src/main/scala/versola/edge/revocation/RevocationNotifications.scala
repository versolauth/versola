package versola.edge.revocation

import zio.stream.Stream

/** Revocations as they are written, by any replica sharing this edge's database. This is
  * what keeps the in-memory list of every replica current without polling.
  */
trait RevocationNotifications:
  def notifications: Stream[Throwable, RevocationEvent]

enum RevocationEvent:
  /** The feed (re)connected, so revocations written while it was down were never delivered.
    * The cache has to be caught up from the table before it can be trusted again — a catch-up
    * from where it last left off, not a rebuild: what it already holds stays valid.
    */
  case Resubscribed

  /** A revocation written by this or another replica. */
  case Revoked(revocation: Revocation)
