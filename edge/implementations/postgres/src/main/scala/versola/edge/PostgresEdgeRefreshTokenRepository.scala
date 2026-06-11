package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{AccessTokenId, PresetId}
import versola.edge.session.{EdgeRefreshTokenRecord, EdgeRefreshTokenRepository}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant

class PostgresEdgeRefreshTokenRepository(xa: TransactorZIO) extends EdgeRefreshTokenRepository, BasicCodecs:
  given DbCodec[AccessTokenId] = DbCodec.StringCodec.biMap(AccessTokenId(_), identity[String])
  given DbCodec[PresetId] = DbCodec.StringCodec.biMap(PresetId(_), identity[String])
  given DbCodec[EdgeRefreshTokenRecord] = DbCodec.derived[EdgeRefreshTokenRecord]

  override def create(
      accessTokenId: AccessTokenId,
      record: EdgeRefreshTokenRecord,
  ): Task[Unit] =
    xa.connect {
      sql"""
        INSERT INTO edge_refresh_tokens (id, preset_id, refresh_token, expires_at)
        VALUES ($accessTokenId, ${record.presetId}, ${record.encryptedRefreshToken}, ${record.expiresAt})
        ON CONFLICT (id) DO UPDATE SET
          preset_id = EXCLUDED.preset_id,
          refresh_token = EXCLUDED.refresh_token,
          expires_at = EXCLUDED.expires_at
      """.update.run()
    }.unit

  override def find(accessTokenId: AccessTokenId): Task[Option[EdgeRefreshTokenRecord]] =
    Clock.instant.flatMap { now =>
      xa.connect {
        sql"""
          SELECT preset_id, refresh_token, expires_at
          FROM edge_refresh_tokens
          WHERE id = $accessTokenId AND expires_at > $now
        """
          .query[EdgeRefreshTokenRecord]
          .run()
          .headOption
      }
    }

  override def delete(accessTokenId: AccessTokenId): Task[Unit] =
    xa.connect {
      sql"""
        DELETE FROM edge_refresh_tokens WHERE id = $accessTokenId
      """.update.run()
    }.unit

  override def deleteByPresetId(presetId: PresetId): Task[Unit] =
    xa.connect {
      sql"""
        DELETE FROM edge_refresh_tokens WHERE preset_id = $presetId
      """.update.run()
    }.unit

object PostgresEdgeRefreshTokenRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeRefreshTokenRepository] =
    ZLayer.fromFunction(PostgresEdgeRefreshTokenRepository(_))
