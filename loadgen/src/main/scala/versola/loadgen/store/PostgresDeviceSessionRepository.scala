package versola.loadgen.store

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.model.DeviceSession
import versola.loadgen.protocol.{EdgeSession, RefreshToken, SsoSession}
import zio.{Chunk, Task, ZIO, ZLayer}

import java.time.Instant

class PostgresDeviceSessionRepository(xa: TransactorZIO) extends DeviceSessionRepository, StoreCodecs:

  private given DbCodec[DeviceSession] = DbCodec.derived

  override def insert(session: DeviceSession): Task[Unit] =
    xa.connectMeasured("insert-device-session"):
      sql"""
        INSERT INTO vu_sessions (
          id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session,
          access_expires_at, refresh_expires_at, acr, auth_time, generation, shard
        ) VALUES (
          ${session.id}, ${session.userId}, ${session.kind}, ${session.clientId},
          ${session.refreshToken}, ${session.edgeCookie}, ${session.ssoSession},
          ${session.accessExpiresAt}, ${session.refreshExpiresAt}, ${session.acr},
          ${session.authTime}, ${session.generation}, ${session.shard}
        )
      """.update.run()
    .unit

  override def find(id: Long): Task[Option[DeviceSession]] =
    xa.connectMeasured("find-device-session"):
      sql"""
        SELECT id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session,
               access_expires_at, refresh_expires_at, acr, auth_time, generation, shard
        FROM vu_sessions WHERE id = $id
      """.query[DeviceSession].run().headOption

  override def listByUser(userId: Long): Task[Vector[DeviceSession]] =
    xa.connectMeasured("list-device-sessions-by-user"):
      sql"""
        SELECT id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session,
               access_expires_at, refresh_expires_at, acr, auth_time, generation, shard
        FROM vu_sessions WHERE user_id = $userId ORDER BY id
      """.query[DeviceSession].run()

  /** The `COALESCE` is written identically in the predicate, the sort and
    * `vu_sessions_shard_idx`. Written any other way -- `OR`, a `CASE` on `kind` -- it stops
    * matching the index expression and the startup load becomes a shard-wide sort.
    */
  override def listLive(shard: Int, liveAt: Instant, limit: Int): Task[Vector[DeviceSession]] =
    xa.connectMeasured("list-live-device-sessions"):
      sql"""
        SELECT id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session,
               access_expires_at, refresh_expires_at, acr, auth_time, generation, shard
        FROM vu_sessions
        WHERE shard = $shard AND COALESCE(refresh_expires_at, access_expires_at) > $liveAt
        ORDER BY COALESCE(refresh_expires_at, access_expires_at)
        LIMIT $limit
      """.query[DeviceSession].run()

  override def bumpGeneration(id: Long): Task[Option[Int]] =
    xa.connectMeasured("bump-device-session-generation"):
      sql"UPDATE vu_sessions SET generation = generation + 1 WHERE id = $id RETURNING generation"
        .query[Int].run().headOption

  override def storeRotatedRefresh(
      id: Long,
      expectedGeneration: Int,
      refreshToken: RefreshToken,
      refreshExpiresAt: Instant,
      accessExpiresAt: Instant,
      acr: Option[String],
      authTime: Instant,
  ): Task[Boolean] =
    xa.connectMeasured("store-rotated-refresh-token"):
      sql"""
        UPDATE vu_sessions
        SET refresh_token = $refreshToken,
            refresh_expires_at = $refreshExpiresAt,
            access_expires_at = $accessExpiresAt,
            acr = $acr,
            auth_time = $authTime
        WHERE id = $id AND generation = $expectedGeneration
      """.update.run() > 0

  override def storeEdgeCookie(id: Long, cookie: EdgeSession, accessExpiresAt: Instant): Task[Unit] =
    xa.connectMeasured("store-edge-session-cookie"):
      sql"""
        UPDATE vu_sessions SET edge_cookie = $cookie, access_expires_at = $accessExpiresAt
        WHERE id = $id
      """.update.run()
    .unit

  /** `COALESCE` on each incoming value, so a step-up that rotated neither the refresh token,
    * its expiry, nor the `SSO_SESSION` keeps what the row already holds rather than nulling a
    * credential it had no opinion about.
    *
    * Batched into one round trip for the same reason as
    * [[PostgresVirtualUserRepository.touchAll]].
    */
  override def storeStepUp(
      id: Long,
      acr: String,
      authTime: Instant,
      accessExpiresAt: Instant,
      refreshToken: Option[RefreshToken],
      refreshExpiresAt: Option[Instant],
      ssoSession: Option[SsoSession],
  ): Task[Unit] =
    xa.connectMeasured("store-device-session-step-up"):
      sql"""
        UPDATE vu_sessions
        SET acr = $acr,
            auth_time = $authTime,
            access_expires_at = $accessExpiresAt,
            refresh_token = COALESCE(${refreshToken}::text, refresh_token),
            refresh_expires_at = COALESCE(${refreshExpiresAt}::timestamptz, refresh_expires_at),
            sso_session = COALESCE(${ssoSession}::text, sso_session)
        WHERE id = $id
      """.update.run()
    .unit

  /** Batched into one round trip for the same reason as
    * [[PostgresVirtualUserRepository.touchAll]].
    */
  override def touchAll(touches: Chunk[SessionTouch]): Task[Unit] =
    if touches.isEmpty then ZIO.unit
    else
      xa.transactMeasured("touch-device-sessions"):
        batchUpdate(touches): touch =>
          sql"""
            UPDATE vu_sessions SET access_expires_at = ${touch.accessExpiresAt}
            WHERE id = ${touch.sessionId}
          """.update
      .unit

  override def delete(id: Long): Task[Unit] =
    xa.connectMeasured("delete-device-session"):
      sql"DELETE FROM vu_sessions WHERE id = $id".update.run()
    .unit

object PostgresDeviceSessionRepository:
  def live: ZLayer[TransactorZIO, Throwable, DeviceSessionRepository] =
    ZLayer.fromFunction(PostgresDeviceSessionRepository(_))
