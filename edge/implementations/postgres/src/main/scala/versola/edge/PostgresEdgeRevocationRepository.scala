package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.revocation.EdgeRevocationRepository
import versola.util.revocation.{PostgresRevocationRepository, Revocation, RevocationRepository, RevocationTable}
import zio.{Task, ZEnvironment, ZIO, ZLayer}

class PostgresEdgeRevocationRepository(xa: TransactorZIO)
  extends PostgresRevocationRepository(xa, PostgresEdgeRevocationRepository.Table),
    EdgeRevocationRepository:

  /** A key can be revoked more than once and the wider of the two governs: a second
    * administrative logout of the same user must not be swallowed as a duplicate of the
    * first, which would leave the tokens issued in between live.
    *
    * Widened on either field independently, because they do not move together. `expires_at`
    * is `revokedAt + widestAccessTokenTtl`, and that TTL is whatever the writing replica's
    * client cache says at the time — so a second invalidation can carry a shorter one (the
    * longest-lived client was reconfigured, or that replica has yet to sync it) and produce
    * an earlier `expires_at` than the row already holds. Keying the guard on expiry alone
    * would then skip the write entirely, leaving `issued_before` where it was and never
    * firing the notification, which is exactly the case the paragraph above says must not be
    * swallowed.
    *
    * `issued_before` is null exactly for the kinds that have no issuance bound (`jti:`,
    * `fam:`, `sid:`), and that is a property of the key rather than of the write, so the two
    * sides of a conflict always agree on whether it is set.
    *
    * `revoked_at` comes from Postgres's own `NOW()` rather than this replica's `Clock`. It
    * is compared, elsewhere, against other replicas' cursors, and a cursor only rewinds by a
    * fixed overlap to cover ordinary commit lag — not whatever a replica's own clock happens
    * to be off by. Stamping it from the writing replica would let a lagging clock write a row
    * that lands behind every cursor by more than the overlap covers, permanently out of
    * reach of the catch-up that exists to find it if its notification is ever missed. The
    * database has one clock; every row taking its `revoked_at` from it is what makes the
    * cursor comparable across replicas at all.
    */
  override def revokeAll(revocations: List[Revocation]): Task[Unit] =
    if revocations.isEmpty then ZIO.unit
    else
      xa.transactMeasured("revoke-tokens"):
        batchUpdate(revocations): revocation =>
          sql"""
            INSERT INTO revocations (revoked_key, revoked_at, expires_at, issued_before)
            VALUES (${revocation.key.encoded}, NOW(), ${revocation.expiresAt}, ${revocation.issuedBefore})
            ON CONFLICT (revoked_key) DO UPDATE
            SET revoked_at    = EXCLUDED.revoked_at,
                expires_at    = GREATEST(revocations.expires_at, EXCLUDED.expires_at),
                issued_before = GREATEST(revocations.issued_before, EXCLUDED.issued_before)
            WHERE revocations.expires_at < EXCLUDED.expires_at
               OR revocations.issued_before < EXCLUDED.issued_before
          """.update

object PostgresEdgeRevocationRepository:
  /** The table and channel of `V2003__revocations_table.sql`. */
  val Table: RevocationTable = RevocationTable(name = "revocations", channel = "revocation")

  def live: ZLayer[TransactorZIO, Nothing, EdgeRevocationRepository & RevocationRepository] =
    ZLayer.fromZIOEnvironment:
      ZIO.serviceWith[TransactorZIO]: xa =>
        val repository = PostgresEdgeRevocationRepository(xa)
        ZEnvironment[EdgeRevocationRepository, RevocationRepository](repository, repository)
