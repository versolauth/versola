package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.*
import versola.edge.model.{EdgeSessionId, EdgeTokenRecord}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task}

import java.time.Instant

class PostgresEdgeTokenRepository(xa: TransactorZIO) extends EdgeTokenRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[EdgeTokenRecord] = DbCodec.derived[EdgeTokenRecord]

  override def create(record: EdgeTokenRecord): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO edge_tokens (session_id, client_id, access_token_hash, refresh_token_hash, scope, issued_at, expires_at)
        VALUES (
          ${record.sessionId},
          ${record.clientId},
          ${record.accessTokenHash},
          ${record.refreshTokenHash},
          ${record.scope},
          ${record.issuedAt},
          ${record.expiresAt}
        )
      """.update.run()
    .unit

  override def findBySessionId(sessionId: MAC.Of[EdgeSessionId]): Task[Option[EdgeTokenRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, client_id, access_token_hash, refresh_token_hash, scope, issued_at, expires_at
          FROM edge_tokens
          WHERE session_id = $sessionId
        """.query[EdgeTokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result

  override def findByAccessTokenHash(accessTokenHash: MAC): Task[Option[EdgeTokenRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, client_id, access_token_hash, refresh_token_hash, scope, issued_at, expires_at
          FROM edge_tokens
          WHERE access_token_hash = $accessTokenHash
        """.query[EdgeTokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result

  override def delete(sessionId: MAC.Of[EdgeSessionId]): Task[Unit] =
    xa.connect:
      sql"""
        DELETE FROM edge_tokens
        WHERE session_id = $sessionId
      """.update.run()
    .unit

