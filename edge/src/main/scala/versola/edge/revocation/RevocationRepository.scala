package versola.edge.revocation

import zio.Task

trait RevocationRepository:
  /** Records revocations, ignoring keys already recorded. Every replica, including the one
    * writing, learns about them through [[RevocationNotifications]] rather than from the
    * return value.
    */
  def revokeAll(revocations: List[Revocation]): Task[Unit]

  /** The revocations that still matter (`expires_at > now`), newest first, capped at `limit`.
    * The cap is what makes the caller able to tell a complete picture from a truncated one.
    */
  def listActive(limit: Int): Task[List[Revocation]]

  /** The active entry under this key, if any. The entry rather than a yes/no, because
    * whether it applies to a given token can depend on when that token was issued.
    */
  def find(key: RevocationKey): Task[Option[Revocation]]
