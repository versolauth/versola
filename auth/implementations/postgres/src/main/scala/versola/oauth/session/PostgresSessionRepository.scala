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

  /** Atomically expires all active sessions and refresh tokens for the given user. */
  override def invalidateByUserId(userId: UserId): Task[List[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-sessions-by-user"):
        val sessions = sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE user_id = $userId AND expires_at > $now
          RETURNING user_id, clients, user_agent_id, created_at, amr, public_id, expires_at
        """.query[SessionRecord].run().toList
        sql"""
          UPDATE refresh_tokens
          SET expires_at = $now
          WHERE user_id = $userId
        """.update.run()
        sessions

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

  override def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      previous: Option[MAC.Of[RefreshToken]],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("create-refresh-token") {
        val familyId = previous match
          case None =>
            // A fresh chain: the token is the root of its own family.
            refreshToken

          case Some(previousToken) =>
            val family = sql"""SELECT family_id FROM refresh_tokens WHERE id = $previousToken"""
              .query[MAC.Of[RefreshToken]]
              .run()
              .headOption
              .getOrElse(throw PostgresSessionRepository.RotationLost)

            // Both this and revokeFamily take the root's lock before touching any member, so
            // a rotation cannot slip a successor past a revocation running beside it: one of
            // the two waits, and whichever goes second sees the other's committed state. The
            // family is read unlocked above only because a token never changes families.
            val rootLocked = sql"""SELECT 1 FROM refresh_tokens WHERE id = $family FOR UPDATE"""
              .query[Int]
              .run()
              .nonEmpty

            // No root left to lock (swept, or revoked through the token endpoint) means the
            // family is gone and nothing here can be serialised against. Fail closed.
            if !rootLocked then throw PostgresSessionRepository.RotationLost

            // Retire the presented token. Zero rows means it was already exchanged -- either
            // earlier, or by a concurrent request that just released the lock above.
            val retired = sql"""
              UPDATE refresh_tokens
              SET rotated_at = $now, expires_at = ${record.expiresAt}
              WHERE id = $previousToken AND rotated_at IS NULL AND expires_at > $now
            """.update.run()

            if retired == 0 then throw PostgresSessionRepository.RotationLost

            // Keep the root alive for as long as the family it anchors: it is the lock target
            // every later rotation and revocation depends on, and the sweep would otherwise
            // collect it once the generation that retired it aged out.
            sql"""
              UPDATE refresh_tokens
              SET expires_at = GREATEST(expires_at, ${record.expiresAt})
              WHERE id = $family
            """.update.run()

            family

        sql"""
          INSERT INTO refresh_tokens (
            id,
            family_id,
            session_id,
            public_session_id,
            access_token,
            user_id,
            client_id,
            audience,
            authorization_details,
            scope,
            issued_at,
            expires_at,
            requested_claims,
            ui_locales,
            nonce,
            amr,
            auth_time,
            acr
          )
          VALUES (
            $refreshToken,
            $familyId,
            ${record.sessionId},
            ${record.publicSessionId},
            ${record.accessToken},
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
            ${record.acr}
          )
          """.update.run()
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
          SELECT session_id, public_session_id, access_token, user_id, client_id,
                 audience, authorization_details, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce,
                 amr, auth_time, acr
          FROM refresh_tokens
          WHERE id = $token AND expires_at > $now AND rotated_at IS NULL"""
          .query[RefreshTokenRecord]
          .run()
          .headOption
    yield result

  override def revokeFamily(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
      accessTokensIssuedAfter: Instant,
  ): Task[Option[RevokedFamily]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("revoke-refresh-token-family") {
        sql"""
          SELECT family_id, user_id
          FROM refresh_tokens
          WHERE id = $token AND client_id = $clientId AND rotated_at IS NOT NULL
        """.query[(MAC.Of[RefreshToken], UserId)]
          .run()
          .headOption
          .map: (family, userId) =>
            // Same lock createRefreshToken takes, and for the same reason: a rotation in
            // flight either commits first and has its successor expired by the update below,
            // or waits here and then finds the token it meant to rotate already dead.
            sql"""SELECT 1 FROM refresh_tokens WHERE id = $family FOR UPDATE""".query[Int].run()

            val revoked = sql"""
              UPDATE refresh_tokens
              SET expires_at = $now
              WHERE family_id = $family AND expires_at > $now
              RETURNING access_token, issued_at
            """.query[(AccessToken, Instant)].run()

            RevokedFamily(
              userId = userId,
              // Older generations are past their access-token TTL, so pushing them to the
              // client's back channel would revoke nothing.
              accessTokens = revoked.view
                .filter(_._2.isAfter(accessTokensIssuedAfter))
                .map(_._1)
                .toList,
            )
      }

  override def delete(token: MAC.Of[RefreshToken]): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("delete-refresh-token"):
        sql"""UPDATE refresh_tokens SET expires_at = $now WHERE id = $token""".update.run()
      .unit

  override def deleteByAccessToken(token: AccessToken): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("delete-refresh-token-by-access-token"):
        sql"""UPDATE refresh_tokens SET expires_at = $now WHERE access_token = $token""".update.run()
      .unit

object PostgresSessionRepository:
  def live: ZLayer[TransactorZIO, Throwable, SessionRepository] =
    ZLayer.fromFunction(PostgresSessionRepository(_))

  /** Signals a rotation that lost its race, from inside the transaction body. */
  private case object RotationLost extends RuntimeException("refresh token already exchanged")

  private val SerializationFailureSqlState = "40001"
  private val UniqueViolationSqlState      = "23505"
  private val MaxCauseDepth                = 10

  private[session] def isSerializationOrUniqueViolationFailure(t: Throwable, depth: Int = 0): Boolean =
    depth < MaxCauseDepth && (t match
      case sql: SQLException =>
        sql.getSQLState == SerializationFailureSqlState || sql.getSQLState == UniqueViolationSqlState
      case _ => Option(t.getCause).exists(isSerializationOrUniqueViolationFailure(_, depth + 1))
    )

