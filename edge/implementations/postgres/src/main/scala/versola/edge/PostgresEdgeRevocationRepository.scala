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
    * `issued_before` follows the cache's widest-wins rule: no bound covers every token the key
    * names, so it beats any instant, and between two instants the later one rejects more. Plain
    * `GREATEST` ignores a null and would narrow an unbounded entry to the bound of a later write
    * of the same key; the rule is written out so that a key written both ways cannot be narrowed
    * whichever order the two writes arrive in. Today the key kind decides whether a bound is set,
    * so the two sides of a conflict agree and nothing changes in practice.
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
                issued_before = CASE
                                  WHEN revocations.issued_before IS NULL OR EXCLUDED.issued_before IS NULL THEN NULL
                                  ELSE GREATEST(revocations.issued_before, EXCLUDED.issued_before)
                                END
            WHERE revocations.expires_at < EXCLUDED.expires_at
               OR (revocations.issued_before IS NOT NULL
                   AND (EXCLUDED.issued_before IS NULL OR revocations.issued_before < EXCLUDED.issued_before))
          """.update

object PostgresEdgeRevocationRepository:
  /** The table and channel of `V2003__revocations_table.sql`. */
  val Table: RevocationTable = RevocationTable(name = "revocations", channel = "revocation")

  def live: ZLayer[TransactorZIO, Nothing, EdgeRevocationRepository & RevocationRepository] =
    ZLayer.fromZIOEnvironment:
      ZIO.serviceWith[TransactorZIO]: xa =>
        val repository = PostgresEdgeRevocationRepository(xa)
        ZEnvironment[EdgeRevocationRepository, RevocationRepository](repository, repository)
