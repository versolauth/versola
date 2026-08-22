package versola.edge.revocation

import zio.stream.Stream

/** Revocations as they are written, by any replica sharing this edge's database. This is
  * what keeps the in-memory list of every replica current without polling.
  */
trait RevocationNotifications:
  def notifications: Stream[Throwable, Revocation]
