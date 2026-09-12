package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, PassedAuthFactor, PassedFactorRecord, ResourceUri, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.session.model.{ClientEntry, PriorSession, PublicSessionId, RefreshAlreadyExchanged, RefreshTokenRecord, RevokedFamily, SessionId, SessionRecord, UserAgentId}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Clock, Duration, IO, Task, ZIO, ZLayer}

import java.sql.{Connection, SQLException}
import java.time.Instant
import java.util.UUID

class PostgresSessionRepository(xa: TransactorZIO)
    extends SessionRepository, BasicCodecs:

  import PgCodec.ListCodec
  import SqlArrayCodec.ListSqlArrayCodec

  // ── shared codecs ─────────────────────────────────────────────────────────
  given DbCodec[MAC]      = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UserId]   = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])

  // ── session codecs ────────────────────────────────────────────────────────
  given DbCodec[PublicSessionId] = DbCodec.StringCodec.biMap(PublicSessionId(_), identity[String])
  given DbCodec[UserAgentId] = DbCodec.UUIDCodec.biMap(UserAgentId(_), identity[UUID])
  given amrSessionCodec: DbCodec[Map[PassedAuthFactor, PassedFactorRecord]] =
    jsonBCodec[Map[PassedAuthFactor, PassedFactorRecord]]
  given clientEntriesDbCodec: DbCodec[List[ClientEntry]] = jsonBCodec[List[ClientEntry]]
  given DbCodec[SessionRecord] = DbCodec.derived[SessionRecord]

  // ── refresh-token codecs ──────────────────────────────────────────────────
  given DbCodec[AccessToken]                   = DbCodec.ByteArrayCodec.biMap(AccessToken(_), identity[Array[Byte]])
  given SqlArrayCodec[ClientId]                = SqlArrayCodec.StringSqlArrayCodec.asInstanceOf[SqlArrayCodec[ClientId]]
  given DbCodec[ScopeToken]                    = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given listStringDbCodec: DbCodec[List[String]]     = PgCodec.SeqCodec[String].biMap(_.toList, _.toSeq)
  given listClientIdDbCodec: DbCodec[List[ClientId]] =
    PgCodec.SeqCodec[String].biMap(_.map(ClientId(_)).toList, _.map(identity[String]))
  given listResourceUriDbCodec: DbCodec[List[ResourceUri]] =
    PgCodec.SeqCodec[String].biMap(_.map(ResourceUri(_)).toList, _.map(identity[String]))
  given DbCodec[Nonce]                         = DbCodec.StringCodec.biMap(Nonce(_), identity[String])
  given DbCodec[RequestedClaims]               = jsonCodec[RequestedClaims]
  given DbCodec[Set[AuthMethodRef]]            = jsonBCodec[Set[AuthMethodRef]]
  given DbCodec[Acr]                           = DbCodec.StringCodec.biMap(Acr(_), identity[String])
  given JsonBDbCodec[AuthorizationDetail]      = jsonBCodec
  // The column is a nullable array; the model's `Option[List[...]]` maps onto it directly via
  // the generic `DbCodec.OptionCodec` (NULL <-> None) wrapping this element codec.
  given listAuthorizationDetailDbCodec: DbCodec[List[AuthorizationDetail]] =
    PgCodec.SeqCodec[AuthorizationDetail].biMap(_.toList, _.toSeq)
  given DbCodec[RefreshTokenRecord]            = DbCodec.derived[RefreshTokenRecord]

  // ── SessionRepository ─────────────────────────────────────────────────────

  override def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
      priorSession: Option[PriorSession],
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      val idleExpiresAt = idleTtl.map(t => now.plusSeconds(t.toSeconds))
      val priorId = priorSession.map(_.id)
      xa.transactMeasured("create-session"):
        // The new session continues the same browser session as the prior one (step-up,
        // idle-slide re-issue): carry over the RPs already registered on it so none of them
        // miss a later logout notification because of the rotation.
        val priorClients = priorId.toList.flatMap: prior =>
          sql"""SELECT clients FROM sso_sessions WHERE id = $prior""".query[List[ClientEntry]].run().headOption.getOrElse(Nil)
        val clients = (session.clients ++ priorClients).distinctBy(_.clientId)
        sql"""
          INSERT INTO sso_sessions (id, public_id, clients, user_id, user_agent_id, created_at, amr, expires_at, idle_expires_at)
          VALUES (
            $id,
            ${session.publicId},
            $clients,
            ${session.userId},
            ${session.userAgentId},
            ${session.createdAt},
            ${session.amr},
            ${now.plusSeconds(ttl.toSeconds)},
            $idleExpiresAt
          )
        """.update.run()
        priorSession.foreach:
          case PriorSession.Invalidate(prior) =>
            // Single data-modifying CTE: expire the prior session, then expire only the
            // refresh tokens that belonged to it (RETURNING short-circuits the second
            // statement when the prior session was already expired).
            sql"""
              WITH expired_prior AS (
                UPDATE sso_sessions SET expires_at = $now WHERE id = $prior AND expires_at > $now RETURNING id
              )
              UPDATE refresh_tokens SET expires_at = $now WHERE session_id IN (SELECT id FROM expired_prior)
            """.update.run()
          case PriorSession.MigrateTokens(prior, amr, authTime, acr) =>
            sql"""
              WITH expired_prior AS (
                UPDATE sso_sessions SET expires_at = $now WHERE id = $prior AND expires_at > $now RETURNING id
              )
              UPDATE refresh_tokens
              SET session_id = $id, amr = $amr, auth_time = $authTime, acr = $acr
              WHERE session_id IN (SELECT id FROM expired_prior) AND expires_at > $now
            """.update.run()
        ()

  override def findSession(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-session"):
        sql"""
          SELECT user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
          FROM sso_sessions
          WHERE id = $id
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
        """.query[SessionRecord].run().headOption

  override def registerClient(id: MAC.Of[SessionId], clientId: ClientId): Task[Unit] =
    Clock.instant.flatMap: now =>
      val newEntry = List(ClientEntry(clientId, now))
      xa.connectMeasured("register-session-client"):
        sql"""
          UPDATE sso_sessions
          SET clients = clients || $newEntry::jsonb
          WHERE id = $id AND NOT (clients @> jsonb_build_array(jsonb_build_object('clientId', $clientId)))
        """.update.run()
      .unit

  override def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("prolong-idle"):
        sql"""
          UPDATE sso_sessions
          SET idle_expires_at = ${now.plusSeconds(idleTtl.toSeconds)}
          WHERE id = $id AND idle_expires_at IS NOT NULL
        """.update.run()
      .unit

  override def findByUserId(userId: UserId): Task[List[SessionRecord]] =
    for
      now    <- Clock.instant
      result <- xa.connectMeasured("find-sessions-by-user"):
        sql"""
          SELECT user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
          FROM sso_sessions
          WHERE
            user_id = $userId
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
          ORDER BY created_at DESC
        """.query[SessionRecord].run().toList
    yield result

  /** Atomically expires all active sessions and refresh tokens for the given user.
    *
    * Reaches refresh_tokens by user_id directly rather than through the session ids just
    * expired above: a refresh token's expiry slides forward on every use while a session's
    * does not, so a token can still be live long after its session expired on its own, and
    * force-logout has to revoke those too, not only the ones under a still-active session.
    */
  override def invalidateByUserId(userId: UserId): Task[List[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-sessions-by-user"):
        sql"""
          WITH expired AS (
            UPDATE sso_sessions
            SET expires_at = $now
            WHERE user_id = $userId AND expires_at > $now
            RETURNING user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
          ),
          revoked_tokens AS (
            UPDATE refresh_tokens SET expires_at = $now
            WHERE user_id = $userId AND expires_at > $now
          )
          SELECT user_id, clients, user_agent_id, created_at, amr, public_id, expires_at FROM expired
        """.query[SessionRecord].run().toList

  override def findRefreshTokensByUserId(userId: UserId): Task[List[RefreshTokenRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-refresh-tokens-by-user"):
        sql"""
          SELECT session_id, public_session_id, access_token, access_token_expires_at,
                 user_id, client_id,
                 audience, authorization_details, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce,
                 amr, auth_time, acr, cnf_jkt
          FROM refresh_tokens
          WHERE user_id = $userId AND expires_at > $now AND rotated_at IS NULL
          ORDER BY issued_at DESC
        """.query[RefreshTokenRecord].run().toList

  override def invalidate(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-session"):
        val session = sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE id = $id
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
          RETURNING user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
        """.query[SessionRecord].run().headOption
        sql"""
          UPDATE refresh_tokens SET expires_at = $now WHERE session_id = $id
        """.update.run()
        session

  override def invalidateByPublicId(publicId: PublicSessionId): Task[Option[(MAC.Of[SessionId], SessionRecord)]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-session-by-public-id"):
        val session = sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE public_id = $publicId
          RETURNING id, user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
        """.query[(MAC, SessionRecord)].run().headOption
        session.foreach { case (id, _) =>
          sql"""UPDATE refresh_tokens SET expires_at = $now WHERE session_id = $id""".update.run()
        }
        session

  override def invalidateByPublicIdForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-session-by-public-id-for-user"):
        val sessionId = sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE public_id = $publicId AND user_id = $userId
          RETURNING id
        """.query[MAC].run().headOption
        sessionId.foreach: id =>
          sql"""UPDATE refresh_tokens SET expires_at = $now WHERE session_id = $id""".update.run()
        sessionId.isDefined

  // ── refresh token methods ─────────────────────────────────────────────────

  /** Serialises every rotation and revocation of the family `token` belongs to, for the rest of
    * the transaction.
    *
    * An advisory lock rather than a row lock on the family's live tip -- the one row both
    * operations have to go through -- because `WHERE rotated_at IS NULL FOR UPDATE` cannot lock
    * that row reliably. At READ COMMITTED a rotation committing while the lock is waited for
    * makes the row stop matching, and Postgres then skips it: the waiter proceeds having locked
    * nothing, and its family-wide update can run beside a later rotation whose successor its
    * snapshot never sees. A lock that names a key instead of a row has nothing to lose that way.
    *
    * Transaction-level, so it is released on commit or rollback and is safe behind a connection
    * pooler in transaction mode -- unlike session-level `pg_advisory_lock`, whose lifetime would
    * outlive the transaction and so leak across a pooler's server-connection reuse.
    *
    * An unknown token leaves the second key NULL, which takes no lock. Nothing is lost: the
    * caller's own statements find no row either way.
    */
  private def lockFamily(token: MAC.Of[RefreshToken])(using DbCon): Unit =
    sql"""
      SELECT 1 FROM pg_advisory_xact_lock(
        ${PostgresSessionRepository.RefreshTokenFamilyLockNamespace},
        (SELECT hashtext(encode(family_id, 'hex')) FROM refresh_tokens WHERE id = $token)
      )
    """.query[Int].run()
    ()

  override def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      previous: Option[MAC.Of[RefreshToken]],
      record: RefreshTokenRecord,
      idempotencyKey: Option[MAC],
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("create-refresh-token") {
        previous match
          case None =>
            // A fresh chain: the token is the root of its own family.
            sql"""
              INSERT INTO refresh_tokens (
                id, family_id, session_id, public_session_id, access_token,
                access_token_expires_at, user_id, client_id,
                audience, authorization_details, scope, issued_at, expires_at, requested_claims,
                ui_locales, nonce, amr, auth_time, acr, cnf_jkt
              )
              VALUES (
                $refreshToken,
                $refreshToken,
                ${record.sessionId},
                ${record.publicSessionId},
                ${record.accessToken},
                ${record.accessTokenExpiresAt},
                ${record.userId},
                ${record.clientId},
                ${record.audience},
                ${record.authorizationDetails},
                ${record.scope},
                ${record.issuedAt},
                ${record.expiresAt},
                ${record.requestedClaims},
                ${record.uiLocales}::text[],
                ${record.nonce},
                ${record.amr},
                ${record.authTime},
                ${record.acr},
                ${record.cnfJkt}
              )
            """.update.run()

          case Some(previousToken) =>
            lockFamily(previousToken)

            // Retire the predecessor, move the idempotency key onto it, and insert the
            // successor -- one statement, one round trip.
            //
            // `retired`'s row lock is what orders two rotations of the same token against each
            // other: the second blocks there and then matches no row. Ordering a rotation
            // against a revocation is the family lock's job (see `lockFamily`), not this row's.
            // Nothing here has to be kept alive to be lockable -- which is why the root needs
            // no expiry bump and is retained on the same terms as any other retired generation.
            //
            // `expires_at` on the retired row is not "when this token stops working" --
            // rotated_at already means that -- it is how long the row survives the cleanup
            // sweep so a replay of this generation still resolves to its family.
            val inserted = sql"""
              WITH retired AS (
                UPDATE refresh_tokens
                SET rotated_at = $now,
                    expires_at = ${now.plus(PostgresSessionRepository.ReplayDetectionWindow)},
                    idempotency_key = $idempotencyKey
                WHERE id = $previousToken AND rotated_at IS NULL AND expires_at > $now
                RETURNING family_id
              ),
              cleared AS (
                -- A key names the family's latest exchange and no other, so recognising it is
                -- the same as asking whether the chain has moved on. Clearing it from wherever
                -- it sat before is what lets a client retry more than once: each retry carries
                -- the key forward, while any exchange under a different key -- the client
                -- finally getting through -- strands the old one and takes reuse detection
                -- back. Disjoint from `retired`'s target row by construction.
                UPDATE refresh_tokens
                SET idempotency_key = NULL
                WHERE family_id = (SELECT family_id FROM retired)
                  AND id <> $previousToken
                  AND idempotency_key IS NOT NULL
              )
              INSERT INTO refresh_tokens (
                id, family_id, session_id, public_session_id, access_token,
                access_token_expires_at, user_id, client_id,
                audience, authorization_details, scope, issued_at, expires_at, requested_claims,
                ui_locales, nonce, amr, auth_time, acr, cnf_jkt
              )
              SELECT
                $refreshToken,
                retired.family_id,
                ${record.sessionId},
                ${record.publicSessionId},
                ${record.accessToken},
                ${record.accessTokenExpiresAt},
                ${record.userId},
                ${record.clientId},
                ${record.audience},
                ${record.authorizationDetails},
                ${record.scope},
                ${record.issuedAt},
                ${record.expiresAt},
                ${record.requestedClaims},
                ${record.uiLocales}::text[],
                ${record.nonce},
                ${record.amr},
                ${record.authTime},
                ${record.acr},
                ${record.cnfJkt}
              FROM retired
            """.update.run()

            // Nothing retired means nothing inserted: the token was already exchanged, either
            // earlier or by a concurrent request that just released the row lock.
            if inserted == 0 then throw PostgresSessionRepository.RotationLost
        ()
      }.catchSome {
        case PostgresSessionRepository.RotationLost =>
          ZIO.fail(RefreshAlreadyExchanged())
        case e if PostgresSessionRepository.isSerializationOrUniqueViolationFailure(e) =>
          ZIO.fail(RefreshAlreadyExchanged())
      }

  override def findToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]] =
    for
      now    <- Clock.instant
      result <- xa.connectMeasured("find-refresh-token"):
        sql"""
          SELECT session_id, public_session_id, access_token, access_token_expires_at,
                 user_id, client_id,
                 audience, authorization_details, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce,
                 amr, auth_time, acr, cnf_jkt
          FROM refresh_tokens
          WHERE id = $token AND expires_at > $now AND rotated_at IS NULL"""
          .query[RefreshTokenRecord]
          .run()
          .headOption
    yield result

  override def findIdempotentRetry(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
      idempotencyKey: MAC,
  ): Task[Option[(MAC.Of[RefreshToken], RefreshTokenRecord)]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-idempotent-refresh-retry"):
        // `exchanged` is the row the key names: the family's latest exchange, since a key is
        // only ever on one. Requiring the presented token to share its family stops a leaked
        // key from being usable on its own, and scoping to the client stops one client from
        // reaching into another's chain.
        //
        // `presented` and `exchanged` are both gated on `expires_at > now`, unlike the family
        // lookup in `revokeFamily`: this query *grants* the caller the live tip, so how long
        // that grant remains available has to be the recorded `ReplayDetectionWindow`, not
        // whatever the cleanup sweep's cadence happens to be. A row past its `expires_at` is
        // physically present until swept, but must stop being honoured now.
        sql"""
          SELECT tip.id, tip.session_id, tip.public_session_id, tip.access_token,
                 tip.access_token_expires_at, tip.user_id,
                 tip.client_id, tip.audience, tip.authorization_details, tip.scope,
                 tip.issued_at, tip.expires_at, tip.requested_claims, tip.ui_locales,
                 tip.nonce, tip.amr, tip.auth_time, tip.acr, tip.cnf_jkt
          FROM refresh_tokens presented
          JOIN refresh_tokens exchanged
            ON exchanged.family_id = presented.family_id
           AND exchanged.idempotency_key = $idempotencyKey
           AND exchanged.rotated_at IS NOT NULL
           AND exchanged.expires_at > $now
          JOIN refresh_tokens tip
            ON tip.family_id = presented.family_id
           AND tip.rotated_at IS NULL
           AND tip.expires_at > $now
          WHERE presented.id = $token AND presented.client_id = $clientId
            AND presented.expires_at > $now
        """
          .query[(MAC.Of[RefreshToken], RefreshTokenRecord)]
          .run()
          .headOption

  override def revokeFamily(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
  ): Task[Option[RevokedFamily]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("revoke-refresh-token-family") {
        // Taken before the family is even read, so everything below runs on a snapshot no
        // rotation of this family can still be about to change: one in flight either commits
        // first -- and has its freshly inserted successor expired by the update below -- or
        // waits, and then finds the token it meant to rotate already dead.
        lockFamily(token)

        sql"""
          SELECT family_id, user_id
          FROM refresh_tokens
          WHERE id = $token AND client_id = $clientId AND rotated_at IS NOT NULL
        """.query[(MAC.Of[RefreshToken], UserId)]
          .run()
          .headOption
          .map: (family, userId) =>
            val revoked = sql"""
              UPDATE refresh_tokens
              SET expires_at = $now
              WHERE family_id = $family AND expires_at > $now
              RETURNING access_token, access_token_expires_at
            """.query[(AccessToken, Instant)].run()

            // Each row's own access-token expiry, not the client's current accessTokenTtl
            // (mutable, so it would misjudge a token minted under a different one): a token
            // already past it is dead already, and pushing it to the client's back channel
            // would revoke nothing.
            val live = revoked.view.filter(_._2.isAfter(now)).toList

            RevokedFamily(
              userId = userId,
              accessTokens = live.map(_._1),
              // The whole batch is pushed as one edge event under a single expiresAt (see the
              // caller), so that bound has to cover the furthest of them, not any one token's.
              accessTokensExpireBy = live.map(_._2).maxOption,
            )
      }

  override def renewBoundToken(
      token: MAC.Of[RefreshToken],
      accessToken: AccessToken,
      scope: Set[ScopeToken],
      expiresAt: Instant,
      accessTokenExpiresAt: Instant,
  ): Task[Boolean] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("renew-bound-refresh-token"):
        // A sender-constrained token is not rotated: a copy of it is inert without the private
        // key, so there is no chain to advance and no predecessor to retire. The row is written
        // once per refresh regardless, because `access_token` has to keep naming the token
        // currently outstanding for revocation to be able to reach it -- so sliding `expires_at`
        // in the same statement is free, and `GREATEST` keeps that idempotent under a retry.
        //
        // `scope` is written for the same reason the rotating path carries it into the
        // successor: this row is the grant's only record, so a narrowing that is not persisted
        // here is one the next refresh silently undoes.
        //
        // `access_token_expires_at` is replaced outright, not `GREATEST`-guarded like
        // `expires_at`: it describes the access token this row now names, and that token's own
        // expiry never needs to be the max of itself and a stale prior value.
        sql"""
          UPDATE refresh_tokens
          SET access_token = $accessToken,
              access_token_expires_at = $accessTokenExpiresAt,
              scope = $scope,
              expires_at = GREATEST(expires_at, $expiresAt)
          WHERE id = $token AND rotated_at IS NULL AND expires_at > $now
        """.update.run() > 0

  override def delete(token: MAC.Of[RefreshToken]): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("delete-refresh-token"):
        sql"""UPDATE refresh_tokens SET expires_at = $now WHERE id = $token""".update.run()
      .unit

  override def deleteByAccessToken(sessionId: MAC.Of[SessionId], token: AccessToken): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("delete-refresh-token-by-access-token"):
        // access_token carries no index of its own; session_id does, and narrows this to a
        // handful of rows before the residual access_token check runs.
        sql"""
          UPDATE refresh_tokens SET expires_at = $now
          WHERE session_id = $sessionId AND access_token = $token
        """.update.run()
      .unit

object PostgresSessionRepository:
  def live: ZLayer[TransactorZIO, Throwable, SessionRepository] =
    ZLayer.fromFunction(PostgresSessionRepository(_))

  /** Namespace (first key) for the transaction-level advisory lock that serialises rotation
    * against revocation within one refresh token family. Advisory-lock keys are global to the
    * entire database, so this must be unique DB-wide: the only other one is
    * `PostgresPasswordRepository.PasswordHistoryLockNamespace` (92). It is `237` (the issue
    * number) by the same convention.
    *
    * The second key is derived from `family_id` via `hashtext`, which is 32-bit, so two families
    * can collide and briefly serialize each other. Like the password-history lock, that costs a
    * short wait and never correctness. Cross-version instability of `hashtext` does not matter
    * here either: the lock never outlives a transaction, and every contending transaction asks
    * the same server for the same value at the same time.
    */
  private val RefreshTokenFamilyLockNamespace: Int = 237

  /** Signals a rotation that lost its race, from inside the transaction body. */
  private case object RotationLost extends RuntimeException("refresh token already exchanged")

  /** The guaranteed *minimum* time a retired token's row survives rotation so a replay of that
    * generation still resolves to its family -- not a cutoff, because the two callers that read
    * a retired row treat expiry past this point differently:
    *
    *   - `findIdempotentRetry` *grants* the caller the family's live tip, so it is strict:
    *     `presented`/`exchanged` are gated on `expires_at > now`, and a row past that instant is
    *     refused even if the cleanup sweep hasn't reclaimed it yet.
    *   - `revokeFamily`'s own family lookup *takes access away*, so it is best-effort: it carries
    *     no expiry check at all and keeps working for as long as the row physically exists.
    *     Detecting a replay late and revoking anyway is strictly safer than not revoking, so
    *     there is nothing to gain from cutting it off at exactly this window -- how far past it
    *     detection still works is however long the cleanup sweep takes to physically delete the
    *     row, not a value this code promises.
    *
    * Independent of refresh_token_ttl on purpose: that value governs how long an *unused* token
    * stays valid, not how far back a replay has to be detectable. Once the row is gone, both
    * callers fall back to a plain invalid_grant with no revocation -- the pre-fix behavior --
    * which is an accepted trade-off for keeping the table's steady-state size bounded by
    * rotation frequency times this window rather than times the (typically much longer)
    * refresh-token TTL.
    *
    * The case this exists for is an attacker rotating a stolen token before the legitimate
    * client wakes up and presents the one it still holds -- that presentation is the only
    * signal the chain leaked. 24h covers the common absence pattern (overnight, a closed
    * laptop) at a bounded cost: retained rows per family are window / refresh interval, so
    * this is ~1 row/family/day at an hourly refresh cadence rather than ~90 at the full
    * refresh-token TTL. A client that goes quiet for longer than this loses guaranteed
    * detection for that gap; if that matters, retaining a narrow tombstone (id, family_id,
    * user_id, client_id, issued_at) instead of the full row would make a much longer window
    * cheap.
    */
  private val ReplayDetectionWindow: Duration = Duration.fromSeconds(24 * 3600)

  private val SerializationFailureSqlState = "40001"
  private val UniqueViolationSqlState      = "23505"
  private val MaxCauseDepth                = 10

  private[session] def isSerializationOrUniqueViolationFailure(t: Throwable, depth: Int = 0): Boolean =
    depth < MaxCauseDepth && (t match
      case sql: SQLException =>
        sql.getSQLState == SerializationFailureSqlState || sql.getSQLState == UniqueViolationSqlState
      case _ => Option(t.getCause).exists(isSerializationOrUniqueViolationFailure(_, depth + 1))
    )

