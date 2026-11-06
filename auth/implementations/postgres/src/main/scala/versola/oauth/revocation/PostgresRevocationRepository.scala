package versola.oauth.revocation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

import java.time.Instant

/** @param revokedKey the encoded key rather than [[RevocationKey]]: a row written by a newer
  *                   version of this service can carry a prefix this one has no case for, and
  *                   skipping that row is better than failing the whole load.
  */
private case class RevocationRow(
    revokedKey: String,
    revokedAt: Instant,
    expiresAt: Instant,
    issuedBefore: Option[Instant],
):
  def decoded: Option[Revocation] =
    RevocationKey.decode(revokedKey).map(Revocation(_, expiresAt, issuedBefore))

  def cursor: RevocationCursor = RevocationCursor(revokedAt, revokedKey)

class PostgresRevocationRepository(xa: TransactorZIO) extends RevocationRepository, BasicCodecs:

  private given DbCodec[RevocationRow] = DbCodec.derived

  override def revoke(revocation: Revocation, cause: RevocationCause): Task[Unit] =
    xa.transactMeasured("revoke-token"):
      PostgresRevocationRepository.upsert(revocation, cause)
    .unit

  /** The row comparison is what makes the cursor a position rather than an offset: it resumes on
    * the ordering itself, so rows written between two reads shift nothing and no row is counted
    * twice or skipped. `auth_revocations_revoked_at_key_idx` is what keeps it a range scan of the rows
    * being asked for instead of a sort of every unexpired one.
    */
  override def activeSince(cursor: RevocationCursor, limit: Int): Task[RevocationPage] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-active-revocations"):
        val rows = sql"""
          SELECT revoked_key, revoked_at, expires_at, issued_before
          FROM auth_revocations
          WHERE expires_at > $now
            AND (revoked_at, revoked_key) > (${cursor.revokedAt}, ${cursor.revokedKey})
          ORDER BY revoked_at, revoked_key
          LIMIT $limit
        """
          .query[RevocationRow]
          .run()
          .toList
        RevocationPage(
          revocations = rows.flatMap(_.decoded),
          last = rows.lastOption.map(_.cursor),
          hasMore = rows.sizeIs == limit,
        )

object PostgresRevocationRepository:
  def live: ZLayer[TransactorZIO, Nothing, RevocationRepository] =
    ZLayer.fromFunction(PostgresRevocationRepository(_))

  /** Writes a revocation on the caller's connection, so that a repository method which ends a
    * session or a refresh family records it in the same transaction as the ending: the two commit
    * together or not at all, and a revocation can never be missing for a session that ended.
    *
    * A key can be revoked more than once and the wider of the two governs: a second
    * administrative logout of the same user must not be swallowed as a duplicate of the first,
    * which would leave the tokens issued in between live.
    *
    * Widened on either field independently, because they do not move together. `expires_at` is
    * `revokedAt + the widest access token TTL`, and that TTL is whatever the writing replica's
    * client cache says at the time, so a second invalidation can carry a shorter one and produce
    * an earlier `expires_at` than the row already holds. Keying the guard on expiry alone would
    * then skip the write entirely, leaving `issued_before` where it was and never firing the
    * notification.
    *
    * `issued_before` is null exactly for the kinds that have no issuance bound (`jti:`, `fam:`,
    * `sid:`), and that is a property of the key rather than of the write, so the two sides of a
    * conflict always agree on whether it is set. `cause` follows the latest write that changed
    * the row.
    *
    * `revoked_at` comes from Postgres's own `NOW()` rather than the replica's `Clock`. It is
    * compared against other replicas' cursors, and a cursor only rewinds by a fixed overlap to
    * cover ordinary commit lag, not whatever a replica's own clock happens to be off by. The
    * database has one clock; every row taking its `revoked_at` from it is what makes the cursor
    * comparable across replicas at all.
    */
  def upsert(revocation: Revocation, cause: RevocationCause)(using DbCon): Int =
    sql"""
      INSERT INTO auth_revocations (revoked_key, revoked_at, expires_at, issued_before, cause)
      VALUES (${revocation.key.encoded}, NOW(), ${revocation.expiresAt}, ${revocation.issuedBefore}, ${cause.code})
      ON CONFLICT (revoked_key) DO UPDATE
      SET revoked_at    = EXCLUDED.revoked_at,
          expires_at    = GREATEST(auth_revocations.expires_at, EXCLUDED.expires_at),
          issued_before = GREATEST(auth_revocations.issued_before, EXCLUDED.issued_before),
          cause         = EXCLUDED.cause
      WHERE auth_revocations.expires_at < EXCLUDED.expires_at
         OR auth_revocations.issued_before < EXCLUDED.issued_before
    """.update.run()
