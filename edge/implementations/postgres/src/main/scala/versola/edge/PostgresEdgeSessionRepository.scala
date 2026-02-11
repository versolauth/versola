package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.*
import versola.edge.model.{EdgeSession, EdgeSessionId}
import versola.oauth.client.model.ClientId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task}

import java.time.Instant

class PostgresEdgeSessionRepository(xa: TransactorZIO) extends EdgeSessionRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[EdgeSession] = DbCodec.derived[EdgeSession]

  override def create(
      id: MAC.Of[EdgeSessionId],
      session: EdgeSession,
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connect:
        sql"""
          INSERT INTO edge_sessions (id, client_id, user_identifier, state, created_at, expires_at)
          VALUES (
            $id,
            ${session.clientId},
            ${session.userIdentifier},
            ${session.state},
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
          SELECT client_id, user_identifier, state, created_at, expires_at
          FROM edge_sessions
          WHERE id = $id
        """.query[(EdgeSession, Instant)].run().headOption
          .collect { case (record, expiresAt) if expiresAt.isAfter(now) => record }
    yield result

  override def delete(id: MAC.Of[EdgeSessionId]): Task[Unit] =
    xa.connect:
      sql"""
        DELETE FROM edge_sessions
        WHERE id = $id
      """.update.run()
    .unit

