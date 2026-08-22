package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.revocation.{Revocation, RevocationKey, RevocationRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZIO, ZLayer}

import java.time.Instant

class PostgresRevocationRepository(xa: TransactorZIO) extends RevocationRepository, BasicCodecs:

  override def revokeAll(revocations: List[Revocation]): Task[Unit] =
    if revocations.isEmpty then ZIO.unit
    else
      xa.transactMeasured("revoke-tokens"):
        revocations.foreach: revocation =>
          sql"""
            INSERT INTO revocations (revoked_key, expires_at, issued_before)
            VALUES (${revocation.key.encoded}, ${revocation.expiresAt}, ${revocation.issuedBefore})
            ON CONFLICT (revoked_key) DO UPDATE
            SET expires_at    = EXCLUDED.expires_at,
                issued_before = EXCLUDED.issued_before,
                revoked_at    = NOW()
            -- A key can be revoked more than once and the later one governs: a second
            -- administrative logout of the same user must not be swallowed as a duplicate
            -- of the first, which would leave the tokens of the session in between live.
            WHERE revocations.expires_at < EXCLUDED.expires_at
          """.update.run()

  override def listActive(limit: Int): Task[List[Revocation]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-active-revocations"):
        sql"""
          SELECT revoked_key, expires_at, issued_before
          FROM revocations
          WHERE expires_at > $now
          ORDER BY revoked_at DESC
          LIMIT $limit
        """
          .query[(String, Instant, Option[Instant])]
          .run()
          .toList
          // A key written by a newer version of this service, with a prefix this one does
          // not understand, is skipped rather than failing the whole load.
          .flatMap((key, expiresAt, issuedBefore) =>
            RevocationKey.decode(key).map(Revocation(_, expiresAt, issuedBefore)),
          )

  override def find(key: RevocationKey): Task[Option[Revocation]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-revocation"):
        sql"""
          SELECT expires_at, issued_before
          FROM revocations
          WHERE revoked_key = ${key.encoded} AND expires_at > $now
        """
          .query[(Instant, Option[Instant])]
          .run()
          .headOption
          .map((expiresAt, issuedBefore) => Revocation(key, expiresAt, issuedBefore))

object PostgresRevocationRepository:
  def live: ZLayer[TransactorZIO, Nothing, RevocationRepository] =
    ZLayer.fromFunction(PostgresRevocationRepository(_))
