package versola.util.revocation

import zio.{Duration, Task}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import scala.util.Try

/** How far through the list a replica has read.
  *
  * `revoked_at` alone would not do: two revocations written in the same instant would make the
  * position ambiguous, and a page boundary falling between them would skip one. The key breaks the
  * tie, which is why rows are ordered and resumed by the pair.
  */
case class RevocationCursor(revokedAt: Instant, revokedKey: String):
  /** The form the feed carries a position in (`GET /revocations`): `<revoked_at>|<revoked_key>`, base64url, and the
    * empty string for [[RevocationCursor.Beginning]]. It is what an edge stores between polls, and what it has to read
    * back to start a poll a little behind where the last one ended (see [[rewound]]).
    */
  def encoded: String =
    if this == RevocationCursor.Beginning then ""
    else Base64.getUrlEncoder.withoutPadding.encodeToString(s"$revokedAt|$revokedKey".getBytes(StandardCharsets.UTF_8))

  /** Starts the read a little behind this position, for a reader that rereads a window to catch what committed late.
    * `revoked_at` is stamped when a row is written but the row only appears when its transaction commits, so one that took
    * longer to commit than its neighbours can land behind a cursor that has already passed it. The key is dropped, so the
    * whole second of the new position is read.
    */
  def rewound(by: Duration): RevocationCursor =
    if this == RevocationCursor.Beginning then this
    else RevocationCursor(revokedAt.minusSeconds(by.toSeconds), "")

object RevocationCursor:
  /** `None` for a value [[encoded]] did not produce. */
  def decode(encoded: String): Option[RevocationCursor] =
    if encoded.isEmpty then Some(Beginning)
    else
      Try:
        val decoded = String(Base64.getUrlDecoder.decode(encoded), StandardCharsets.UTF_8)
        val separator = decoded.indexOf('|')
        RevocationCursor(Instant.parse(decoded.take(separator)), decoded.drop(separator + 1))
      .toOption

  /** Before any row that exists, since `revoked_at` is the instant of a write. Reading from here is
    * what loads the whole list, which is what a replica does once, at startup.
    */
  val Beginning: RevocationCursor = RevocationCursor(Instant.EPOCH, "")

  given Ordering[RevocationCursor] =
    Ordering.by(cursor => (cursor.revokedAt.getEpochSecond, cursor.revokedAt.getNano, cursor.revokedKey))

/** @param last the last row read, decoded or not. A row this version has no case for still has to
  *             move the cursor past it, or every subsequent read starts on it again. `None` only
  *             when the page was empty.
  * @param hasMore whether the page filled the limit it was given, which is the only evidence
  *                available that another page exists.
  */
case class RevocationPage(revocations: List[Revocation], last: Option[RevocationCursor], hasMore: Boolean)

/** The read side of the durable copy of the revocation list. Never read while a request is in
  * flight: it is what a replica catches up from when it starts or when it has just missed
  * notifications, and what an entry survives a restart in.
  *
  * Writing is not part of it: each service writes its own table in its own way (auth in the
  * transaction that ends a session or a refresh family, edge from the events auth sends it), and
  * both rely on the same upsert rule, which widens a key already recorded rather than skipping it,
  * and never narrows either field. Every replica, including the one writing, learns about a write
  * through [[RevocationNotifications]] rather than from the return value of the write.
  */
trait RevocationRepository:
  /** One page of the revocations that still matter (`expires_at > now`), ordered so that a reader
    * can resume from where it stopped.
    *
    * Paged rather than whole because the size of the list is set by the rate revocations are
    * written at, not by anything bounded here: an administrator ending a large number of users'
    * access puts all of it in the window at once. A page bounds what a single read holds; resuming
    * from a cursor bounds what a periodic read costs, which is the rows written since the last one
    * rather than the whole table.
    *
    * Nothing here reports deletions, and nothing needs to: an entry is dropped locally once
    * `expires_at` passes, so a replica never has to be told that a row it holds is gone.
    */
  def activeSince(cursor: RevocationCursor, limit: Int): Task[RevocationPage]
