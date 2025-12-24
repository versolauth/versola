package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{DbCodec, sql}
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, TokenRecord, WithTtl}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.prelude.These
import zio.{Clock, Task}

import java.time.Instant
import java.util.UUID

class PostgresTokenRepository(xa: TransactorZIO) extends TokenRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[TokenRecord] = DbCodec.derived[TokenRecord]

  override def create(
      tokens: These[WithTtl[MAC.Of[AccessToken]], WithTtl[MAC.Of[RefreshToken]]],
      record: TokenRecord,
  ): Task[Unit] = {
    Clock.instant.flatMap: now =>
      xa.transact:
        tokens match
          case These.Both(accessToken, refreshToken) =>
            createAccessToken(accessToken, record, now).update.run()
            createRefreshToken(refreshToken, record, now).update.run()

          case These.Left(accessToken) =>
            createAccessToken(accessToken, record, now).update.run()

          case These.Right(refreshToken) =>
            createRefreshToken(refreshToken, record, now).update.run()
  }

  private def createAccessToken(
      accessToken: WithTtl[MAC.Of[AccessToken]],
      record: TokenRecord,
      now: Instant,
  ) =
    sql"""
      INSERT INTO access_tokens (id, session_id, user_id, client_id, scope, issued_at, expires_at)
      VALUES (
        ${accessToken.value},
        ${record.sessionId},
        ${record.userId},
        ${record.clientId},
        ${record.scope},
        ${record.issuedAt},
        ${now.plusSeconds(accessToken.ttl.toSeconds)})
    """

  private def createRefreshToken(
      refreshToken: WithTtl[MAC.Of[RefreshToken]],
      record: TokenRecord,
      now: Instant,
  ) =
    sql"""
      INSERT INTO refresh_tokens (id, session_id, user_id, client_id, scope, issued_at, expires_at)
      VALUES (
        ${refreshToken.value},
        ${record.sessionId},
        ${record.userId},
        ${record.clientId},
        ${record.scope},
        ${record.issuedAt},
        ${now.plusSeconds(refreshToken.ttl.toSeconds)})
    """

  override def findAccessToken(token: MAC.Of[AccessToken]): Task[Option[TokenRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, user_id, client_id, scope, issued_at, expires_at
          FROM access_tokens
          WHERE id = $token
        """.query[TokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result

  override def findRefreshToken(token: MAC.Of[RefreshToken]): Task[Option[TokenRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, user_id, client_id, scope, issued_at, expires_at
          FROM refresh_tokens
          WHERE id = $token
        """.query[TokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result
