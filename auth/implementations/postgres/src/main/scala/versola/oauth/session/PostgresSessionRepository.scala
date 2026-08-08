package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.oauth.client.model.{Acr, AuthMethodRef, ClientId, PassedAuthFactor, PassedFactorRecord, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.session.model.{ClientEntry, PriorSession, PublicSessionId, RefreshAlreadyExchanged, RefreshTokenRecord, SessionId, SessionRecord, UserAgentInfo}
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
  given DbCodec[UserAgentInfo] = jsonBCodec[UserAgentInfo]
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
  given DbCodec[Nonce]                         = DbCodec.StringCodec.biMap(Nonce(_), identity[String])
  given DbCodec[RequestedClaims]               = jsonCodec[RequestedClaims]
  given DbCodec[Set[AuthMethodRef]]            = jsonBCodec[Set[AuthMethodRef]]
  given DbCodec[Acr]                           = DbCodec.StringCodec.biMap(Acr(_), identity[String])
  given DbCodec[RefreshTokenRecord]            = DbCodec.derived[RefreshTokenRecord]

  // ── SessionRepository ─────────────────────────────────────────────────────

  override def create(
      id: MAC.Of[SessionId],
      publicId: PublicSessionId,
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
      priorSession: Option[PriorSession],
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      val idleExpiresAt = idleTtl.map(t => now.plusSeconds(t.toSeconds))
      xa.transactMeasured("create-session"):
        createRaw(id, publicId, session, now, now.plusSeconds(ttl.toSeconds), idleExpiresAt, priorSession)

  /** Same insert (including prior-session handling), without the transact/measure wrapper, so it
    * can be composed into a larger transaction (see
    * [[versola.oauth.conversation.PostgresConversationFinalizer]]).
    *
    * @param now used both for `created_at`-relative expiry math already baked into `expiresAt`/
    *            `idleExpiresAt` by the caller, and to timestamp the prior-session invalidation.
    */
  private[oauth] def createRaw(
      id: MAC.Of[SessionId],
      publicId: PublicSessionId,
      session: SessionRecord,
      now: Instant,
      expiresAt: Instant,
      idleExpiresAt: Option[Instant],
      priorSession: Option[PriorSession],
  )(using DbCon): Unit =
    // The new session continues the same browser session as the prior one (step-up,
    // idle-slide re-issue): carry over the RPs already registered on it so none of them
    // miss a later logout notification because of the rotation.
    val priorId = priorSession.map(_.id)
    val priorClients = priorId.toList.flatMap: prior =>
      sql"""SELECT clients FROM sso_sessions WHERE id = $prior""".query[List[ClientEntry]].run().headOption.getOrElse(Nil)
    val clients = (session.clients ++ priorClients).distinctBy(_.clientId)
    sql"""
      INSERT INTO sso_sessions (id, public_id, clients, user_id, user_agent, created_at, amr, expires_at, idle_expires_at)
      VALUES (
        $id,
        $publicId,
        $clients,
        ${session.userId},
        ${session.userAgent},
        ${session.createdAt},
        ${session.amr},
        $expiresAt,
        $idleExpiresAt
      )
    """.update.run()
    priorSession.foreach:
      case PriorSession.Invalidate(prior) =>
        sql"""UPDATE sso_sessions SET expires_at = $now WHERE id = $prior""".update.run()
        sql"""UPDATE refresh_tokens SET expires_at = $now WHERE session_id = $prior""".update.run()
      case PriorSession.MigrateTokens(prior, amr, authTime, acr) =>
        sql"""UPDATE sso_sessions SET expires_at = $now WHERE id = $prior""".update.run()
        sql"""
          UPDATE refresh_tokens
          SET session_id = $id, amr = $amr, auth_time = $authTime, acr = $acr
          WHERE session_id = $prior AND expires_at > $now
        """.update.run()
    ()

  override def findSession(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-session"):
        sql"""
          SELECT user_id, clients, user_agent, created_at, amr, public_id
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
          SELECT user_id, clients, user_agent, created_at, amr, public_id
          FROM sso_sessions
          WHERE
            user_id = $userId
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
          ORDER BY created_at DESC
        """.query[SessionRecord].run().toList
    yield result

  /** Atomically expires all sessions and refresh tokens for the given user. */
  override def invalidateByUserId(userId: UserId): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-sessions-by-user"):
        sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE user_id = $userId
        """.update.run()
        sql"""
          UPDATE refresh_tokens
          SET expires_at = $now
          WHERE user_id = $userId
        """.update.run()
        ()

  override def invalidate(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("invalidate-session"):
        val session = sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE id = $id
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
          RETURNING user_id, clients, user_agent, created_at, amr, public_id
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
          RETURNING id, user_id, clients, user_agent, created_at, amr, public_id
        """.query[(MAC, SessionRecord)].run().headOption
        session.foreach { case (id, _) =>
          sql"""UPDATE refresh_tokens SET expires_at = $now WHERE session_id = $id""".update.run()
        }
        session

  // ── refresh token methods ─────────────────────────────────────────────────

  override def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    xa.repeatableRead.transactMeasured("create-refresh-token") {
      record.previousRefreshToken
        .foreach { oldToken => sql"""DELETE FROM refresh_tokens WHERE id = $oldToken""".update.run() }

      sql"""
        INSERT INTO refresh_tokens (
          id,
          previous_id,
          session_id,
          public_session_id,
          access_token,
          user_id,
          client_id,
          external_audience,
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
          ${record.previousRefreshToken},
          ${record.sessionId},
          ${record.publicSessionId},
          ${record.accessToken},
          ${record.userId},
          ${record.clientId},
          ${record.externalAudience},
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
      case e if PostgresSessionRepository.isSerializationOrUniqueViolationFailure(e) =>
        ZIO.fail(RefreshAlreadyExchanged())
    }

  override def findToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]] =
    for
      now    <- Clock.instant
      result <- xa.connectMeasured("find-refresh-token"):
        sql"""
          SELECT session_id, public_session_id, access_token, user_id, client_id,
                 external_audience, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce, previous_id,
                 amr, auth_time, acr
          FROM refresh_tokens
          WHERE id = $token
        """.query[RefreshTokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result

  override def delete(token: MAC.Of[RefreshToken]): Task[Unit] =
    xa.connectMeasured("delete-refresh-token"):
      sql"""DELETE FROM refresh_tokens WHERE id = $token""".update.run()
    .unit

  override def deleteByAccessToken(token: AccessToken): Task[Unit] =
    xa.connectMeasured("delete-refresh-token-by-access-token"):
      sql"""DELETE FROM refresh_tokens WHERE access_token = $token""".update.run()
    .unit

object PostgresSessionRepository:
  def live: ZLayer[TransactorZIO, Throwable, SessionRepository] =
    ZLayer.fromFunction(PostgresSessionRepository(_))

  private val SerializationFailureSqlState = "40001"
  private val UniqueViolationSqlState      = "23505"
  private val MaxCauseDepth                = 10

  private[session] def isSerializationOrUniqueViolationFailure(t: Throwable, depth: Int = 0): Boolean =
    depth < MaxCauseDepth && (t match
      case sql: SQLException =>
        sql.getSQLState == SerializationFailureSqlState || sql.getSQLState == UniqueViolationSqlState
      case _ => Option(t.getCause).exists(isSerializationOrUniqueViolationFailure(_, depth + 1))
    )

