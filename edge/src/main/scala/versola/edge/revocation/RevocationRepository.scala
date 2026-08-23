package versola.edge.revocation

import zio.Task

/** The durable copy of the revocation list. Never read while a request is in flight: it is
  * what a replica catches up from when it starts or when it has just missed notifications,
  * and what an entry survives a restart in.
  */
trait RevocationRepository:
  /** Records revocations, ignoring keys already recorded. Every replica, including the one
    * writing, learns about them through [[RevocationNotifications]] rather than from the
    * return value.
    */
  def revokeAll(revocations: List[Revocation]): Task[Unit]

  /** Every revocation that still matters (`expires_at > now`).
    *
    * Unbounded because the list is: an entry lives only until the last token it could cover
    * expires, so what this returns is the revocations of roughly one access token TTL — a
    * window measured in minutes, not the history of every logout.
    */
  def listActive: Task[List[Revocation]]
