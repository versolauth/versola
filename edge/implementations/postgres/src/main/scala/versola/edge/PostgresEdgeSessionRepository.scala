package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.*
import versola.edge.model.{EdgeSession, EdgeSessionId}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task}

import java.time.Instant

class PostgresEdgeSessionRepository(xa: TransactorZIO) extends EdgeSessionRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[EdgeSession] = DbCodec.derived[EdgeSession]

  override def create(
      id: MAC.Of[EdgeSessionId],
      session: EdgeSession,
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connect:
        sql"""
          INSERT INTO edge_sessions (
            id, client_id, state,
            access_token_encrypted, refresh_token_encrypted,
            token_expires_at, scope, created_at, session_expires_at
          )
          VALUES (
            $id,
            ${session.clientId},
            ${session.state},
            ${session.accessTokenEncrypted},
            ${session.refreshTokenEncrypted},
            ${session.tokenExpiresAt},
            ${session.scope},
            ${session.createdAt},
            ${now.plusSeconds(ttl.toSeconds)}
          )
        """.update.run()
      .unit

  override def find(id: MAC.Of[EdgeSessionId]): Task[Option[EdgeSession]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT client_id, state,
                 access_token_encrypted, refresh_token_encrypted,
                 token_expires_at, scope, created_at, session_expires_at
          FROM edge_sessions
          WHERE id = $id AND session_expires_at > $now
        """.query[EdgeSession].run().headOption
    yield result

  override def findByClientId(clientId: ClientId): Task[List[EdgeSession]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT client_id, state,
                 access_token_encrypted, refresh_token_encrypted,
                 token_expires_at, scope, created_at, session_expires_at
          FROM edge_sessions
          WHERE client_id = $clientId AND session_expires_at > $now
        """.query[EdgeSession].run()
    yield result.toList

  override def delete(id: MAC.Of[EdgeSessionId]): Task[Unit] =
    xa.connect:
      sql"""
        DELETE FROM edge_sessions
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteByClientId(clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""
        DELETE FROM edge_sessions
        WHERE client_id = $clientId
      """.update.run()
    .unit

