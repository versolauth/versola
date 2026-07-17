package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.oauth.client.model.{AuthMethodRef, ClientId, PassedAuthFactor, PassedFactorRecord, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, SessionId, SessionRecord, UserAgentInfo}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Clock, Duration, IO, Task, ZIO, ZLayer}

import java.sql.{Connection, SQLException}
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
  given DbCodec[UserAgentInfo] = jsonBCodec[UserAgentInfo]
  given amrSessionCodec: DbCodec[Map[PassedAuthFactor, PassedFactorRecord]] =
    jsonBCodec[Map[PassedAuthFactor, PassedFactorRecord]]
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
  given DbCodec[RefreshTokenRecord]            = DbCodec.derived[RefreshTokenRecord]

  // ── SessionRepository ─────────────────────────────────────────────────────

  override def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      val idleExpiresAt = idleTtl.map(t => now.plusSeconds(t.toSeconds))
      xa.connectMeasured("create-session"):
        sql"""
          INSERT INTO sso_sessions (id, client_id, user_id, user_agent, created_at, amr, expires_at, idle_expires_at)
          VALUES (
            $id,
            ${session.clientId},
            ${session.userId},
            ${session.userAgent},
            ${session.createdAt},
            ${session.amr},
            ${now.plusSeconds(ttl.toSeconds)},
            $idleExpiresAt
          )
        """.update.run()
      .unit

  override def findSession(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-session"):
        sql"""
          SELECT user_id, client_id, user_agent, created_at, amr
          FROM sso_sessions
          WHERE id = $id
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
        """.query[SessionRecord].run().headOption

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
          SELECT user_id, client_id, user_agent, created_at, amr
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

  // ── refresh token methods ─────────────────────────────────────────────────

  override def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    xa.withConnectionConfig(
      _.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ),
    ).transactMeasured("create-refresh-token") {
      record.previousRefreshToken
        .foreach { oldToken => sql"""DELETE FROM refresh_tokens WHERE id = $oldToken""".update.run() }

      sql"""
        INSERT INTO refresh_tokens (
          id,
          previous_id,
          session_id,
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
          auth_time
        )
        VALUES (
          $refreshToken,
          ${record.previousRefreshToken},
          ${record.sessionId},
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
          ${record.authTime}
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
          SELECT session_id, access_token, user_id, client_id,
                 external_audience, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce, previous_id,
                 amr, auth_time
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

