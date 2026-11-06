package versola.oauth.revocation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.model.UserId
import versola.util.revocation.{PostgresRevocationRepository, Revocation, RevocationKey, RevocationRepository, RevocationTable}
import zio.{Task, ZEnvironment, ZIO, ZLayer}

import java.time.Instant

class PostgresAuthRevocationRepository(xa: TransactorZIO)
  extends PostgresRevocationRepository(xa, PostgresAuthRevocationRepository.Table),
    AuthRevocationRepository:

  override def revoke(revocation: Revocation, cause: RevocationCause): Task[Unit] =
    xa.transactMeasured("revoke-token"):
      PostgresAuthRevocationRepository.upsert(revocation, cause)
    .unit

object PostgresAuthRevocationRepository:
  /** The table and channel of `V0032__revocations_table.sql`. */
  val Table: RevocationTable = RevocationTable(name = "auth_revocations", channel = "auth_revocation")

  def live: ZLayer[TransactorZIO, Nothing, AuthRevocationRepository & RevocationRepository] =
    ZLayer.fromZIOEnvironment:
      ZIO.serviceWith[TransactorZIO]: xa =>
        val repository = PostgresAuthRevocationRepository(xa)
        ZEnvironment[AuthRevocationRepository, RevocationRepository](repository, repository)

  /** `sub:<user>`, bounded at `now`: every token the user holds that was issued before this instant is refused,
    * and one issued after it, which the user obtained by signing in or refreshing again, is not. Written by
    * whatever changes the user in a way their old tokens no longer reflect, in the same transaction as the
    * change. */
  def upsertUser(userId: UserId, now: Instant, terms: RevocationTerms)(using DbCon): Int =
    upsert(
      Revocation(key = RevocationKey.Sub(userId.toString), expiresAt = now.plus(terms.ttl), issuedBefore = Some(now)),
      terms.cause,
    )

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
    * `issued_before` follows the cache's widest-wins rule: no bound covers every token the key
    * names, so it beats any instant, and between two instants the later one rejects more. Today
    * the key kind decides whether a bound is set, so the two sides of a conflict agree; the rule
    * is written out so that a writer of a kind that can be written both ways cannot narrow an
    * entry. `cause` follows the latest write that changed the row.
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
          issued_before = CASE
                            WHEN auth_revocations.issued_before IS NULL OR EXCLUDED.issued_before IS NULL THEN NULL
                            ELSE GREATEST(auth_revocations.issued_before, EXCLUDED.issued_before)
                          END,
          cause         = EXCLUDED.cause
      WHERE auth_revocations.expires_at < EXCLUDED.expires_at
         OR (auth_revocations.issued_before IS NOT NULL
             AND (EXCLUDED.issued_before IS NULL OR auth_revocations.issued_before < EXCLUDED.issued_before))
    """.update.run()
