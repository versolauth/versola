package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{DbCodec, sql}
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, TokenCreationRecord, TokenRecord, WithTtl}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.prelude.These
import zio.{Clock, Task}

import java.time.Instant
import java.util.UUID

class PostgresRefreshTokenRepository(xa: TransactorZIO) extends RefreshTokenRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[TokenRecord] = DbCodec.derived[TokenRecord]

  override def create(
      refreshToken: MAC.Of[RefreshToken],
      ttl: zio.Duration,
      record: TokenCreationRecord,
  ): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO refresh_tokens (id, session_id, user_id, client_id, scope, issued_at, expires_at)
        VALUES (
          ${refreshToken},
          ${record.sessionId},
          ${record.userId},
          ${record.clientId},
          ${record.scope},
          ${record.issuedAt},
          ${record.issuedAt.plusSeconds(ttl.toSeconds)})
      """.update.run()
    .unit

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
