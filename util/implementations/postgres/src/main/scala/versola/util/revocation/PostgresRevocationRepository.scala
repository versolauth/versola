package versola.util.revocation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.{BasicCodecs, TableName}
import zio.{Clock, Task}

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

/** The read side of a service's revocation table. The service's own repository extends it and adds
  * how that service writes, which is the part that differs between them.
  */
class PostgresRevocationRepository(xa: TransactorZIO, table: RevocationTable) extends RevocationRepository, BasicCodecs:

  private given DbCodec[RevocationRow] = DbCodec.derived

  private val relation = SqlLiteral(TableName.validated(table.name))

  /** The row comparison is what makes the cursor a position rather than an offset: it resumes on
    * the ordering itself, so rows written between two reads shift nothing and no row is counted
    * twice or skipped. The index on `(revoked_at, revoked_key)` is what keeps it a range scan of
    * the rows being asked for instead of a sort of every unexpired one.
    */
  override def activeSince(cursor: RevocationCursor, limit: Int): Task[RevocationPage] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-active-revocations"):
        val rows = sql"""
          SELECT revoked_key, revoked_at, expires_at, issued_before
          FROM $relation
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
