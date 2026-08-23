package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.revocation.{Revocation, RevocationKey, RevocationRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZIO, ZLayer}

import java.time.Instant

/** @param revokedKey the encoded key rather than [[RevocationKey]]: a row written by a newer
  *                   version of this service can carry a prefix this one has no case for,
  *                   and skipping that row is better than failing the whole load.
  */
private case class RevocationRow(revokedKey: String, expiresAt: Instant, issuedBefore: Option[Instant]):
  def decoded: Option[Revocation] =
    RevocationKey.decode(revokedKey).map(Revocation(_, expiresAt, issuedBefore))

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

  override def listActive: Task[List[Revocation]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-active-revocations"):
        sql"""
          SELECT revoked_key, expires_at, issued_before
          FROM revocations
          WHERE expires_at > $now
        """
          .query[RevocationRow]
          .run()
          .toList
          .flatMap(_.decoded)

object PostgresRevocationRepository:
  def live: ZLayer[TransactorZIO, Nothing, RevocationRepository] =
    ZLayer.fromFunction(PostgresRevocationRepository(_))
