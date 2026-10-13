package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{AccessTokenId, PresetId, SessionId}
import versola.edge.session.{EdgeSessionRecord, EdgeSessionRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task, ZLayer}

import java.time.Instant

class PostgresEdgeSessionRepository(xa: TransactorZIO) extends EdgeSessionRepository, BasicCodecs:
  given DbCodec[AccessTokenId] = DbCodec.StringCodec.biMap(AccessTokenId(_), identity[String])
  given DbCodec[PresetId] = DbCodec.StringCodec.biMap(PresetId(_), identity[String])
  given DbCodec[SessionId] = DbCodec.StringCodec.biMap(SessionId(_), identity[String])
  given DbCodec[EdgeSessionRecord] = DbCodec.derived[EdgeSessionRecord]

  override def create(record: EdgeSessionRecord): Task[Unit] =
    Clock.instant.flatMap(now =>
      xa.connectMeasured("create-edge-session") {
        sql"""
        INSERT INTO edge_sessions (public_session_id, preset_id, access_token_id, refresh_token, expires_at, access_token)
        VALUES (${record.publicSessionId}, ${record.presetId}, ${record.accessTokenId}, ${record.encryptedRefreshToken}, ${record.expiresAt}, ${record.encryptedAccessToken})
        ON CONFLICT (public_session_id, preset_id) DO UPDATE SET
          -- an upsert that keeps the same token (a repeated write) must not push it into `previous`
          previous_access_token_id = CASE
            WHEN edge_sessions.access_token_id <> EXCLUDED.access_token_id THEN edge_sessions.access_token_id
            ELSE edge_sessions.previous_access_token_id
          END,
          rotated_at = CASE
            WHEN edge_sessions.access_token_id <> EXCLUDED.access_token_id THEN $now
            ELSE edge_sessions.rotated_at
          END,
          access_token_id = EXCLUDED.access_token_id,
          refresh_token = EXCLUDED.refresh_token,
          access_token = EXCLUDED.access_token,
          expires_at = EXCLUDED.expires_at,
          refresh_lease_until = NULL
      """.update.run()
      },
    ).unit

  override def findByAccessTokenId(accessTokenId: AccessTokenId): Task[Option[EdgeSessionRecord]] =
    Clock.instant.flatMap { now =>
      xa.connectMeasured("find-edge-session") {
        sql"""
          SELECT public_session_id, preset_id, access_token_id, refresh_token, expires_at, previous_access_token_id, access_token, rotated_at
          FROM edge_sessions
          WHERE access_token_id = $accessTokenId AND expires_at > $now
        """
          .query[EdgeSessionRecord]
          .run()
          .headOption
      }
    }

  override def findByPreviousAccessTokenId(accessTokenId: AccessTokenId, rotatedAfter: Instant): Task[Option[EdgeSessionRecord]] =
    Clock.instant.flatMap { now =>
      xa.connectMeasured("find-edge-session-by-previous") {
        sql"""
          SELECT public_session_id, preset_id, access_token_id, refresh_token, expires_at, previous_access_token_id, access_token, rotated_at
          FROM edge_sessions
          WHERE previous_access_token_id = $accessTokenId AND expires_at > $now AND rotated_at > $rotatedAfter
        """
          .query[EdgeSessionRecord]
          .run()
          .headOption
      }
    }

  override def claimRefresh(
      accessTokenId: AccessTokenId,
      now: Instant,
      leaseUntil: Instant,
  ): Task[Option[EdgeSessionRecord]] =
    // One statement, so the check and the take cannot interleave with another claim; no transaction
    // outlives it, which is what lets the caller hold the lease across an HTTP call to auth.
    xa.connectMeasured("claim-edge-session-refresh") {
      sql"""
        UPDATE edge_sessions SET refresh_lease_until = $leaseUntil
        WHERE access_token_id = $accessTokenId
          AND expires_at > $now
          AND (refresh_lease_until IS NULL OR refresh_lease_until < $now)
        RETURNING public_session_id, preset_id, access_token_id, refresh_token, expires_at, previous_access_token_id, access_token, rotated_at
      """
        .query[EdgeSessionRecord]
        .run()
        .headOption
    }

  override def releaseRefresh(accessTokenId: AccessTokenId): Task[Unit] =
    xa.connectMeasured("release-edge-session-refresh") {
      sql"""
        UPDATE edge_sessions SET refresh_lease_until = NULL WHERE access_token_id = $accessTokenId
      """.update.run()
    }.unit

  override def delete(accessTokenId: AccessTokenId): Task[Unit] =
    xa.connectMeasured("delete-edge-session") {
      sql"""
        DELETE FROM edge_sessions WHERE access_token_id = $accessTokenId
      """.update.run()
    }.unit

  override def findBySessionId(sid: SessionId): Task[List[EdgeSessionRecord]] =
    xa.connectMeasured("find-edge-sessions-by-sid") {
      sql"""
        SELECT public_session_id, preset_id, access_token_id, refresh_token, expires_at, previous_access_token_id, access_token, rotated_at
        FROM edge_sessions WHERE public_session_id = $sid
      """
        .query[EdgeSessionRecord]
        .run()
        .toList
    }

object PostgresEdgeSessionRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeSessionRepository] =
    ZLayer.fromFunction(PostgresEdgeSessionRepository(_))
