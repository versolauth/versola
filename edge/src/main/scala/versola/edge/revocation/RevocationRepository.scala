package versola.edge.revocation

import zio.Task

import java.time.Instant

/** How far through the list a replica has read.
  *
  * `revoked_at` alone would not do: two revocations written in the same instant would make
  * the position ambiguous, and a page boundary falling between them would skip one. The key
  * breaks the tie, which is why rows are ordered and resumed by the pair.
  */
case class RevocationCursor(revokedAt: Instant, revokedKey: String)

object RevocationCursor:
  /** Before any row that exists, since `revoked_at` is the instant of a write. Reading from
    * here is what loads the whole list, which is what a replica does once, at startup.
    */
  val Beginning: RevocationCursor = RevocationCursor(Instant.EPOCH, "")

  given Ordering[RevocationCursor] =
    Ordering.by(cursor => (cursor.revokedAt.getEpochSecond, cursor.revokedAt.getNano, cursor.revokedKey))

/** @param last the last row read, decoded or not. A row this version has no case for still
  *             has to move the cursor past it, or every subsequent read starts on it again.
  *             `None` only when the page was empty.
  * @param hasMore whether the page filled the limit it was given, which is the only evidence
  *                available that another page exists.
  */
case class RevocationPage(revocations: List[Revocation], last: Option[RevocationCursor], hasMore: Boolean)

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

  /** One page of the revocations that still matter (`expires_at > now`), ordered so that a
    * reader can resume from where it stopped.
    *
    * Paged rather than whole because the size of the list is set by the rate revocations are
    * written at, not by anything this service bounds: an administrator ending a large number
    * of users' access puts all of it in the window at once. A page bounds what a single read
    * holds; resuming from a cursor bounds what a periodic read costs, which is the rows
    * written since the last one rather than the whole table.
    *
    * Nothing here reports deletions, and nothing needs to: an entry is dropped locally once
    * `expires_at` passes, so a replica never has to be told that a row it holds is gone.
    */
  def activeSince(cursor: RevocationCursor, limit: Int): Task[RevocationPage]
