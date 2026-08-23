package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.revocation.{Revocation, RevocationCursor, RevocationKey, RevocationPage, RevocationRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZIO, ZLayer}

import java.time.Instant

/** @param revokedKey the encoded key rather than [[RevocationKey]]: a row written by a newer
  *                   version of this service can carry a prefix this one has no case for,
  *                   and skipping that row is better than failing the whole load.
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

  /** A key can be revoked more than once and the later one governs: a second administrative
    * logout of the same user must not be swallowed as a duplicate of the first, which would
    * leave the tokens issued in between live.
    */
  override def revokeAll(revocations: List[Revocation]): Task[Unit] =
    if revocations.isEmpty then ZIO.unit
    else
      Clock.instant.flatMap: now =>
        xa.transactMeasured("revoke-tokens"):
          batchUpdate(revocations): revocation =>
            sql"""
              INSERT INTO revocations (revoked_key, revoked_at, expires_at, issued_before)
              VALUES (${revocation.key.encoded}, $now, ${revocation.expiresAt}, ${revocation.issuedBefore})
              ON CONFLICT (revoked_key) DO UPDATE
              SET revoked_at    = EXCLUDED.revoked_at,
                  expires_at    = EXCLUDED.expires_at,
                  issued_before = EXCLUDED.issued_before
              WHERE revocations.expires_at < EXCLUDED.expires_at
            """.update

  /** The row comparison is what makes the cursor a position rather than an offset: it resumes
    * on the ordering itself, so rows written between two reads shift nothing and no row is
    * counted twice or skipped. `revocations_revoked_at_key_idx` is what keeps it a range scan
    * of the rows being asked for instead of a sort of every unexpired one.
    */
  override def activeSince(cursor: RevocationCursor, limit: Int): Task[RevocationPage] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-active-revocations"):
        val rows = sql"""
          SELECT revoked_key, revoked_at, expires_at, issued_before
          FROM revocations
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
