package versola.oauth.revocation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.user.model.UserId
import versola.util.postgres.TableName
import versola.util.revocation.{
  PostgresRevocationRepository,
  Revocation,
  RevocationCursor,
  RevocationKey,
  RevocationPage,
  RevocationRepository,
  RevocationTable,
}
import zio.{Clock, Task, ZEnvironment, ZIO, ZLayer}

import java.time.Instant

class PostgresAuthRevocationRepository(xa: TransactorZIO)
  extends PostgresRevocationRepository(xa, PostgresAuthRevocationRepository.Table),
    AuthRevocationRepository:

  override def revoke(revocation: Revocation, cause: RevocationCause, tenantId: Option[TenantId]): Task[Unit] =
    xa.transactMeasured("revoke-token"):
      PostgresAuthRevocationRepository.upsert(revocation, cause, tenantId)
    .unit

  private given DbCodec[FeedRow] = DbCodec.derived

  private val relation = SqlLiteral(TableName.validated(PostgresAuthRevocationRepository.Table.name))

  /** The tenant filter is an `ANY` over a text array bound as one parameter, so a long tenant list is
    * still one statement of fixed shape. `tenant_id IS NULL` is what the column's `NULL` means: not
    * limited to a tenant, served to every edge.
    */
  override def feedSince(cursor: RevocationCursor, limit: Int, tenantIds: Set[TenantId]): Task[RevocationPage] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("list-revocation-feed"):
        val tenants: Set[String] = tenantIds.map(identity[String])
        val rows = sql"""
          SELECT revoked_key, revoked_at, expires_at, issued_before
          FROM $relation
          WHERE expires_at > $now
            AND (revoked_at, revoked_key) > (${cursor.revokedAt}, ${cursor.revokedKey})
            AND (tenant_id IS NULL OR tenant_id = ANY($tenants))
          ORDER BY revoked_at, revoked_key
          LIMIT $limit
        """
          .query[FeedRow]
          .run()
          .toList
        RevocationPage(
          revocations = rows.flatMap(_.decoded),
          last = rows.lastOption.map(_.cursor),
          hasMore = rows.sizeIs == limit,
        )

/** A row as the feed reads it: the encoded key, not [[RevocationKey]], so that a row of a kind this
  * version has no case for moves the cursor and is skipped instead of failing the page.
  */
private case class FeedRow(revokedKey: String, revokedAt: Instant, expiresAt: Instant, issuedBefore: Option[Instant]):
  def decoded: Option[Revocation] =
    RevocationKey.decode(revokedKey).map(Revocation(_, expiresAt, issuedBefore))

  def cursor: RevocationCursor = RevocationCursor(revokedAt, revokedKey)

object PostgresAuthRevocationRepository:
  /** The table and channel of `V0032__revocations_table.sql`. */
  val Table: RevocationTable = RevocationTable(name = "auth_revocations", channel = "auth_revocation")

  def live: ZLayer[TransactorZIO, Nothing, AuthRevocationRepository & RevocationRepository] =
    ZLayer.fromZIOEnvironment:
      ZIO.serviceWith[TransactorZIO]: xa =>
        val repository = PostgresAuthRevocationRepository(xa)
        ZEnvironment[AuthRevocationRepository, RevocationRepository](repository, repository)

  /** `sub:<user>`, for a change made at `now`: every token the user holds that was issued in an earlier second than
    * `now` is refused, and one issued in the same second or later, which the user obtained by signing in or
    * refreshing right after the change, is not. Written by whatever changes the user in a way their old tokens no
    * longer reflect, in the same transaction as the change.
    *
    * `issued_before` is [[Revocation.userBound]] of `now`, the last second the change revokes, not `now`. Every
    * reader (auth's and edge's cache, the pull feed's `before`, older edge versions) revokes a token whose `iat`
    * is not after the stored value, and `iat` is whole seconds: storing `now` itself, with its microseconds,
    * refused a token minted at 10:00:00.7 for a change at 10:00:00.2 (`iat` 10:00:00), so a user who signed in
    * right after an administrator set their password got a 401 at `/userinfo`, and so did a refresh right after a
    * role removal. The cost is the mirror image: a token minted in the same second but just before the change
    * keeps working until it expires, because `iat` cannot tell it from one minted just after.
    *
    * Calling it again widens the entry (a later change gives a later bound, and [[upsert]] keeps the greater of
    * the two) and an earlier `now` never narrows it.
    */
  def upsertUser(userId: UserId, now: Instant, terms: RevocationTerms)(using DbCon): Int =
    upsert(
      Revocation(
        key = RevocationKey.Sub(userId.toString),
        expiresAt = now.plus(terms.ttl),
        issuedBefore = Some(Revocation.userBound(now)),
      ),
      terms.cause,
      terms.tenantId,
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
  def upsert(revocation: Revocation, cause: RevocationCause, tenantId: Option[TenantId])(using DbCon): Int =
    upsertStatement(revocation, cause, tenantId).run()

  /** [[upsert]] for several keys of one cause at once, as one batch on the caller's connection: a method that ends
    * several families or sessions in one transaction writes their records in one round trip rather than one each.
    */
  def upsertAll(revocations: List[Revocation], cause: RevocationCause, tenantId: Option[TenantId])(using DbCon): Unit =
    if revocations.nonEmpty then
      batchUpdate(revocations)(upsertStatement(_, cause, tenantId))
      ()

  private def upsertStatement(revocation: Revocation, cause: RevocationCause, tenantId: Option[TenantId]): Update =
    val tenant: Option[String] = tenantId.map(identity[String])
    sql"""
      INSERT INTO auth_revocations (revoked_key, revoked_at, expires_at, issued_before, cause, tenant_id)
      VALUES (${revocation.key.encoded}, NOW(), ${revocation.expiresAt}, ${revocation.issuedBefore}, ${cause.code}, $tenant)
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
    """.update
