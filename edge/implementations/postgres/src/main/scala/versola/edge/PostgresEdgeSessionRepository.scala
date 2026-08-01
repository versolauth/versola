package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{AccessTokenId, PresetId, SessionId}
import versola.edge.session.{EdgeSessionRecord, EdgeSessionRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

class PostgresEdgeSessionRepository(xa: TransactorZIO) extends EdgeSessionRepository, BasicCodecs:
  given DbCodec[AccessTokenId] = DbCodec.StringCodec.biMap(AccessTokenId(_), identity[String])
  given DbCodec[PresetId] = DbCodec.StringCodec.biMap(PresetId(_), identity[String])
  given DbCodec[SessionId] = DbCodec.StringCodec.biMap(SessionId(_), identity[String])
  given DbCodec[EdgeSessionRecord] = DbCodec.derived[EdgeSessionRecord]

  override def create(record: EdgeSessionRecord): Task[Unit] =
    xa.connectMeasured("create-edge-session") {
      sql"""
        INSERT INTO edge_sessions (public_session_id, preset_id, access_token_id, refresh_token, expires_at)
        VALUES (${record.publicSessionId}, ${record.presetId}, ${record.accessTokenId}, ${record.encryptedRefreshToken}, ${record.expiresAt})
        ON CONFLICT (public_session_id, preset_id) DO UPDATE SET
          access_token_id = EXCLUDED.access_token_id,
          refresh_token = EXCLUDED.refresh_token,
          expires_at = EXCLUDED.expires_at
      """.update.run()
    }.unit

  override def findByAccessTokenId(accessTokenId: AccessTokenId): Task[Option[EdgeSessionRecord]] =
    Clock.instant.flatMap { now =>
      xa.connectMeasured("find-edge-session") {
        sql"""
          SELECT public_session_id, preset_id, access_token_id, refresh_token, expires_at
          FROM edge_sessions
          WHERE access_token_id = $accessTokenId AND expires_at > $now
        """
          .query[EdgeSessionRecord]
          .run()
          .headOption
      }
    }

  override def delete(accessTokenId: AccessTokenId): Task[Unit] =
    xa.connectMeasured("delete-edge-session") {
      sql"""
        DELETE FROM edge_sessions WHERE access_token_id = $accessTokenId
      """.update.run()
    }.unit

  override def deleteBySessionId(sid: SessionId): Task[List[EdgeSessionRecord]] =
    xa.connectMeasured("delete-edge-sessions-by-sid") {
      sql"""
        DELETE FROM edge_sessions WHERE public_session_id = $sid
        RETURNING public_session_id, preset_id, access_token_id, refresh_token, expires_at
      """
        .query[EdgeSessionRecord]
        .run()
        .toList
    }

object PostgresEdgeSessionRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeSessionRepository] =
    ZLayer.fromFunction(PostgresEdgeSessionRepository(_))
